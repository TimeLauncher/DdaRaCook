package com.example.myapplication

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.camera.CaptureFailureKind
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.CapturePurpose
import com.example.myapplication.camera.CaptureRequest
import com.example.myapplication.camera.DatWearableCameraGateway
import com.example.myapplication.camera.FakeCaptureBehavior
import com.example.myapplication.camera.FakeWearableCameraGateway
import com.example.myapplication.camera.WearableCameraGateway
import com.example.myapplication.camera.WearableCameraState
import com.example.myapplication.judgment.FakeJudgmentBehavior
import com.example.myapplication.judgment.FakeJudgmentGateway
import com.example.myapplication.judgment.ImageNormalizer
import com.example.myapplication.judgment.JudgmentImagePolicy
import com.example.myapplication.judgment.JudgmentOutcome
import com.example.myapplication.judgment.JudgmentRequest
import com.example.myapplication.judgment.JudgeApiService
import com.example.myapplication.judgment.shouldSendStartImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

private const val AUTO_ADVANCE_DELAY_MS = 2_000L
private const val MAX_CONSECUTIVE_CAMERA_TIMEOUTS = 2
private const val REQUIRED_CAPTURE_MAX_ATTEMPTS = 3
private const val REQUIRED_CAPTURE_RETRY_DELAY_MS = 3_000L
private const val NEXT_COMMAND_DEBOUNCE_MS = 3_000L
private const val CAMERA_STREAM_READY_TIMEOUT_MS = 12_000L
private const val CAMERA_PHOTO_CAPTURE_TIMEOUT_MS = 20_000L
private const val MAX_VIEWED_RECIPE_COUNT = 30
private const val MANUAL_ADVANCE_BASELINE_DELAY_SECONDS = 5
private const val PRESENTATION_CAPTURE_REVEAL_DELAY_MS = 3_000L

class CookingSessionViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val persistence = AppPersistence(application)
    private val cameraGateway: WearableCameraGateway = if (BuildConfig.USE_FAKE_CAMERA) {
        FakeWearableCameraGateway(application)
    } else {
        DatWearableCameraGateway(application)
    }
    private val fakeJudgmentGateway = FakeJudgmentGateway()
    private val networkJudgmentGateway = JudgeApiService(application)
    private val fixtureRecipes = RecipeFixtures.sampleRecipes().sortedByDescending(Recipe::isMvpReady)
    private val initialRecipes = persistence.loadRecipes(fixtureRecipes).withAutomaticInspectionInterval()
    private val initialServerBaseUrl = persistence.loadServerBaseUrl(BuildConfig.JUDGE_BASE_URL)
    private val initialUseMockJudgment = persistence.loadUseMockJudgment(fallback = false)
    private val initialScrappedRecipeIds = persistence.loadScrappedRecipeIds()
    private val initialViewedRecipeIds = persistence.loadViewedRecipeIds()
    private val initialVoiceGuidanceEnabled = persistence.loadVoiceGuidanceEnabled()
    private val restoredSession = persistence.loadSession()?.takeIf { saved ->
        initialRecipes.any { it.id == saved.recipeId } && saved.phase != CookingPhase.SESSION_COMPLETED
    }

    private val mutableUiState = MutableStateFlow(
        CookingSessionUiState(
            recipes = initialRecipes,
            selectedRecipeId = restoredSession?.recipeId ?: initialRecipes.first().id,
            session = restoredSession,
            hasResumableSession = restoredSession != null,
            useFakeCamera = cameraGateway.isFake,
            useMockJudgment = initialUseMockJudgment,
            deviceHint = if (cameraGateway.isFake) {
                "가짜 안경 게이트웨이를 사용 중입니다."
            } else {
                "실제 Meta 안경 연결을 준비합니다."
            },
            serverBaseUrl = initialServerBaseUrl,
            scrappedRecipeIds = initialScrappedRecipeIds,
            viewedRecipeIds = initialViewedRecipeIds,
            voiceGuidanceEnabled = initialVoiceGuidanceEnabled
        )
    )
    val uiState: StateFlow<CookingSessionUiState> = mutableUiState.asStateFlow()

    init {
        persistence.saveRecipes(initialRecipes)
        networkJudgmentGateway.updateBaseUrl(initialServerBaseUrl)
        if (!initialUseMockJudgment) checkServerHealth()
    }

    private var announcementId = 0L
    private var inspectionCountdownJob: Job? = null
    private var inspectionExecutionJob: Job? = null
    private var baselineCaptureJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var autoAdvanceJob: Job? = null
    private var presentationCaptureRevealJob: Job? = null
    private var pendingManualInspectionStepOrder: Int? = null
    private var lastNextCommandAtMs = 0L

    init {
        viewModelScope.launch {
            cameraGateway.state.collectLatest { state ->
                mutableUiState.update {
                    it.copy(cameraState = state, deviceHint = cameraStateHint(state, cameraGateway.isFake))
                }
                val current = uiState.value
                if (
                    !cameraGateway.isFake &&
                    !current.isPresentationSimulation &&
                    current.session?.mode == SessionMode.AUTO &&
                    current.currentScreen in setOf(
                        AppScreen.S5_COOKING,
                        AppScreen.S6_STEP_DONE,
                        AppScreen.S7_NEEDS_VIEW
                    ) &&
                    (state == WearableCameraState.Disconnected || state is WearableCameraState.Error)
                ) {
                    transitionToManualMode("안경 연결을 확인할 수 없어 수동 모드로 전환합니다.")
                }
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                persistence.saveSession(state.session.takeUnless { state.isPresentationSimulation })
            }
        }
    }

    fun openPresentationSimulationDetail() {
        cancelInspectionWork()
        persistence.saveSession(null)
        val recipe = uiState.value.recipes.firstOrNull {
            it.id == PresentationSimulation.RECIPE_ID
        } ?: return
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipe.id,
                currentScreen = AppScreen.S2_RECIPE_DETAIL,
                recipeDetailReturnScreen = AppScreen.S1_HOME,
                session = null,
                hasResumableSession = false,
                resumeAutoAfterDeviceSetup = false,
                currentCaptureOutcome = null,
                lastCaptureFailureKind = null,
                nextInspectionInSeconds = null,
                judgingInFlight = false,
                judgeError = null,
                presentationSimulationSelected = true,
                presentationCaptureVisible = false
            )
        }
    }

    fun openPresentationSimulationDevicePreparation() {
        cancelInspectionWork()
        val recipe = uiState.value.recipes.firstOrNull {
            it.id == PresentationSimulation.RECIPE_ID
        } ?: return
        val now = System.currentTimeMillis()
        val session = createFreshSession(
            recipeId = recipe.id,
            phase = CookingPhase.READY
        ).copy(
            logs = listOf(
                SessionLogEntry(
                    timestampMs = now,
                    stepOrder = recipe.steps.first().order,
                    message = "발표용 자동모드 준비",
                    eventType = PresentationSimulation.EVENT_TYPE
                )
            )
        )
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipe.id,
                currentScreen = AppScreen.S4_DEVICE,
                session = session,
                cameraState = WearableCameraState.Ready,
                deviceHint = "안경 카메라 준비 완료. 검사 순간에는 발표용 캡처본을 표시합니다.",
                hasResumableSession = false,
                resumeAutoAfterDeviceSetup = false,
                currentCaptureOutcome = null,
                judgeError = null,
                nextInspectionInSeconds = null,
                presentationSimulationSelected = true,
                presentationCaptureVisible = false
            )
        }
    }

    fun startPresentationSimulation() {
        cancelInspectionWork()
        persistence.saveSession(null)
        val recipe = uiState.value.recipes.firstOrNull {
            it.id == PresentationSimulation.RECIPE_ID
        } ?: return
        val now = System.currentTimeMillis()
        val session = createFreshSession(
            recipeId = recipe.id,
            phase = CookingPhase.WAITING_FOR_CHECK
        ).copy(
            currentStepStartedAtMs = now,
            stepStartedAtMsByOrder = mapOf(recipe.steps.first().order to now),
            logs = listOf(
                SessionLogEntry(
                    timestampMs = now,
                    stepOrder = recipe.steps.first().order,
                    message = "발표용 자동모드 시작",
                    eventType = PresentationSimulation.EVENT_TYPE
                )
            )
        )
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipe.id,
                currentScreen = AppScreen.S5_COOKING,
                session = session,
                hasResumableSession = false,
                resumeAutoAfterDeviceSetup = false,
                currentCaptureOutcome = null,
                lastCaptureFailureKind = null,
                nextInspectionInSeconds = null,
                judgingInFlight = false,
                judgeError = null,
                presentationSimulationSelected = true,
                presentationCaptureVisible = false
            )
        }
        announceCurrent(recipe.steps.first().voicePrompt)
        startElapsedTicker()
        schedulePresentationCaptureReveal()
    }

    fun revealPresentationCapture() {
        if (!uiState.value.isPresentationSimulation || uiState.value.currentScreen != AppScreen.S5_COOKING) return
        presentationCaptureRevealJob?.cancel()
        presentationCaptureRevealJob = null
        mutableUiState.update { it.copy(presentationCaptureVisible = true) }
    }

    fun continuePresentationSimulation() {
        val state = uiState.value
        if (!state.isPresentationSimulation) {
            continueAutoButtonToNextStep()
            return
        }
        presentationCaptureRevealJob?.cancel()
        presentationCaptureRevealJob = null
        val recipe = state.selectedRecipe ?: return
        val session = state.session ?: return
        val now = System.currentTimeMillis()
        val completedOrder = recipe.steps[session.currentStepIndex].order
        val nextIndex = session.currentStepIndex + 1
        val completed = session.copy(
            completedStepOrders = session.completedStepOrders + completedOrder,
            stepCompletedAtMsByOrder = session.stepCompletedAtMsByOrder + (completedOrder to now),
            autoDoneCount = session.autoDoneCount + 1,
            logs = session.logs + SessionLogEntry(
                timestampMs = now,
                stepOrder = completedOrder,
                message = "발표용 캡처 확인 후 다음 단계",
                eventType = PresentationSimulation.EVENT_TYPE
            )
        )
        if (nextIndex >= recipe.steps.size) {
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S9_SUMMARY,
                    session = completed.copy(
                        phase = CookingPhase.SESSION_COMPLETED,
                        completedAtMs = now
                    ),
                    nextInspectionInSeconds = null,
                    presentationCaptureVisible = false
                )
            }
            announceCurrent("${recipe.title} 조리가 끝났습니다.")
            return
        }
        val nextStep = recipe.steps[nextIndex]
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = completed.copy(
                    phase = CookingPhase.WAITING_FOR_CHECK,
                    currentStepIndex = nextIndex,
                    currentStepStartedAtMs = now,
                    stepStartedAtMsByOrder = completed.stepStartedAtMsByOrder + (nextStep.order to now),
                    currentVerdict = null,
                    activeRequestId = null
                ),
                currentCaptureOutcome = null,
                nextInspectionInSeconds = null,
                judgingInFlight = false,
                judgeError = null,
                presentationCaptureVisible = false
            )
        }
        announceCurrent(nextStep.voicePrompt)
        schedulePresentationCaptureReveal()
    }

    fun openRecipeEditor(recipeId: String? = null) {
        cancelInspectionWork()
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipeId ?: "",
                currentScreen = AppScreen.S3_RECIPE_EDITOR,
                session = null,
                hasResumableSession = false,
                presentationSimulationSelected = false,
                presentationCaptureVisible = false
            )
        }
    }

    fun saveRecipe(recipe: Recipe) {
        if (recipe.validationErrors().isNotEmpty()) return
        val normalized = recipe.copy(
            id = recipe.id.ifBlank { "recipe-${UUID.randomUUID()}" },
            steps = recipe.steps.mapIndexed { index, step -> step.copy(order = index + 1) }
        )
        val recipes = uiState.value.recipes.toMutableList().apply {
            val index = indexOfFirst { it.id == normalized.id }
            if (index >= 0) set(index, normalized) else add(normalized)
        }
        persistence.saveRecipes(recipes)
        mutableUiState.update {
            it.copy(
                recipes = recipes,
                selectedRecipeId = normalized.id,
                currentScreen = AppScreen.S2_RECIPE_DETAIL
            )
        }
    }

    fun cancelRecipeEditor() {
        mutableUiState.update {
            it.copy(currentScreen = if (it.selectedRecipeId.isBlank()) AppScreen.S1_HOME else AppScreen.S2_RECIPE_DETAIL)
        }
    }

    fun deleteSessionImages() {
        val session = uiState.value.session ?: return
        val imageUris = (session.lastCaptureUriByStep.values + session.baselineUriByStep.values)
            .distinct()
        viewModelScope.launch {
            imageUris.forEach { cameraGateway.deleteArtifact(it) }
            mutableUiState.update { state ->
                val currentSession = state.session ?: return@update state
                state.copy(
                    session = currentSession.copy(
                        lastCaptureUriByStep = emptyMap(),
                        baselineUriByStep = emptyMap(),
                        logs = currentSession.logs.map { it.copy(imageUri = null) }
                    )
                )
            }
        }
    }

    fun recordGroundTruth(requestId: String, verdict: JudgmentVerdict) {
        mutableUiState.update { state ->
            val session = state.session ?: return@update state
            state.copy(session = session.copy(logs = session.logs.map { log ->
                if (log.requestId == requestId && log.verdict != null) log.copy(groundTruth = verdict) else log
            }))
        }
    }

    fun resumeSavedSession() {
        val session = uiState.value.session ?: return
        mutableUiState.update {
            it.copy(
                selectedRecipeId = session.recipeId,
                currentScreen = screenForSession(session),
                hasResumableSession = false,
                session = session.copy(activeRequestId = null)
            )
        }
        startElapsedTicker()
        if (session.mode == SessionMode.AUTO && session.phase == CookingPhase.WAITING_FOR_CHECK) {
            scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
        }
    }

    fun selectRecipe(recipeId: String) {
        if (uiState.value.recipes.none { it.id == recipeId }) return
        cancelInspectionWork()
        mutableUiState.update { state ->
            val viewedRecipeIds = (listOf(recipeId) + state.viewedRecipeIds.filterNot { it == recipeId })
                .take(MAX_VIEWED_RECIPE_COUNT)
            persistence.saveViewedRecipeIds(viewedRecipeIds)
            state.copy(
                selectedRecipeId = recipeId,
                currentScreen = AppScreen.S2_RECIPE_DETAIL,
                viewedRecipeIds = viewedRecipeIds,
                recipeDetailReturnScreen = if (state.currentScreen == AppScreen.S10_MY) AppScreen.S10_MY else AppScreen.S1_HOME,
                session = null,
                currentCaptureOutcome = null,
                lastCaptureFailureKind = null,
                judgeError = null,
                nextInspectionInSeconds = null,
                presentationSimulationSelected = false,
                presentationCaptureVisible = false
            )
        }
    }

    fun openMyPage() {
        mutableUiState.update { it.copy(currentScreen = AppScreen.S10_MY) }
    }

    fun closeMyPage() {
        mutableUiState.update { it.copy(currentScreen = AppScreen.S1_HOME) }
    }

    fun backFromRecipeDetail() {
        if (uiState.value.presentationSimulationSelected) {
            backToHome()
            return
        }
        mutableUiState.update { state ->
            state.copy(
                currentScreen = if (state.recipeDetailReturnScreen == AppScreen.S10_MY) AppScreen.S10_MY else AppScreen.S1_HOME
            )
        }
    }

    fun toggleRecipeScrap(recipeId: String) {
        if (uiState.value.recipes.none { it.id == recipeId }) return
        mutableUiState.update { state ->
            val scrappedRecipeIds = if (recipeId in state.scrappedRecipeIds) {
                state.scrappedRecipeIds - recipeId
            } else {
                state.scrappedRecipeIds + recipeId
            }
            persistence.saveScrappedRecipeIds(scrappedRecipeIds)
            state.copy(scrappedRecipeIds = scrappedRecipeIds)
        }
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        persistence.saveVoiceGuidanceEnabled(enabled)
        mutableUiState.update { it.copy(voiceGuidanceEnabled = enabled) }
    }

    fun backToHome() {
        cancelInspectionWork()
        persistence.saveSession(null)
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S1_HOME,
                session = null,
                hasResumableSession = false,
                nextInspectionInSeconds = null,
                presentationSimulationSelected = false,
                presentationCaptureVisible = false
            )
        }
    }

    fun backFromDevicePreparation() {
        cancelInspectionWork()
        elapsedTickerJob?.cancel()
        elapsedTickerJob = null
        pendingManualInspectionStepOrder = null
        if (uiState.value.isPresentationSimulation) {
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S2_RECIPE_DETAIL,
                    session = null,
                    hasResumableSession = false,
                    resumeAutoAfterDeviceSetup = false,
                    currentCaptureOutcome = null,
                    judgeError = null,
                    nextInspectionInSeconds = null,
                    presentationSimulationSelected = true,
                    presentationCaptureVisible = false
                )
            }
            return
        }
        mutableUiState.update { state ->
            val keepCookingSession = state.resumeAutoAfterDeviceSetup && state.session != null
            state.copy(
                currentScreen = AppScreen.S2_RECIPE_DETAIL,
                session = if (keepCookingSession) state.session?.copy(activeRequestId = null) else null,
                hasResumableSession = keepCookingSession,
                resumeAutoAfterDeviceSetup = false,
                currentCaptureOutcome = null,
                judgeError = null,
                nextInspectionInSeconds = null
            )
        }
    }

    fun openDevicePreparation() {
        if (cameraGateway is FakeWearableCameraGateway) {
            cameraGateway.setState(WearableCameraState.Registering)
        } else {
            (cameraGateway as? DatWearableCameraGateway)?.prepareSession()
        }
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S4_DEVICE,
                session = createFreshSession(phase = CookingPhase.PREPARING_DEVICE),
                resumeAutoAfterDeviceSetup = false
            )
        }
    }

    fun initializeDatCamera(androidPermissionsGranted: Boolean) {
        val datGateway = cameraGateway as? DatWearableCameraGateway ?: return
        if (!androidPermissionsGranted) {
            mutableUiState.update {
                it.copy(deviceHint = "Bluetooth 권한이 필요합니다. 연결 준비에서 다시 허용해주세요.")
            }
            return
        }
        datGateway.initialize()
        datGateway.prepareSession()
    }

    fun advanceDeviceSetup(activity: Activity) {
        val datGateway = cameraGateway as? DatWearableCameraGateway
        if (datGateway == null) {
            advanceFakeDeviceState()
            return
        }
        when (uiState.value.cameraState) {
            WearableCameraState.NotStarted -> datGateway.startRegistration(activity)
            WearableCameraState.Registering -> Unit
            WearableCameraState.PermissionRequired -> Unit
            else -> datGateway.prepareSession()
        }
    }

    fun onWearableCameraPermissionResult(granted: Boolean) {
        (cameraGateway as? DatWearableCameraGateway)
            ?.onWearableCameraPermissionResult(granted)
    }

    private fun advanceFakeDeviceState() {
        val fakeGateway = cameraGateway as? FakeWearableCameraGateway ?: return
        val next = when (uiState.value.cameraState) {
            WearableCameraState.NotStarted -> WearableCameraState.Registering
            WearableCameraState.Registering -> WearableCameraState.PermissionRequired
            WearableCameraState.PermissionRequired -> WearableCameraState.Searching
            WearableCameraState.Searching -> WearableCameraState.Connecting
            WearableCameraState.Connecting -> WearableCameraState.Ready
            WearableCameraState.Disconnected -> WearableCameraState.Searching
            is WearableCameraState.Error -> WearableCameraState.Searching
            else -> WearableCameraState.Ready
        }
        fakeGateway.setState(next)
        val phase = if (next == WearableCameraState.Ready) CookingPhase.READY else CookingPhase.PREPARING_DEVICE
        mutableUiState.update { state ->
            state.copy(
                session = (state.session ?: createFreshSession()).copy(phase = phase),
                deviceHint = when (next) {
                    WearableCameraState.Ready -> "안경 카메라 준비 완료. 평소에는 OFF이고 검사 순간에만 촬영합니다."
                    WearableCameraState.PermissionRequired -> "카메라 권한이 필요합니다."
                    WearableCameraState.Searching -> "사용 가능한 안경을 찾고 있습니다."
                    WearableCameraState.Connecting -> "세션을 연결하고 있습니다."
                    else -> "연결 준비 중입니다."
                }
            )
        }
    }

    fun simulateDeviceDisconnect() {
        val fakeGateway = cameraGateway as? FakeWearableCameraGateway ?: return
        fakeGateway.setState(WearableCameraState.Disconnected)
        mutableUiState.update {
            it.copy(
                deviceHint = "연결 끊김을 재현했습니다. 자동 검사는 멈추고 수동 진행으로 전환할 수 있습니다."
            )
        }
        transitionToManualMode("안경 연결이 끊겨 수동 모드로 전환합니다.")
    }

    fun simulateDeviceError() {
        val fakeGateway = cameraGateway as? FakeWearableCameraGateway ?: return
        fakeGateway.setState(WearableCameraState.Error("가짜 연결 실패"))
        mutableUiState.update {
            it.copy(deviceHint = "가짜 오류 상태입니다. 다시 연결하거나 안경 없이 시작할 수 있습니다.")
        }
    }

    fun startWithoutGlasses() {
        cancelInspectionWork()
        (cameraGateway as? FakeWearableCameraGateway)
            ?.setState(WearableCameraState.Disconnected)
        val recipe = uiState.value.selectedRecipe ?: return
        val session = createFreshSession(mode = SessionMode.MANUAL_ONLY, phase = CookingPhase.MANUAL_MODE, recipeId = recipe.id)
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S8_MANUAL,
                session = session,
                resumeAutoAfterDeviceSetup = false
            )
        }
        announceCurrent("안경 없이 시작합니다. 다 되면 다음이라고 말해주세요.")
        startStep(0, manualOnly = true)
    }

    fun startCooking() {
        if (uiState.value.cameraState != WearableCameraState.Ready) {
            startWithoutGlasses()
            return
        }
        val state = uiState.value
        if (state.resumeAutoAfterDeviceSetup && state.session != null) {
            val currentIndex = state.session.currentStepIndex
            mutableUiState.update { it.copy(resumeAutoAfterDeviceSetup = false) }
            startStep(currentIndex, manualOnly = false, keepCounts = true)
            return
        }
        val recipe = uiState.value.selectedRecipe ?: return
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = createFreshSession(recipeId = recipe.id, phase = CookingPhase.STEP_STARTING)
            )
        }
        startStep(0, manualOnly = false)
    }

    fun repeatCurrentStep() {
        val step = uiState.value.currentStep ?: return
        announceCurrent(step.voicePrompt)
    }

    fun disableAutoMode() {
        transitionToManualMode("자동 확인을 껐습니다. 화면 버튼이나 음성으로 진행할 수 있어요.")
    }

    fun onSpeechError(message: String) {
        mutableUiState.update { it.copy(isListening = false, speechError = message) }
    }

    fun triggerImmediateInspection() {
        val state = uiState.value
        var session = state.session ?: return
        val step = state.currentStep ?: return
        if (session.mode == SessionMode.MANUAL_ONLY) {
            if (state.cameraState != WearableCameraState.Ready) {
                announceCurrent("안경 카메라가 준비되지 않아 바로 촬영할 수 없습니다. 연결을 확인해주세요.")
                return
            }
            session = session.copy(
                mode = SessionMode.AUTO,
                phase = CookingPhase.WAITING_FOR_CHECK,
                cannotTellStreak = 0
            )
            mutableUiState.update {
                it.copy(currentScreen = AppScreen.S5_COOKING, session = session)
            }
        }
        when (session.phase) {
            CookingPhase.STEP_STARTING -> {
                pendingManualInspectionStepOrder = step.order
                announceCurrent("기준 촬영이 끝나는 대로 확인하겠습니다.")
                return
            }
            CookingPhase.PROMPTING_USER,
            CookingPhase.CAPTURING -> {
                announceCurrent("이미 확인 촬영을 진행하고 있습니다.")
                return
            }
            CookingPhase.JUDGING -> {
                announceCurrent("촬영을 마쳤습니다. 결과를 확인하고 있습니다.")
                return
            }
            CookingPhase.WAITING_FOR_CHECK,
            CookingPhase.NEEDS_VIEW,
            CookingPhase.NETWORK_RETRY -> Unit
            else -> {
                announceCurrent("현재 단계에서는 카메라 확인을 시작할 수 없습니다.")
                return
            }
        }
        cancelInspectionWork()
        inspectionExecutionJob = viewModelScope.launch {
            promptAndInspect(isManualRequest = true)
        }
    }

    fun setGalleryBaselineImage(uriValue: String) {
        val state = uiState.value
        val session = state.session ?: return
        val step = state.currentStep ?: return
        if (session.mode != SessionMode.MANUAL_ONLY || !step.shouldSendStartImage()) return
        viewModelScope.launch {
            val errorMessage = withContext(Dispatchers.IO) {
                runCatching {
                    ImageNormalizer(getApplication()).normalize(
                        uriValue,
                        JudgmentImagePolicy.MANUAL_MODE
                    )
                }
                    .exceptionOrNull()
                    ?.message
            }
            val current = uiState.value
            val currentSession = current.session
            if (currentSession?.id != session.id || current.currentStep?.order != step.order) return@launch
            if (errorMessage != null) {
                mutableUiState.update { it.copy(judgeError = "시작 사진을 불러올 수 없습니다: $errorMessage") }
                announceCurrent("비교 시작 사진을 불러오지 못했습니다. 다른 사진을 선택해주세요.")
                return@launch
            }
            mutableUiState.update {
                val active = it.session ?: return@update it
                it.copy(
                    judgeError = null,
                    session = active.copy(
                        baselineUriByStep = active.baselineUriByStep + (step.order to uriValue),
                        currentVerdict = null
                    )
                )
            }
            announceCurrent("비교 시작 사진을 준비했습니다. 이제 현재 사진을 선택해 판정해주세요.")
        }
    }

    fun judgeGalleryImage(uriValue: String) {
        val state = uiState.value
        val session = state.session ?: return
        val recipe = state.selectedRecipe ?: return
        val step = state.currentStep ?: return
        if (session.mode != SessionMode.MANUAL_ONLY) return
        val validationError = galleryJudgmentValidationError(
            step = step,
            hasBaselineImage = session.baselineUriByStep[step.order] != null
        )
        if (validationError != null) {
            mutableUiState.update { it.copy(judgeError = validationError) }
            announceCurrent(validationError)
            return
        }

        cancelInspectionWork()
        val requestId = UUID.randomUUID().toString()
        mutableUiState.update {
            val active = it.session ?: return@update it
            it.copy(
                currentScreen = AppScreen.S8_MANUAL,
                judgingInFlight = true,
                judgeError = null,
                session = active.copy(
                    phase = CookingPhase.JUDGING,
                    activeRequestId = requestId,
                    currentVerdict = null,
                    lastCaptureUriByStep = active.lastCaptureUriByStep + (step.order to uriValue)
                )
            )
        }
        announceCurrent("선택한 갤러리 사진을 판정 서버로 보내 확인하겠습니다.")
        viewModelScope.launch {
            val outcome = networkJudgmentGateway.judge(
                JudgmentRequest(
                    requestId = requestId,
                    cookingSessionId = session.id,
                    recipeId = recipe.id,
                    stepOrder = step.order,
                    instruction = step.instruction,
                    checkType = step.checkType,
                    checkCondition = step.checkCondition,
                    needsStartImage = step.shouldSendStartImage(),
                    elapsedSeconds = ((System.currentTimeMillis() - session.currentStepStartedAtMs) / 1_000L)
                        .toInt()
                        .coerceAtLeast(0),
                    baselineImageUri = session.baselineUriByStep[step.order],
                    currentImageUri = uriValue,
                    imagePolicy = JudgmentImagePolicy.MANUAL_MODE
                )
            )
            handleJudgmentOutcome(outcome)
        }
    }

    fun retryLastManualJudgment() {
        val state = uiState.value
        val session = state.session ?: return
        val step = state.currentStep ?: return
        if (session.mode != SessionMode.MANUAL_ONLY || state.judgingInFlight) return
        val currentImageUri = session.lastCaptureUriByStep[step.order]
        if (currentImageUri == null) {
            val message = "다시 판정할 사진이 없습니다. 갤러리에서 사진을 선택해주세요."
            mutableUiState.update { it.copy(judgeError = message) }
            announceCurrent(message)
            return
        }
        judgeGalleryImage(currentImageUri)
    }

    /**
     * 디버그 전용 — 병렬 타이머를 지금 끝낸 것으로 만든다.
     *
     * 8분을 실제로 기다리지 않고 만료 안내와 대기 해제를 확인하기 위한 것이다.
     * 만료 시각만 당기고, 안내·자동 진행은 평소와 같은 티커 경로로 흐르게 둔다.
     */
    fun debugFinishParallelTimer() {
        val session = uiState.value.session ?: return
        if (session.parallelTimerEndsAtMs == null || session.parallelTimerFired) return
        mutableUiState.update {
            it.copy(session = session.copy(parallelTimerEndsAtMs = System.currentTimeMillis()))
        }
    }

    fun continueToNextStep() {
        continueToNextStep(bypassDebounce = false, allowDuringStepStarting = false)
    }

    fun continueAutoButtonToNextStep() {
        val session = uiState.value.session ?: return
        if (session.mode == SessionMode.MANUAL_ONLY) {
            continueManualButtonToNextStep()
            return
        }
        continueToNextStep(bypassDebounce = true, allowDuringStepStarting = true)
    }

    fun continueManualButtonToNextStep() {
        val session = uiState.value.session ?: return
        if (session.mode != SessionMode.MANUAL_ONLY) {
            continueToNextStep()
            return
        }
        continueToNextStep(bypassDebounce = true, allowDuringStepStarting = false)
    }

    private fun continueToNextStep(
        bypassDebounce: Boolean,
        allowDuringStepStarting: Boolean
    ) {
        val state = uiState.value
        val session = state.session ?: return
        if (blocksNextStepDuringStart(session.phase, allowDuringStepStarting)) {
            announceCurrent(
                "현재 ${session.currentStepIndex + 1}단계 기준 촬영을 준비하고 있습니다. " +
                    "촬영이 끝날 때까지 기다려주세요."
            )
            return
        }
        val now = System.currentTimeMillis()
        if (!bypassDebounce && now - lastNextCommandAtMs < NEXT_COMMAND_DEBOUNCE_MS) {
            announceCurrent("다음 단계 요청을 이미 처리하고 있습니다.")
            return
        }
        if (!bypassDebounce) lastNextCommandAtMs = now
        val wasAutomaticallyCompleted =
            session.phase == CookingPhase.STEP_COMPLETED && session.currentVerdict == JudgmentVerdict.DONE
        advanceToNextStep(manual = !wasAutomaticallyCompleted)
    }

    private fun advanceToNextStep(manual: Boolean) {
        val state = uiState.value
        val recipe = state.selectedRecipe ?: return
        val originalSession = state.session ?: return
        val now = System.currentTimeMillis()
        val session = if (manual) {
            originalSession.copy(
                completedStepOrders = originalSession.completedStepOrders + (originalSession.currentStepIndex + 1),
                stepCompletedAtMsByOrder = originalSession.stepCompletedAtMsByOrder + ((originalSession.currentStepIndex + 1) to now),
                logs = originalSession.logs + SessionLogEntry(
                    timestampMs = now,
                    stepOrder = originalSession.currentStepIndex + 1,
                    message = "사용자 직접 완료",
                    eventType = "MANUAL_OVERRIDE",
                    manualOverride = true,
                    overrideType = "MANUAL_NEXT"
                )
            )
        } else originalSession
        val nextIndex = session.currentStepIndex + 1
        if (nextIndex >= recipe.steps.size) {
            cancelInspectionWork()
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S9_SUMMARY,
                    session = session.copy(
                        phase = CookingPhase.SESSION_COMPLETED,
                        completedAtMs = System.currentTimeMillis(),
                        completedStepOrders = session.completedStepOrders + (session.currentStepIndex + 1)
                    )
                )
            }
            announceCurrent("${recipe.title} 조리가 끝났습니다.")
            return
        }

        // 병렬 타이머는 단계를 **마칠 때** 켠다. "면을 넣으세요"를 듣는 시점에는 아직 면이 물에
        // 들어가지 않았다. 사용자가 "다음"이라고 한 지금이 면이 들어간 순간이다.
        val leavingTimer = recipe.steps.getOrNull(session.currentStepIndex)?.parallelTimer
        val timed = if (leavingTimer == null) session else session.copy(
            parallelTimerEndsAtMs = now + leavingTimer.durationSeconds * 1_000L,
            parallelTimerLabel = leavingTimer.label,
            parallelTimerMessage = leavingTimer.doneAnnouncement,
            parallelTimerFired = false
        )

        // 삶아둔 면을 넣는 단계는 면이 익기 전에 시작할 수 없다. 자동 진행만 붙잡고,
        // 사용자가 "다음"이라고 하면 그대로 보낸다 (F5-1).
        if (!manual && shouldWaitForParallelTimer(recipe.steps[nextIndex], timed)) {
            val remaining = timed.parallelTimerRemainingSeconds() ?: 0
            mutableUiState.update { it.copy(session = timed.copy(advanceBlockedByTimer = true)) }
            announceCurrent(
                "${timed.parallelTimerLabel ?: "타이머"}가 끝나면 다음 단계로 갈게요. " +
                    "${formatRemaining(remaining)} 남았어요."
            )
            return
        }

        mutableUiState.update { it.copy(session = timed.copy(advanceBlockedByTimer = false)) }
        startStep(nextIndex, manualOnly = timed.mode == SessionMode.MANUAL_ONLY, manualIncrement = manual)
    }

    /** 아직 걸린 적 없는 타이머는 기다리지 않는다 — 영영 오지 않기 때문이다. */
    private fun shouldWaitForParallelTimer(nextStep: RecipeStep, session: CookingSession): Boolean =
        nextStep.waitsForParallelTimer &&
            session.parallelTimerEndsAtMs != null &&
            !session.parallelTimerFired

    fun moveToPreviousStep() {
        if (uiState.value.isPresentationSimulation) {
            moveToPreviousPresentationStep()
            return
        }
        val session = uiState.value.session ?: return
        if (session.currentStepIndex == 0) {
            cancelInspectionWork()
            elapsedTickerJob?.cancel()
            elapsedTickerJob = null
            pendingManualInspectionStepOrder = null
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S4_DEVICE,
                    session = session.copy(activeRequestId = null),
                    hasResumableSession = true,
                    resumeAutoAfterDeviceSetup = true,
                    currentCaptureOutcome = null,
                    judgeError = null,
                    nextInspectionInSeconds = null
                )
            }
            return
        }
        val updated = session.copy(
            currentStepIndex = session.currentStepIndex - 1,
            undoDoneCount = session.undoDoneCount + if (session.phase == CookingPhase.STEP_COMPLETED) 1 else 0,
            consecutiveDoneCount = 0,
            phase = if (session.mode == SessionMode.MANUAL_ONLY) CookingPhase.MANUAL_MODE else CookingPhase.STEP_STARTING,
            logs = session.logs + SessionLogEntry(
                timestampMs = System.currentTimeMillis(),
                stepOrder = session.currentStepIndex + 1,
                message = "이전 단계 복귀",
                eventType = "MANUAL_OVERRIDE",
                manualOverride = true,
                overrideType = "UNDO_DONE"
            )
        )
        mutableUiState.update { it.copy(session = updated, currentScreen = if (session.mode == SessionMode.MANUAL_ONLY) AppScreen.S8_MANUAL else AppScreen.S5_COOKING) }
        startStep(updated.currentStepIndex, manualOnly = updated.mode == SessionMode.MANUAL_ONLY, keepCounts = true)
    }

    private fun moveToPreviousPresentationStep() {
        val state = uiState.value
        val recipe = state.selectedRecipe ?: return
        val session = state.session ?: return
        if (session.currentStepIndex == 0) {
            cancelInspectionWork()
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S4_DEVICE,
                    presentationCaptureVisible = false
                )
            }
            return
        }
        val previousIndex = session.currentStepIndex - 1
        val previousStep = recipe.steps[previousIndex]
        val now = System.currentTimeMillis()
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = session.copy(
                    phase = CookingPhase.WAITING_FOR_CHECK,
                    currentStepIndex = previousIndex,
                    currentStepStartedAtMs = now,
                    stepStartedAtMsByOrder = session.stepStartedAtMsByOrder + (previousStep.order to now),
                    currentVerdict = null,
                    activeRequestId = null
                ),
                currentCaptureOutcome = null,
                nextInspectionInSeconds = null,
                judgingInFlight = false,
                judgeError = null,
                presentationCaptureVisible = false
            )
        }
        announceCurrent(previousStep.voicePrompt)
        schedulePresentationCaptureReveal()
    }

    fun keepCurrentStepAndReschedule() {
        val step = uiState.value.currentStep ?: return
        val session = uiState.value.session ?: return
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = session.copy(phase = CookingPhase.WAITING_FOR_CHECK)
            )
        }
        scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
    }

    fun resumeAutoMode() {
        val state = uiState.value
        val session = state.session ?: return
        if (state.cameraState != WearableCameraState.Ready) {
            cancelInspectionWork()
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S4_DEVICE,
                    resumeAutoAfterDeviceSetup = true
                )
            }
            announceCurrent("안경 연결을 다시 준비한 뒤 현재 단계부터 자동 확인을 재개합니다.")
            return
        }
        val currentIndex = session.currentStepIndex
        if (!uiState.value.useMockJudgment) {
            viewModelScope.launch {
                mutableUiState.update { it.copy(serverReady = null, serverStatusMessage = "판정 서버 상태 확인 중") }
                val health = networkJudgmentGateway.checkHealth()
                mutableUiState.update { it.copy(serverReady = health.ready, serverStatusMessage = health.message) }
                if (health.ready) resumeAutoModeAfterCheck(session, currentIndex)
            }
            return
        }
        resumeAutoModeAfterCheck(session, currentIndex)
    }

    private fun resumeAutoModeAfterCheck(session: CookingSession, currentIndex: Int) {
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                resumeAutoAfterDeviceSetup = false,
                session = session.copy(
                    mode = SessionMode.AUTO,
                    phase = CookingPhase.STEP_STARTING,
                    cannotTellStreak = 0
                )
            )
        }
        startStep(currentIndex, manualOnly = false, keepCounts = true)
    }

    fun onAudioPermissionResult(granted: Boolean) {
        mutableUiState.update { it.copy(audioPermissionGranted = granted) }
    }

    fun setListening(active: Boolean) {
        mutableUiState.update { it.copy(isListening = active) }
    }

    fun handleVoiceTranscript(text: String) {
        setListening(false)
        mutableUiState.update { it.copy(speechError = null, lastVoiceTranscript = text.trim()) }
        val screen = uiState.value.currentScreen
        val command = parseVoiceCommand(text)
        if (command == null || !isVoiceCommandAllowed(command, screen)) {
            onSpeechError("현재 화면에서 사용할 수 있는 음성 명령이 아닙니다.")
            return
        }
        if (uiState.value.isPresentationSimulation) {
            when (command) {
                VoiceCommand.NEXT -> continuePresentationSimulation()
                VoiceCommand.PREVIOUS -> moveToPreviousPresentationStep()
                VoiceCommand.REPEAT -> repeatCurrentStep()
                VoiceCommand.INGREDIENTS -> announceIngredients()
                VoiceCommand.CURRENT_STEP -> announceCurrentStep()
                else -> announceCurrent("발표용 모드에서는 다음, 다시, 이전 명령을 사용해주세요.")
            }
            return
        }
        when (command) {
            VoiceCommand.INGREDIENTS -> announceIngredients()
            VoiceCommand.VERIFY_INGREDIENT, VoiceCommand.CHECK_NOW -> triggerImmediateInspection()
            VoiceCommand.CURRENT_STEP -> announceCurrentStep()
            VoiceCommand.RESUME_AUTO -> resumeAutoMode()
            VoiceCommand.NEXT -> continueToNextStep()
            VoiceCommand.NOT_YET -> keepCurrentStepAndReschedule()
            VoiceCommand.REPEAT -> repeatCurrentStep()
            VoiceCommand.PREVIOUS -> moveToPreviousStep()
        }
    }

    private fun announceIngredients() {
        val recipe = uiState.value.selectedRecipe ?: return
        val message = recipe.ingredients.joinToString(", ") { "${it.name} ${it.amount}" }
        announceCurrent(limitAnnouncement("재료는 $message 입니다."))
    }

    private fun announceCurrentStep() {
        val step = uiState.value.currentStep ?: return
        val total = uiState.value.selectedRecipe?.steps?.size ?: return
        announceCurrent("현재 ${step.order}단계, 전체 ${total}단계입니다. ${step.instruction}")
    }

    fun setMockJudgmentEnabled(enabled: Boolean) {
        persistence.saveUseMockJudgment(enabled)
        mutableUiState.update {
            it.copy(
                useMockJudgment = enabled,
                serverReady = if (enabled) null else it.serverReady,
                serverStatusMessage = if (enabled) null else "실제 서버를 사용하기 전에 연결 상태를 확인합니다."
            )
        }
        if (!enabled) checkServerHealth()
    }

    fun checkServerHealth() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(serverReady = null, serverStatusMessage = "판정 서버 상태 확인 중") }
            val health = networkJudgmentGateway.checkHealth()
            mutableUiState.update { it.copy(serverReady = health.ready, serverStatusMessage = health.message) }
        }
    }

    fun setServerBaseUrl(value: String) {
        mutableUiState.update { it.copy(serverBaseUrl = value, serverReady = null, serverStatusMessage = null) }
    }

    fun applyServerBaseUrl() {
        val value = uiState.value.serverBaseUrl
        if (!networkJudgmentGateway.updateBaseUrl(value)) {
            mutableUiState.update { it.copy(serverReady = false, serverStatusMessage = "http 또는 https 서버 주소를 입력해 주세요.") }
            return
        }
        persistence.saveServerBaseUrl(value.trim().trimEnd('/'))
        checkServerHealth()
    }

    fun setMockVerdict(verdict: JudgmentVerdict) {
        mutableUiState.update { it.copy(selectedMockVerdict = verdict) }
    }

    fun setFakeCaptureBehaviorSuccess() {
        (cameraGateway as? FakeWearableCameraGateway)
            ?.setBehavior(FakeCaptureBehavior.Success())
    }

    fun setFakeCaptureBehaviorBusy() {
        (cameraGateway as? FakeWearableCameraGateway)?.setBehavior(
            FakeCaptureBehavior.Failure(
                kind = CaptureFailureKind.BUSY,
                retryable = true,
                userMessage = "가짜 BUSY 상태입니다.",
                debugMessage = "Configured BUSY failure"
            )
        )
    }

    fun setFakeCaptureBehaviorDisconnect() {
        (cameraGateway as? FakeWearableCameraGateway)
            ?.setBehavior(FakeCaptureBehavior.Disconnect)
    }

    fun setFakeCaptureBehaviorFailure() {
        (cameraGateway as? FakeWearableCameraGateway)?.setBehavior(
            FakeCaptureBehavior.Failure(
                kind = CaptureFailureKind.UNKNOWN,
                retryable = true,
                userMessage = "가짜 촬영 실패입니다.",
                debugMessage = "Configured generic failure"
            )
        )
    }

    override fun onCleared() {
        cancelInspectionWork()
        runBlocking {
            cameraGateway.release()
            fakeJudgmentGateway.release()
            networkJudgmentGateway.release()
        }
        super.onCleared()
    }

    private fun startStep(
        stepIndex: Int,
        manualOnly: Boolean,
        manualIncrement: Boolean = false,
        keepCounts: Boolean = false
    ) {
        cancelInspectionWork()
        pendingManualInspectionStepOrder = null
        val state = uiState.value
        val recipe = state.selectedRecipe ?: return
        val previous = state.session ?: createFreshSession(recipeId = recipe.id)
        val step = recipe.steps[stepIndex]
        val now = System.currentTimeMillis()

        val session = previous.copy(
            recipeId = recipe.id,
            phase = CookingPhase.STEP_STARTING,
            mode = if (manualOnly) SessionMode.MANUAL_ONLY else previous.mode,
            currentStepIndex = stepIndex,
            manualNextCount = if (manualIncrement) previous.manualNextCount + 1 else previous.manualNextCount,
            cannotTellStreak = if (keepCounts) previous.cannotTellStreak else 0,
            consecutiveDoneCount = 0,
            networkFailureCount = 0,
            currentVerdict = null,
            activeRequestId = null,
            currentStepStartedAtMs = now,
            stepStartedAtMsByOrder = previous.stepStartedAtMsByOrder + (step.order to now),
            logs = previous.logs + SessionLogEntry(
                timestampMs = System.currentTimeMillis(),
                stepOrder = step.order,
                message = "단계 시작: ${step.instruction}"
            )
        )
        mutableUiState.update {
            it.copy(
                currentScreen = if (manualOnly) AppScreen.S8_MANUAL else AppScreen.S5_COOKING,
                session = session,
                currentCaptureOutcome = null,
                judgeError = null,
                nextInspectionInSeconds = null
            )
        }
        announceCurrent(step.voicePrompt)
        startElapsedTicker()
        if (manualOnly || !step.isAutoCheck) {
            mutableUiState.update { ui -> ui.copy(session = session.copy(phase = if (manualOnly) CookingPhase.MANUAL_MODE else CookingPhase.WAITING_FOR_CHECK)) }
            return
        }
        if (hasReusableStartImage(step, session)) {
            mutableUiState.update { ui ->
                ui.copy(session = session.copy(phase = CookingPhase.WAITING_FOR_CHECK))
            }
            scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
            return
        }
        launchBaselineCapture(
            step = step,
            cookingSessionId = session.id,
            manuallyAdvancedIntoStep = manualIncrement
        )
    }

    private fun launchBaselineCapture(
        step: RecipeStep,
        cookingSessionId: String,
        manuallyAdvancedIntoStep: Boolean = false,
        forceImmediate: Boolean = false
    ) {
        baselineCaptureJob?.cancel()

        val capturePlan = baselineCapturePlan(
            step = step,
            manuallyAdvancedIntoStep = manuallyAdvancedIntoStep,
            forceImmediate = forceImmediate
        )

        // 기준 사진과 자동 검사는 **서로 다른 시계**로 돈다. 직렬로 묶으면 첫 검사가
        // 15 + 30 = 45초로 밀린다. 검사 카운트다운은 여기서 바로 시작하고,
        // 기준 사진은 아래 코루틴이 제 시간에 따로 찍는다. 단, 앞 단계를 수동 완료해서
        // 재사용할 DONE 사진이 없는 baselineOnStepStart 단계는 5초 뒤 새 기준을 촬영한
        // 시점부터 30초를 센다.
        if (capturePlan.inspectionRunsWhileWaitingForBaseline) {
            mutableUiState.update { ui ->
                val session = ui.session ?: return@update ui
                ui.copy(session = session.copy(phase = CookingPhase.WAITING_FOR_CHECK))
            }
            scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
        }

        baselineCaptureJob = viewModelScope.launch {
            if (capturePlan.delaySeconds > 0) delay(capturePlan.delaySeconds * 1_000L)
            val captured = captureBaseline(step)
            baselineCaptureJob = null
            val current = uiState.value
            val currentSession = current.session
            if (
                currentSession?.id != cookingSessionId ||
                current.currentStep?.order != step.order
            ) return@launch
            // 지연 경로는 이미 WAITING_FOR_CHECK 로 넘어가 있다. 그 외에는 종전대로
            // 단계 시작 상태에서만 이어간다. 사용자 요청으로 끼어든 촬영은 예외다 — 그때는
            // 이미 WAITING_FOR_CHECK 이고, 여기서 빠져나가면 대기 중인 수동 검사가 영영 안 돈다.
            if (
                !capturePlan.inspectionRunsWhileWaitingForBaseline &&
                !forceImmediate &&
                currentSession.phase != CookingPhase.STEP_STARTING
            ) return@launch

            if (!captured) {
                val failure = current.currentCaptureOutcome as? CaptureOutcome.Failure
                transitionToManualMode(
                    "기준 사진 촬영을 완료하지 못했습니다. " +
                        "${failure?.userMessage ?: "안경 카메라를 확인해주세요."} " +
                        "연결을 확인한 뒤 자동 확인 다시를 말해주세요."
                )
                return@launch
            }

            val runPendingManualInspection = pendingManualInspectionStepOrder == step.order
            if (runPendingManualInspection) {
                pendingManualInspectionStepOrder = null
            }
            mutableUiState.update { ui ->
                val session = ui.session ?: currentSession
                ui.copy(
                    consecutiveCameraTimeouts = 0,
                    // 지연 경로에서는 이미 검사가 돌고 있을 수 있어 단계를 되돌리지 않는다.
                    session = if (capturePlan.inspectionRunsWhileWaitingForBaseline) {
                        session
                    } else {
                        session.copy(phase = CookingPhase.WAITING_FOR_CHECK)
                    }
                )
            }
            if (runPendingManualInspection) {
                triggerImmediateInspection()
            } else if (!capturePlan.inspectionRunsWhileWaitingForBaseline) {
                scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
            }
        }
    }

    private suspend fun captureBaseline(step: RecipeStep): Boolean {
        val state = uiState.value
        val session = state.session ?: return false
        val request = CaptureRequest(
            requestId = UUID.randomUUID().toString(),
            cookingSessionId = session.id,
            stepOrder = step.order,
            purpose = CapturePurpose.BASELINE,
            burstDurationMs = step.inspectionPolicy?.burstSeconds?.times(1_000L) ?: 3_000L,
            streamTimeoutMs = CAMERA_STREAM_READY_TIMEOUT_MS,
            captureTimeoutMs = CAMERA_PHOTO_CAPTURE_TIMEOUT_MS
        )
        val outcome = captureRequiredWithRetry(request, "기준 사진 촬영")
        when (outcome) {
            is CaptureOutcome.Success -> {
                mutableUiState.update {
                    val currentSession = it.session ?: return@update it
                    it.copy(
                        currentCaptureOutcome = outcome,
                        consecutiveCameraTimeouts = 0,
                        session = currentSession.copy(
                            baselineUriByStep = currentSession.baselineUriByStep + (step.order to outcome.artifact.imageUri),
                            lastCaptureUriByStep = currentSession.lastCaptureUriByStep + (step.order to outcome.artifact.imageUri)
                        )
                    )
                }
                return true
            }
            is CaptureOutcome.Failure -> {
                val timeoutCount = if (outcome.kind == CaptureFailureKind.STREAM_TIMEOUT) {
                    uiState.value.consecutiveCameraTimeouts + 1
                } else {
                    0
                }
                mutableUiState.update {
                    it.copy(
                        currentCaptureOutcome = outcome,
                        lastCaptureFailureKind = outcome.kind,
                        consecutiveCameraTimeouts = timeoutCount
                    )
                }
                return false
            }
        }
    }

    private suspend fun captureRequiredWithRetry(
        request: CaptureRequest,
        label: String
    ): CaptureOutcome {
        var attempt = 1
        while (true) {
            val outcome = cameraGateway.capture(request)
            if (outcome is CaptureOutcome.Success) {
                return outcome
            }
            val failure = outcome as CaptureOutcome.Failure
            if (!failure.retryable || attempt >= REQUIRED_CAPTURE_MAX_ATTEMPTS) return failure
            announceCurrent(
                "$label ${attempt}회차에 실패했습니다. ${failure.userMessage} " +
                    "${REQUIRED_CAPTURE_RETRY_DELAY_MS / 1_000L}초 후 다시 촬영하겠습니다."
            )
            delay(REQUIRED_CAPTURE_RETRY_DELAY_MS)
            val current = uiState.value
            if (
                current.session?.id != request.cookingSessionId ||
                current.currentStep?.order != request.stepOrder
            ) {
                return CaptureOutcome.Failure(
                    requestId = request.requestId,
                    kind = CaptureFailureKind.CANCELLED,
                    retryable = false,
                    userMessage = "단계가 변경되어 재촬영을 취소했습니다."
                )
            }
            attempt += 1
        }
    }

    private fun scheduleInspection(delaySeconds: Int) {
        cancelInspectionWork()
        inspectionCountdownJob = viewModelScope.launch {
            for (remaining in delaySeconds downTo 1) {
                mutableUiState.update { it.copy(nextInspectionInSeconds = remaining) }
                delay(1_000L)
            }
            mutableUiState.update { it.copy(nextInspectionInSeconds = 0) }
            inspectionExecutionJob = launch {
                promptAndInspect(isManualRequest = false)
            }
        }
    }

    private suspend fun promptAndInspect(isManualRequest: Boolean) {
        val state = uiState.value
        val session = state.session ?: return
        val step = state.currentStep ?: return
        if (session.mode == SessionMode.MANUAL_ONLY) return

        // 두 시계가 따로 도는 대가로, 검사가 기준 사진보다 먼저 올 수 있다.
        // 상대 조건을 1장으로 물으면 서버는 200 을 돌려주고 **조용히 틀린다.** 기다린다.
        if (step.shouldSendStartImage() && session.baselineUriByStep[step.order] == null) {
            if (isManualRequest) {
                pendingManualInspectionStepOrder = step.order
                // 여기까지 온 수동 요청은 방금 `cancelInspectionWork()`로 기준 촬영 job까지 끊고 왔다.
                // 다시 걸어주지 않으면 위 pending 이 기다리는 촬영이 영영 오지 않는다.
                if (baselineCaptureJob == null) {
                    launchBaselineCapture(step, session.id, forceImmediate = true)
                }
                announceCurrent("기준 사진을 먼저 찍고 확인할게요.")
            } else {
                scheduleInspection(BASELINE_WAIT_RETRY_SECONDS)
            }
            return
        }

        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = session.copy(phase = CookingPhase.PROMPTING_USER),
                nextInspectionInSeconds = null
            )
        }
        announceCurrent(
            if (isManualRequest) {
                "확인하겠습니다. 팬을 봐주세요."
            } else {
                "자동으로 확인하겠습니다. 팬을 봐주세요."
            }
        )
        delay(1_000L)

        val request = CaptureRequest(
            requestId = UUID.randomUUID().toString(),
            cookingSessionId = session.id,
            stepOrder = step.order,
            purpose = if (isManualRequest) CapturePurpose.MANUAL_CHECK else CapturePurpose.INSPECTION,
            burstDurationMs = step.inspectionPolicy?.burstSeconds?.times(1_000L) ?: 3_000L,
            streamTimeoutMs = CAMERA_STREAM_READY_TIMEOUT_MS,
            captureTimeoutMs = CAMERA_PHOTO_CAPTURE_TIMEOUT_MS
        )
        mutableUiState.update {
            val currentSession = it.session ?: return@update it
            it.copy(
                session = currentSession.copy(
                    phase = CookingPhase.CAPTURING,
                    activeRequestId = request.requestId
                )
            )
        }
        val outcome = if (isManualRequest) {
            captureRequiredWithRetry(request, "요청한 확인 촬영")
        } else {
            cameraGateway.capture(request)
        }
        handleCaptureOutcome(request, outcome)
    }

    private suspend fun handleCaptureOutcome(request: CaptureRequest, outcome: CaptureOutcome) {
        val current = uiState.value
        if (!isCurrentCaptureRequest(current.session, current.currentStep?.order, request)) return
        when (outcome) {
            is CaptureOutcome.Failure -> {
                val timeoutCount = if (outcome.kind == CaptureFailureKind.STREAM_TIMEOUT) {
                    uiState.value.consecutiveCameraTimeouts + 1
                } else {
                    0
                }
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    it.copy(
                        currentCaptureOutcome = outcome,
                        lastCaptureFailureKind = outcome.kind,
                        consecutiveCameraTimeouts = timeoutCount,
                        session = session.copy(
                            phase = if (session.mode == SessionMode.MANUAL_ONLY) CookingPhase.MANUAL_MODE else CookingPhase.WAITING_FOR_CHECK,
                            activeRequestId = null
                        )
                    )
                }
                if (request.purpose == CapturePurpose.MANUAL_CHECK) {
                    val attempts = if (outcome.retryable) {
                        "최대 ${REQUIRED_CAPTURE_MAX_ATTEMPTS}회 재시도했지만"
                    } else {
                        "시도했지만"
                    }
                    transitionToManualMode(
                        "요청한 확인 촬영을 $attempts 완료하지 못했습니다. " +
                            "${outcome.userMessage} 안경 연결을 확인해주세요."
                    )
                    return
                }
                when (outcome.kind) {
                    CaptureFailureKind.NOT_REGISTERED,
                    CaptureFailureKind.PERMISSION_DENIED,
                    CaptureFailureKind.DEVICE_NOT_FOUND,
                    CaptureFailureKind.DEVICE_DISCONNECTED,
                    CaptureFailureKind.SESSION_START_FAILED,
                    CaptureFailureKind.STREAM_START_FAILED,
                    CaptureFailureKind.CAPTURE_TIMEOUT,
                    CaptureFailureKind.STREAM_STOP_FAILED ->
                        transitionToManualMode("안경 카메라 연결을 다시 준비해야 해 수동 모드로 전환합니다.")
                    CaptureFailureKind.STREAM_TIMEOUT -> {
                        if (timeoutCount >= MAX_CONSECUTIVE_CAMERA_TIMEOUTS) {
                            transitionToManualMode("카메라 스트림 준비가 두 번 연속 실패해 수동 모드로 전환합니다.")
                        } else {
                            val step = uiState.value.currentStep ?: return
                            scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
                        }
                    }
                    CaptureFailureKind.BUSY,
                    CaptureFailureKind.PHOTO_CAPTURE_FAILED,
                    CaptureFailureKind.FILE_SAVE_FAILED,
                    CaptureFailureKind.NOT_READY,
                    CaptureFailureKind.UNKNOWN -> {
                        val step = uiState.value.currentStep ?: return
                        scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
                    }
                    CaptureFailureKind.INVALID_REQUEST,
                    CaptureFailureKind.CANCELLED -> Unit
                }
            }

            is CaptureOutcome.Success -> {
                val afterCapture = uiState.value.session ?: return
                mutableUiState.update {
                    it.copy(
                        currentCaptureOutcome = outcome,
                        consecutiveCameraTimeouts = 0,
                        session = afterCapture.copy(
                            phase = CookingPhase.JUDGING,
                            cameraActiveMs = afterCapture.cameraActiveMs + outcome.artifact.totalCaptureLatencyMs,
                            lastCaptureUriByStep = afterCapture.lastCaptureUriByStep + (request.stepOrder to outcome.artifact.imageUri)
                        ),
                        judgingInFlight = true,
                        judgeError = null
                    )
                }
                val state = uiState.value
                val recipe = state.selectedRecipe ?: return
                val step = state.currentStep ?: return
                if (state.useMockJudgment) {
                    fakeJudgmentGateway.setBehavior(
                        FakeJudgmentBehavior.Success(
                            verdict = state.selectedMockVerdict,
                            reasonCode = when (state.selectedMockVerdict) {
                                JudgmentVerdict.DONE -> ReasonCode.VISIBLE_CHANGE
                                JudgmentVerdict.NOT_DONE -> ReasonCode.NO_CHANGE
                                JudgmentVerdict.CANNOT_TELL -> ReasonCode.TARGET_NOT_VISIBLE
                            }
                        )
                    )
                }
                val judgmentGateway = if (state.useMockJudgment) fakeJudgmentGateway else networkJudgmentGateway
                val judgmentOutcome = judgmentGateway.judge(
                    JudgmentRequest(
                        requestId = request.requestId,
                        cookingSessionId = state.session?.id.orEmpty(),
                        recipeId = recipe.id,
                        stepOrder = step.order,
                        instruction = step.instruction,
                        checkType = step.checkType,
                        checkCondition = step.checkCondition,
                        needsStartImage = step.shouldSendStartImage(),
                        elapsedSeconds = state.session?.let {
                            ((System.currentTimeMillis() - it.currentStepStartedAtMs) / 1_000L).toInt().coerceAtLeast(0)
                        } ?: 0,
                        baselineImageUri = state.session?.baselineUriByStep?.get(step.order),
                        currentImageUri = outcome.artifact.imageUri,
                        imagePolicy = if (state.session?.mode == SessionMode.MANUAL_ONLY) {
                            JudgmentImagePolicy.MANUAL_MODE
                        } else {
                            JudgmentImagePolicy.AUTOMATIC_CAMERA
                        }
                    )
                )
                handleJudgmentOutcome(judgmentOutcome)
            }
        }
    }

    private fun handleCameraFailure(outcome: CaptureOutcome.Failure) {
        when (outcome.kind) {
            CaptureFailureKind.NOT_REGISTERED,
            CaptureFailureKind.PERMISSION_DENIED,
            CaptureFailureKind.DEVICE_NOT_FOUND,
            CaptureFailureKind.DEVICE_DISCONNECTED,
            CaptureFailureKind.SESSION_START_FAILED,
            CaptureFailureKind.STREAM_START_FAILED,
            CaptureFailureKind.STREAM_TIMEOUT,
            CaptureFailureKind.CAPTURE_TIMEOUT,
            CaptureFailureKind.STREAM_STOP_FAILED ->
                transitionToManualMode("안경 카메라 연결을 다시 준비해야 해 수동 모드로 전환합니다.")
            CaptureFailureKind.BUSY,
            CaptureFailureKind.PHOTO_CAPTURE_FAILED,
            CaptureFailureKind.FILE_SAVE_FAILED,
            CaptureFailureKind.NOT_READY,
            CaptureFailureKind.UNKNOWN -> {
                val step = uiState.value.currentStep ?: return
                scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
            }
            CaptureFailureKind.INVALID_REQUEST,
            CaptureFailureKind.CANCELLED -> Unit
        }
    }

    private fun handleJudgmentOutcome(outcome: JudgmentOutcome) {
        val current = uiState.value
        val activeSession = current.session ?: return
        val outcomeRequestId = when (outcome) {
            is JudgmentOutcome.Failure -> outcome.requestId
            is JudgmentOutcome.Success -> outcome.result.requestId
        }
        if (activeSession.activeRequestId != outcomeRequestId) return
        if (outcome is JudgmentOutcome.Success && !isCurrentJudgment(activeSession, current.currentStep?.order, outcome.result)) return
        if (activeSession.mode == SessionMode.MANUAL_ONLY) {
            handleManualGalleryJudgmentOutcome(outcome)
            return
        }
        when (outcome) {
            is JudgmentOutcome.Failure -> {
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    it.copy(
                        judgingInFlight = false,
                        judgeError = outcome.message,
                        session = session.copy(
                            phase = if (outcome.retryable) CookingPhase.NETWORK_RETRY else CookingPhase.WAITING_FOR_CHECK,
                            networkFailureCount = session.networkFailureCount + if (outcome.retryable) 1 else 0,
                            activeRequestId = null,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = currentStepOrder(session),
                                message = "판정 실패: ${outcome.message}",
                                requestId = outcome.requestId,
                                eventType = "NETWORK_FAILURE",
                                requestedAtMs = outcome.requestedAtMs,
                                respondedAtMs = outcome.respondedAtMs,
                                imageUri = session.lastCaptureUriByStep[currentStepOrder(session)]
                            )
                        )
                    )
                }
                val step = uiState.value.currentStep ?: return
                if (outcome.retryable) scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
            }

            is JudgmentOutcome.Success -> {
                applyVerdict(outcome.result)
            }
        }
    }

    private fun handleManualGalleryJudgmentOutcome(outcome: JudgmentOutcome) {
        when (outcome) {
            is JudgmentOutcome.Failure -> {
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    it.copy(
                        currentScreen = AppScreen.S8_MANUAL,
                        judgingInFlight = false,
                        judgeError = outcome.message,
                        session = session.copy(
                            phase = CookingPhase.MANUAL_MODE,
                            activeRequestId = null,
                            networkFailureCount = session.networkFailureCount + if (outcome.retryable) 1 else 0,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = currentStepOrder(session),
                                message = "갤러리 사진 판정 실패: ${outcome.message}",
                                requestId = outcome.requestId,
                                eventType = "NETWORK_FAILURE",
                                requestedAtMs = outcome.requestedAtMs,
                                respondedAtMs = outcome.respondedAtMs,
                                imageUri = session.lastCaptureUriByStep[currentStepOrder(session)]
                            )
                        )
                    )
                }
                announceCurrent("갤러리 사진을 판정하지 못했습니다. ${outcome.message}")
            }

            is JudgmentOutcome.Success -> {
                val result = outcome.result
                val completedAtMs = System.currentTimeMillis()
                val shouldAutoAdvance = shouldAutoAdvanceManualVerdict(result.verdict)
                val baselineTargetOrder = doneBaselineTarget(
                    recipe = uiState.value.selectedRecipe,
                    session = uiState.value.session,
                    completedStepOrder = result.stepOrder,
                    verdict = result.verdict
                )?.first
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    val carriedBaseline = doneBaselineTarget(
                        recipe = it.selectedRecipe,
                        session = session,
                        completedStepOrder = result.stepOrder,
                        verdict = result.verdict
                    )
                    val updated = session.copy(
                        phase = CookingPhase.MANUAL_MODE,
                        activeRequestId = null,
                        currentVerdict = result.verdict,
                        lastRoundTripMs = result.roundTripMs,
                        lastVlmLatencyMs = result.vlmLatencyMs,
                        lastReasonCode = result.reasonCode,
                        networkFailureCount = 0,
                        baselineUriByStep = carriedBaseline?.let { baseline ->
                            session.baselineUriByStep + baseline
                        } ?: session.baselineUriByStep,
                        completedStepOrders = if (shouldAutoAdvance) {
                            session.completedStepOrders + result.stepOrder
                        } else {
                            session.completedStepOrders
                        },
                        stepCompletedAtMsByOrder = if (shouldAutoAdvance) {
                            session.stepCompletedAtMsByOrder + (result.stepOrder to completedAtMs)
                        } else {
                            session.stepCompletedAtMsByOrder
                        },
                        autoDoneCount = session.autoDoneCount + if (result.verdict == JudgmentVerdict.DONE) 1 else 0,
                        notDoneCount = session.notDoneCount + if (result.verdict == JudgmentVerdict.NOT_DONE) 1 else 0,
                        cannotTellCount = session.cannotTellCount + if (result.verdict == JudgmentVerdict.CANNOT_TELL) 1 else 0,
                        logs = buildList {
                            addAll(session.logs)
                            add(
                                judgmentLog(
                                    stepOrder = result.stepOrder,
                                    result = result,
                                    message = "GALLERY_${result.verdict.name}"
                                )
                            )
                            carriedBaseline?.let { (nextStepOrder, imageUri) ->
                                add(
                                    SessionLogEntry(
                                        timestampMs = System.currentTimeMillis(),
                                        stepOrder = nextStepOrder,
                                        message = "${result.stepOrder}단계 DONE 사진을 비교 시작 사진으로 사용",
                                        eventType = "BASELINE_REUSED",
                                        imageUri = imageUri
                                    )
                                )
                            }
                        }
                    )
                    it.copy(
                        currentScreen = AppScreen.S8_MANUAL,
                        judgingInFlight = false,
                        judgeError = null,
                        session = updated
                    )
                }
                announceCurrent(
                    when (result.verdict) {
                        JudgmentVerdict.DONE -> if (baselineTargetOrder != null) {
                            "선택한 사진을 완료 상태로 판정했습니다. ${baselineTargetOrder}단계 비교 시작 사진으로 사용하고 다음 단계로 넘어갑니다."
                        } else {
                            "선택한 사진을 완료 상태로 판정했습니다. 다음 단계로 넘어갑니다."
                        }
                        JudgmentVerdict.NOT_DONE -> "선택한 사진은 아직 완료 상태가 아닙니다. 현재 단계를 계속해주세요."
                        JudgmentVerdict.CANNOT_TELL -> "선택한 사진만으로 판단하기 어렵습니다. 대상이 잘 보이는 사진을 다시 선택해주세요."
                    }
                )
                if (shouldAutoAdvance) {
                    autoAdvanceJob?.cancel()
                    autoAdvanceJob = viewModelScope.launch {
                        delay(AUTO_ADVANCE_DELAY_MS)
                        val current = uiState.value
                        val currentSession = current.session
                        if (
                            current.currentScreen == AppScreen.S8_MANUAL &&
                            currentSession != null &&
                            currentSession.mode == SessionMode.MANUAL_ONLY &&
                            current.currentStep?.order == result.stepOrder &&
                            currentSession.currentVerdict == JudgmentVerdict.DONE
                        ) {
                            autoAdvanceJob = null
                            advanceToNextStep(manual = false)
                        }
                    }
                }
            }
        }
    }

    private fun applyVerdict(result: com.example.myapplication.judgment.JudgmentResult) {
        val state = uiState.value
        val session = state.session ?: return
        val step = state.currentStep ?: return
        when (result.verdict) {
            JudgmentVerdict.DONE -> {
                val required = step.inspectionPolicy?.requiredConsecutiveDone?.coerceAtLeast(1) ?: 1
                val streak = session.consecutiveDoneCount + 1
                if (streak < required) {
                    mutableUiState.update {
                        it.copy(
                            currentScreen = AppScreen.S5_COOKING,
                            judgingInFlight = false,
                            session = session.copy(
                                phase = CookingPhase.WAITING_FOR_CHECK,
                                consecutiveDoneCount = streak,
                                cannotTellStreak = 0,
                                networkFailureCount = 0,
                                currentVerdict = result.verdict,
                                activeRequestId = null,
                                lastRoundTripMs = result.roundTripMs,
                                lastVlmLatencyMs = result.vlmLatencyMs,
                                lastReasonCode = result.reasonCode,
                                logs = session.logs + judgmentLog(step.order, result, "DONE $streak/$required")
                            )
                        )
                    }
                    announceCurrent("완료 상태를 한 번 더 확인할게요.")
                    scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
                    return
                }
                val completedAt = System.currentTimeMillis()
                val carriedBaseline = doneBaselineTarget(
                    recipe = state.selectedRecipe,
                    session = session,
                    completedStepOrder = step.order,
                    verdict = result.verdict
                )
                mutableUiState.update {
                    it.copy(
                        currentScreen = AppScreen.S6_STEP_DONE,
                        judgingInFlight = false,
                        session = session.copy(
                            phase = CookingPhase.STEP_COMPLETED,
                            autoDoneCount = session.autoDoneCount + 1,
                            consecutiveDoneCount = streak,
                            cannotTellStreak = 0,
                            networkFailureCount = 0,
                            currentVerdict = result.verdict,
                            activeRequestId = null,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            lastReasonCode = result.reasonCode,
                            baselineUriByStep = carriedBaseline?.let { baseline ->
                                session.baselineUriByStep + baseline
                            } ?: session.baselineUriByStep,
                            completedStepOrders = session.completedStepOrders + step.order,
                            stepCompletedAtMsByOrder = session.stepCompletedAtMsByOrder + (step.order to completedAt),
                            logs = buildList {
                                addAll(session.logs)
                                add(judgmentLog(step.order, result, "DONE"))
                                carriedBaseline?.let { (nextStepOrder, imageUri) ->
                                    add(
                                        SessionLogEntry(
                                            timestampMs = completedAt,
                                            stepOrder = nextStepOrder,
                                            message = "${step.order}단계 DONE 사진을 비교 시작 사진으로 사용",
                                            eventType = "BASELINE_REUSED",
                                            imageUri = imageUri
                                        )
                                    )
                                }
                            }
                        )
                    )
                }
                announceCurrent("${step.order}단계 완료. 다음 단계로 넘어갈게요.")
                autoAdvanceJob?.cancel()
                autoAdvanceJob = viewModelScope.launch {
                    delay(AUTO_ADVANCE_DELAY_MS)
                    if (uiState.value.currentScreen == AppScreen.S6_STEP_DONE) {
                        autoAdvanceJob = null
                        advanceToNextStep(manual = false)
                    }
                }
            }

            JudgmentVerdict.NOT_DONE -> {
                mutableUiState.update {
                    it.copy(
                        currentScreen = AppScreen.S5_COOKING,
                        judgingInFlight = false,
                        session = session.copy(
                            phase = CookingPhase.WAITING_FOR_CHECK,
                            notDoneCount = session.notDoneCount + 1,
                            consecutiveDoneCount = 0,
                            networkFailureCount = 0,
                            currentVerdict = result.verdict,
                            activeRequestId = null,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            lastReasonCode = result.reasonCode,
                            logs = session.logs + judgmentLog(step.order, result, "NOT_DONE")
                        )
                    )
                }
                announceCurrent("아직 완료 상태가 아니에요. 현재 단계를 계속해주세요.")
                scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
            }

            JudgmentVerdict.CANNOT_TELL -> {
                val streak = session.cannotTellStreak + 1
                val (nextPhase, nextScreen) = cannotTellDestination(streak)
                mutableUiState.update {
                    it.copy(
                        currentScreen = nextScreen,
                        judgingInFlight = false,
                        session = session.copy(
                            phase = nextPhase,
                            cannotTellStreak = streak,
                            consecutiveDoneCount = 0,
                            networkFailureCount = 0,
                            cannotTellCount = session.cannotTellCount + 1,
                            currentVerdict = result.verdict,
                            activeRequestId = null,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            lastReasonCode = result.reasonCode,
                            logs = session.logs + judgmentLog(step.order, result, "CANNOT_TELL")
                        )
                    )
                }
                if (streak >= 3) {
                    announceCurrent("자동 확인을 멈췄어요. 다 되면 다음이라고 말해주세요.")
                } else {
                    announceCurrent("팬이 잘 안 보여요. 팬 쪽을 한 번 봐주세요.")
                    scheduleInspection(AUTOMATIC_INSPECTION_INTERVAL_SECONDS)
                }
            }
        }
    }

    private fun transitionToManualMode(message: String) {
        cancelInspectionWork()
        pendingManualInspectionStepOrder = null
        mutableUiState.update {
            val session = it.session ?: return@update it
            it.copy(
                currentScreen = AppScreen.S8_MANUAL,
                session = session.copy(
                    phase = CookingPhase.MANUAL_MODE,
                    mode = SessionMode.MANUAL_ONLY
                )
            )
        }
        announceCurrent(message)
    }

    private fun announceCurrent(message: String) {
        announcementId += 1
        mutableUiState.update {
            it.copy(pendingAnnouncement = PendingAnnouncement(announcementId, limitAnnouncement(message)))
        }
    }

    private fun cancelInspectionWork() {
        baselineCaptureJob?.cancel()
        inspectionCountdownJob?.cancel()
        inspectionExecutionJob?.cancel()
        baselineCaptureJob = null
        inspectionCountdownJob = null
        inspectionExecutionJob = null
        autoAdvanceJob?.cancel()
        autoAdvanceJob = null
        presentationCaptureRevealJob?.cancel()
        presentationCaptureRevealJob = null
    }

    private fun schedulePresentationCaptureReveal() {
        presentationCaptureRevealJob?.cancel()
        val expectedSessionId = uiState.value.session?.id ?: return
        val expectedStepOrder = uiState.value.currentStep?.order ?: return
        presentationCaptureRevealJob = viewModelScope.launch {
            delay(PRESENTATION_CAPTURE_REVEAL_DELAY_MS)
            val current = uiState.value
            if (
                current.isPresentationSimulation &&
                current.currentScreen == AppScreen.S5_COOKING &&
                current.session?.id == expectedSessionId &&
                current.currentStep?.order == expectedStepOrder
            ) {
                mutableUiState.update { it.copy(presentationCaptureVisible = true) }
            }
            presentationCaptureRevealJob = null
        }
    }

    private fun startElapsedTicker() {
        elapsedTickerJob?.cancel()
        elapsedTickerJob = viewModelScope.launch {
            while (true) {
                val state = uiState.value
                val session = state.session ?: break
                val step = state.currentStep ?: break
                val elapsed = ((System.currentTimeMillis() - session.currentStepStartedAtMs) / 1_000L).toInt().coerceAtLeast(0)
                val maximum = step.inspectionPolicy?.maxExpectedSeconds ?: Int.MAX_VALUE
                val remaining = session.parallelTimerRemainingSeconds()
                mutableUiState.update {
                    it.copy(
                        stepElapsedSeconds = elapsed,
                        maxExpectedExceeded = elapsed > maximum,
                        parallelTimerRemainingSeconds = remaining
                    )
                }
                if (remaining == 0 && !session.parallelTimerFired && session.phase != CookingPhase.SESSION_COMPLETED) {
                    announceParallelTimerDone(session, step.order)
                    // 타이머를 기다리느라 붙잡아 둔 자동 진행이 있으면 풀어준다. 다만 곧바로 넘기면
                    // 다음 단계 안내가 "면을 건져 두세요"를 덮어써 사용자가 그 말을 듣지 못한다.
                    if (session.advanceBlockedByTimer) {
                        delay(AUTO_ADVANCE_DELAY_MS)
                        advanceToNextStep(manual = false)
                    }
                }
                delay(1_000L)
            }
        }
    }

    /**
     * 병렬 타이머 만료 안내. 지금 어느 단계에 있든 그 위로 올라간다.
     *
     * `parallelTimerFired` 를 먼저 세워 1초마다 도는 티커가 같은 안내를 반복하지 않게 한다.
     */
    private fun announceParallelTimerDone(session: CookingSession, currentStepOrder: Int) {
        val message = session.parallelTimerMessage ?: return
        mutableUiState.update { ui ->
            val current = ui.session ?: return@update ui
            if (current.parallelTimerFired) return@update ui
            ui.copy(
                session = current.copy(
                    parallelTimerFired = true,
                    logs = current.logs + SessionLogEntry(
                        timestampMs = System.currentTimeMillis(),
                        stepOrder = currentStepOrder,
                        message = "병렬 타이머 완료(${current.parallelTimerLabel ?: "타이머"}): $message"
                    )
                )
            )
        }
        announceCurrent(message)
    }

    private fun judgmentLog(
        stepOrder: Int,
        result: com.example.myapplication.judgment.JudgmentResult,
        message: String
    ) = SessionLogEntry(
        timestampMs = System.currentTimeMillis(),
        stepOrder = stepOrder,
        message = message,
        verdict = result.verdict,
        roundTripMs = result.roundTripMs,
        vlmLatencyMs = result.vlmLatencyMs,
        reasonCode = result.reasonCode,
        requestId = result.requestId,
        eventType = "JUDGMENT",
        requestedAtMs = result.requestedAtMs,
        respondedAtMs = result.respondedAtMs,
        imageUri = uiState.value.session?.lastCaptureUriByStep?.get(stepOrder)
    )

    private fun createFreshSession(
        recipeId: String = uiState.value.selectedRecipeId,
        mode: SessionMode = SessionMode.AUTO,
        phase: CookingPhase = CookingPhase.IDLE
    ): CookingSession {
        return CookingSession(
            id = UUID.randomUUID().toString(),
            recipeId = recipeId,
            mode = mode,
            phase = phase
        )
    }

    private fun currentStepOrder(session: CookingSession): Int = session.currentStepIndex + 1

    private fun screenForSession(session: CookingSession): AppScreen = when (session.phase) {
        CookingPhase.MANUAL_MODE -> AppScreen.S8_MANUAL
        CookingPhase.NEEDS_VIEW -> AppScreen.S7_NEEDS_VIEW
        CookingPhase.STEP_COMPLETED -> AppScreen.S6_STEP_DONE
        CookingPhase.SESSION_COMPLETED -> AppScreen.S9_SUMMARY
        else -> AppScreen.S5_COOKING
    }
}

/** 음성으로 읽어줄 남은 시간. "3분 20초" · "40초" */
internal fun formatRemaining(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return when {
        minutes == 0 -> "${seconds}초"
        seconds == 0 -> "${minutes}분"
        else -> "${minutes}분 ${seconds}초"
    }
}

internal fun limitAnnouncement(message: String): String = message
    .split(Regex("(?<=[.!?。！？])\\s+"))
    .filter(String::isNotBlank)
    .take(2)
    .joinToString(" ")

internal fun isCurrentJudgment(
    session: CookingSession,
    currentStepOrder: Int?,
    result: com.example.myapplication.judgment.JudgmentResult
): Boolean = session.activeRequestId == result.requestId &&
    session.id == result.cookingSessionId &&
    currentStepOrder == result.stepOrder

internal fun isCurrentCaptureRequest(
    session: CookingSession?,
    currentStepOrder: Int?,
    request: CaptureRequest
): Boolean = session?.id == request.cookingSessionId &&
    session.activeRequestId == request.requestId &&
    currentStepOrder == request.stepOrder

internal fun galleryJudgmentValidationError(
    step: RecipeStep,
    hasBaselineImage: Boolean
): String? = when {
    !step.isAutoCheck || step.checkType == CheckType.TIMER_ONLY || step.checkCondition.isNullOrBlank() ->
        "이 단계는 사진 판정을 사용하지 않습니다. 수동으로 다음 단계로 이동해주세요."
    step.shouldSendStartImage() && !hasBaselineImage ->
        "비교 판정에 필요한 시작 사진이 없습니다. 비교 시작 사진을 먼저 불러와주세요."
    else -> null
}

internal fun doneBaselineTarget(
    recipe: Recipe?,
    session: CookingSession?,
    completedStepOrder: Int,
    verdict: JudgmentVerdict
): Pair<Int, String>? {
    if (recipe == null || session == null || verdict != JudgmentVerdict.DONE) return null
    val completedIndex = recipe.steps.indexOfFirst { it.order == completedStepOrder }
    if (completedIndex < 0) return null
    val nextStep = recipe.steps.getOrNull(completedIndex + 1) ?: return null
    if (!nextStep.needsStartImage || !nextStep.baselineOnStepStart) return null
    val completedImageUri = session.lastCaptureUriByStep[completedStepOrder] ?: return null
    return nextStep.order to completedImageUri
}

internal fun hasReusableStartImage(step: RecipeStep, session: CookingSession): Boolean =
    step.shouldSendStartImage() && session.baselineUriByStep[step.order] != null

internal data class BaselineCapturePlan(
    val delaySeconds: Int,
    val inspectionRunsWhileWaitingForBaseline: Boolean
)

internal fun baselineCapturePlan(
    step: RecipeStep,
    manuallyAdvancedIntoStep: Boolean,
    forceImmediate: Boolean = false
): BaselineCapturePlan = when {
    forceImmediate -> BaselineCapturePlan(
        delaySeconds = 0,
        inspectionRunsWhileWaitingForBaseline = false
    )
    step.shouldSendStartImage() && step.baselineOnStepStart && manuallyAdvancedIntoStep -> BaselineCapturePlan(
        delaySeconds = MANUAL_ADVANCE_BASELINE_DELAY_SECONDS,
        inspectionRunsWhileWaitingForBaseline = false
    )
    step.shouldSendStartImage() && !step.baselineOnStepStart -> BaselineCapturePlan(
        delaySeconds = BASELINE_CAPTURE_DELAY_SECONDS,
        inspectionRunsWhileWaitingForBaseline = true
    )
    else -> BaselineCapturePlan(
        delaySeconds = 0,
        inspectionRunsWhileWaitingForBaseline = false
    )
}

internal fun shouldAutoAdvanceManualVerdict(verdict: JudgmentVerdict): Boolean =
    verdict == JudgmentVerdict.DONE

internal fun blocksNextStepDuringStart(
    phase: CookingPhase,
    allowDuringStepStarting: Boolean
): Boolean = phase == CookingPhase.STEP_STARTING && !allowDuringStepStarting

private fun cameraStateHint(state: WearableCameraState, isFake: Boolean): String {
    if (isFake) return "가짜 Gateway 상태: ${state::class.simpleName}"
    return when (state) {
        WearableCameraState.NotStarted -> "Meta AI 앱에서 Developer Mode를 켜고 안경을 등록해주세요."
        WearableCameraState.Registering -> "Meta AI 앱에서 등록을 완료해주세요."
        WearableCameraState.PermissionRequired -> "웨어러블 카메라 권한을 허용해주세요."
        WearableCameraState.Searching -> "사용 가능한 안경을 찾고 있습니다."
        WearableCameraState.Connecting -> "안경 세션을 연결하고 있습니다."
        WearableCameraState.Ready -> "안경 카메라 준비 완료. 검사 순간에만 촬영합니다."
        WearableCameraState.Capturing -> "안경 카메라로 사진을 촬영하고 있습니다."
        WearableCameraState.Busy -> "안경 카메라 자원을 정리하고 있습니다."
        WearableCameraState.Disconnected -> "안경 연결이 끊겼습니다. 다시 연결해주세요."
        is WearableCameraState.Error -> state.message
        WearableCameraState.Released -> "카메라 세션이 종료되었습니다."
    }
}

internal fun parseVoiceCommand(text: String): VoiceCommand? {
    val normalized = text.trim().lowercase()
    return when {
        "재료" in normalized -> VoiceCommand.INGREDIENTS
        "이거 맞아" in normalized || "이게 맞아" in normalized -> VoiceCommand.VERIFY_INGREDIENT
        "몇 단계" in normalized || "몇단계" in normalized -> VoiceCommand.CURRENT_STEP
        "자동 확인 다시" in normalized || "자동확인 다시" in normalized -> VoiceCommand.RESUME_AUTO
        "다음" in normalized -> VoiceCommand.NEXT
        "아직" in normalized -> VoiceCommand.NOT_YET
        "다시" in normalized -> VoiceCommand.REPEAT
        "이전" in normalized -> VoiceCommand.PREVIOUS
        "확인" in normalized -> VoiceCommand.CHECK_NOW
        else -> null
    }
}

internal fun isVoiceCommandAllowed(command: VoiceCommand, screen: AppScreen): Boolean = when (command) {
    VoiceCommand.INGREDIENTS, VoiceCommand.CURRENT_STEP -> screen in setOf(AppScreen.S5_COOKING, AppScreen.S6_STEP_DONE, AppScreen.S7_NEEDS_VIEW, AppScreen.S8_MANUAL)
    VoiceCommand.VERIFY_INGREDIENT, VoiceCommand.CHECK_NOW -> screen in setOf(AppScreen.S5_COOKING, AppScreen.S7_NEEDS_VIEW)
    VoiceCommand.RESUME_AUTO -> screen == AppScreen.S8_MANUAL
    VoiceCommand.NEXT -> screen in setOf(AppScreen.S5_COOKING, AppScreen.S6_STEP_DONE, AppScreen.S7_NEEDS_VIEW, AppScreen.S8_MANUAL)
    VoiceCommand.NOT_YET -> screen == AppScreen.S5_COOKING
    VoiceCommand.REPEAT -> screen in setOf(AppScreen.S5_COOKING, AppScreen.S8_MANUAL)
    VoiceCommand.PREVIOUS -> screen in setOf(AppScreen.S5_COOKING, AppScreen.S6_STEP_DONE, AppScreen.S8_MANUAL)
}

internal fun cannotTellDestination(streak: Int): Pair<CookingPhase, AppScreen> =
    if (streak >= 3) CookingPhase.MANUAL_MODE to AppScreen.S8_MANUAL
    else CookingPhase.NEEDS_VIEW to AppScreen.S7_NEEDS_VIEW
