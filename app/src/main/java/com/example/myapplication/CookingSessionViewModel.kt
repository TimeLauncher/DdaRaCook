package com.example.myapplication

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.camera.CaptureFailureKind
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.CapturePurpose
import com.example.myapplication.camera.CaptureRequest
import com.example.myapplication.camera.FakeCaptureBehavior
import com.example.myapplication.camera.FakeWearableCameraGateway
import com.example.myapplication.camera.WearableCameraState
import com.example.myapplication.judgment.FakeJudgmentBehavior
import com.example.myapplication.judgment.FakeJudgmentGateway
import com.example.myapplication.judgment.JudgmentOutcome
import com.example.myapplication.judgment.JudgmentRequest
import com.example.myapplication.judgment.JudgeApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File

class CookingSessionViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val persistence = AppPersistence(application)
    private val cameraGateway = FakeWearableCameraGateway(application)
    private val fakeJudgmentGateway = FakeJudgmentGateway()
    private val networkJudgmentGateway = JudgeApiService(application)
    private val fixtureRecipes = RecipeFixtures.sampleRecipes().filter { it.id == "kimchi" } +
        RecipeFixtures.sampleRecipes().filter { it.id != "kimchi" }
    private val initialRecipes = persistence.loadRecipes(fixtureRecipes)
    private val restoredSession = persistence.loadSession()?.takeIf { saved ->
        initialRecipes.any { it.id == saved.recipeId } && saved.phase != CookingPhase.SESSION_COMPLETED
    }

    private val mutableUiState = MutableStateFlow(
        CookingSessionUiState(
            recipes = initialRecipes,
            selectedRecipeId = restoredSession?.recipeId ?: initialRecipes.first().id,
            session = restoredSession,
            hasResumableSession = restoredSession != null
        )
    )
    val uiState: StateFlow<CookingSessionUiState> = mutableUiState.asStateFlow()

    private var announcementId = 0L
    private var inspectionCountdownJob: Job? = null
    private var inspectionExecutionJob: Job? = null
    private var elapsedTickerJob: Job? = null
    private var autoAdvanceJob: Job? = null

    init {
        viewModelScope.launch {
            cameraGateway.state.collectLatest { state ->
                mutableUiState.update { it.copy(cameraState = state) }
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                persistence.saveSession(state.session?.takeUnless { it.phase == CookingPhase.SESSION_COMPLETED })
            }
        }
    }

    fun openRecipeEditor(recipeId: String? = null) {
        cancelInspectionWork()
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipeId ?: "",
                currentScreen = AppScreen.S3_RECIPE_EDITOR,
                session = null,
                hasResumableSession = false
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
        mutableUiState.update { state ->
            val session = state.session ?: return@update state
            (session.lastCaptureUriByStep.values + session.baselineUriByStep.values)
                .distinct()
                .forEach { value ->
                    val uri = Uri.parse(value)
                    if (uri.scheme == "file") runCatching { File(requireNotNull(uri.path)).delete() }
                }
            state.copy(session = session.copy(lastCaptureUriByStep = emptyMap(), baselineUriByStep = emptyMap()))
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
            scheduleInspection(uiState.value.currentStep?.inspectionPolicy?.checkIntervalSeconds ?: 10)
        }
    }

    fun selectRecipe(recipeId: String) {
        cancelInspectionWork()
        mutableUiState.update {
            it.copy(
                selectedRecipeId = recipeId,
                currentScreen = AppScreen.S2_RECIPE_DETAIL,
                session = null,
                currentCaptureOutcome = null,
                lastCaptureFailureKind = null,
                judgeError = null,
                nextInspectionInSeconds = null
            )
        }
    }

    fun backToHome() {
        cancelInspectionWork()
        persistence.saveSession(null)
        mutableUiState.update { it.copy(currentScreen = AppScreen.S1_HOME, session = null, hasResumableSession = false, nextInspectionInSeconds = null) }
    }

    fun openDevicePreparation() {
        cameraGateway.setState(WearableCameraState.Registering)
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S4_DEVICE,
                deviceHint = "가짜 Gateway를 사용해 등록/권한/연결 상태를 재현합니다.",
                session = createFreshSession(phase = CookingPhase.PREPARING_DEVICE)
            )
        }
    }

    fun advanceFakeDeviceState() {
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
        cameraGateway.setState(next)
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
        cameraGateway.setState(WearableCameraState.Disconnected)
        mutableUiState.update {
            it.copy(
                deviceHint = "연결 끊김을 재현했습니다. 자동 검사는 멈추고 수동 진행으로 전환할 수 있습니다."
            )
        }
        transitionToManualMode("안경 연결이 끊겨 수동 모드로 전환합니다.")
    }

    fun simulateDeviceError() {
        cameraGateway.setState(WearableCameraState.Error("가짜 연결 실패"))
        mutableUiState.update {
            it.copy(deviceHint = "가짜 오류 상태입니다. 다시 연결하거나 안경 없이 시작할 수 있습니다.")
        }
    }

    fun startWithoutGlasses() {
        cancelInspectionWork()
        cameraGateway.setState(WearableCameraState.Disconnected)
        val recipe = uiState.value.selectedRecipe ?: return
        val session = createFreshSession(mode = SessionMode.MANUAL_ONLY, phase = CookingPhase.MANUAL_MODE, recipeId = recipe.id)
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S8_MANUAL,
                session = session
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
        val session = state.session ?: return
        if (session.mode == SessionMode.MANUAL_ONLY || session.phase == CookingPhase.CAPTURING || session.phase == CookingPhase.JUDGING) {
            return
        }
        cancelInspectionWork()
        inspectionExecutionJob = viewModelScope.launch {
            promptAndInspect(isManualRequest = true)
        }
    }

    fun continueToNextStep() {
        advanceToNextStep(manual = true)
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
        if (session !== originalSession) mutableUiState.update { it.copy(session = session) }
        startStep(nextIndex, manualOnly = session.mode == SessionMode.MANUAL_ONLY, manualIncrement = manual)
    }

    fun moveToPreviousStep() {
        val session = uiState.value.session ?: return
        if (session.currentStepIndex == 0) return
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

    fun keepCurrentStepAndReschedule() {
        val step = uiState.value.currentStep ?: return
        val session = uiState.value.session ?: return
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = session.copy(phase = CookingPhase.WAITING_FOR_CHECK)
            )
        }
        scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
    }

    fun resumeAutoMode() {
        val session = uiState.value.session ?: return
        val currentIndex = session.currentStepIndex
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
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
        mutableUiState.update { it.copy(speechError = null) }
        val normalized = text.trim().lowercase()
        val screen = uiState.value.currentScreen
        when {
            "재료" in normalized -> announceIngredients()
            "자동 확인 다시" in normalized || "자동확인 다시" in normalized -> if (screen == AppScreen.S8_MANUAL) resumeAutoMode()
            "다음" in normalized -> if (screen in setOf(AppScreen.S5_COOKING, AppScreen.S6_STEP_DONE, AppScreen.S7_NEEDS_VIEW, AppScreen.S8_MANUAL)) continueToNextStep()
            "아직" in normalized -> if (screen == AppScreen.S5_COOKING) keepCurrentStepAndReschedule()
            "다시" in normalized -> if (screen in setOf(AppScreen.S5_COOKING, AppScreen.S8_MANUAL)) repeatCurrentStep()
            "이전" in normalized -> if (screen in setOf(AppScreen.S5_COOKING, AppScreen.S6_STEP_DONE, AppScreen.S8_MANUAL)) moveToPreviousStep()
            "확인" in normalized -> if (screen in setOf(AppScreen.S5_COOKING, AppScreen.S7_NEEDS_VIEW)) triggerImmediateInspection()
            else -> onSpeechError("사용할 수 있는 음성 명령을 인식하지 못했습니다.")
        }
    }

    private fun announceIngredients() {
        val recipe = uiState.value.selectedRecipe ?: return
        val message = recipe.ingredients.joinToString(", ") { "${it.name} ${it.amount}" }
        announceCurrent(limitAnnouncement("재료는 $message 입니다."))
    }

    fun setMockJudgmentEnabled(enabled: Boolean) {
        mutableUiState.update { it.copy(useMockJudgment = enabled) }
    }

    fun setMockVerdict(verdict: JudgmentVerdict) {
        mutableUiState.update { it.copy(selectedMockVerdict = verdict) }
    }

    fun setFakeCaptureBehaviorSuccess() {
        cameraGateway.setBehavior(FakeCaptureBehavior.Success())
    }

    fun setFakeCaptureBehaviorBusy() {
        cameraGateway.setBehavior(
            FakeCaptureBehavior.Failure(
                kind = CaptureFailureKind.BUSY,
                retryable = true,
                userMessage = "가짜 BUSY 상태입니다.",
                debugMessage = "Configured BUSY failure"
            )
        )
    }

    fun setFakeCaptureBehaviorDisconnect() {
        cameraGateway.setBehavior(FakeCaptureBehavior.Disconnect)
    }

    fun setFakeCaptureBehaviorFailure() {
        cameraGateway.setBehavior(
            FakeCaptureBehavior.Failure(
                kind = CaptureFailureKind.UNKNOWN,
                retryable = true,
                userMessage = "가짜 촬영 실패입니다.",
                debugMessage = "Configured generic failure"
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        cancelInspectionWork()
        viewModelScope.launch { cameraGateway.release() }
        viewModelScope.launch { fakeJudgmentGateway.release() }
        viewModelScope.launch { networkJudgmentGateway.release() }
    }

    private fun startStep(
        stepIndex: Int,
        manualOnly: Boolean,
        manualIncrement: Boolean = false,
        keepCounts: Boolean = false
    ) {
        cancelInspectionWork()
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
        viewModelScope.launch {
            captureBaseline(step)
            mutableUiState.update { ui -> ui.copy(session = (ui.session ?: session).copy(phase = CookingPhase.WAITING_FOR_CHECK)) }
            scheduleInspection(step.inspectionPolicy?.earliestCheckSeconds ?: 10)
        }
    }

    private suspend fun captureBaseline(step: RecipeStep) {
        val state = uiState.value
        val session = state.session ?: return
        val request = CaptureRequest(
            requestId = UUID.randomUUID().toString(),
            cookingSessionId = session.id,
            stepOrder = step.order,
            purpose = CapturePurpose.BASELINE,
            burstDurationMs = step.inspectionPolicy?.burstSeconds?.times(1_000L) ?: 3_000L
        )
        val outcome = cameraGateway.capture(request)
        when (outcome) {
            is CaptureOutcome.Success -> {
                mutableUiState.update {
                    val currentSession = it.session ?: return@update it
                    it.copy(
                        currentCaptureOutcome = outcome,
                        session = currentSession.copy(
                            baselineUriByStep = currentSession.baselineUriByStep + (step.order to outcome.artifact.imageUri),
                            lastCaptureUriByStep = currentSession.lastCaptureUriByStep + (step.order to outcome.artifact.imageUri)
                        )
                    )
                }
            }
            is CaptureOutcome.Failure -> {
                mutableUiState.update { it.copy(currentCaptureOutcome = outcome, lastCaptureFailureKind = outcome.kind) }
            }
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
        mutableUiState.update {
            it.copy(
                currentScreen = AppScreen.S5_COOKING,
                session = session.copy(phase = CookingPhase.PROMPTING_USER),
                nextInspectionInSeconds = null
            )
        }
        announceCurrent("상태를 확인할게요. 팬을 봐주세요.")
        delay(1_000L)

        val request = CaptureRequest(
            requestId = UUID.randomUUID().toString(),
            cookingSessionId = session.id,
            stepOrder = step.order,
            purpose = if (isManualRequest) CapturePurpose.MANUAL_CHECK else CapturePurpose.INSPECTION,
            burstDurationMs = step.inspectionPolicy?.burstSeconds?.times(1_000L) ?: 3_000L,
            streamTimeoutMs = 7_000L,
            captureTimeoutMs = 7_000L
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
        val outcome = cameraGateway.capture(request)
        handleCaptureOutcome(request, outcome)
    }

    private suspend fun handleCaptureOutcome(request: CaptureRequest, outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.Failure -> {
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    it.copy(
                        currentCaptureOutcome = outcome,
                        lastCaptureFailureKind = outcome.kind,
                        session = session.copy(
                            phase = if (session.mode == SessionMode.MANUAL_ONLY) CookingPhase.MANUAL_MODE else CookingPhase.WAITING_FOR_CHECK,
                            activeRequestId = null
                        )
                    )
                }
                when (outcome.kind) {
                    CaptureFailureKind.DEVICE_DISCONNECTED -> transitionToManualMode("촬영 중 연결이 끊겨 수동 모드로 전환합니다.")
                    CaptureFailureKind.BUSY,
                    CaptureFailureKind.STREAM_TIMEOUT,
                    CaptureFailureKind.CAPTURE_TIMEOUT,
                    CaptureFailureKind.NOT_READY,
                    CaptureFailureKind.UNKNOWN -> {
                        val step = uiState.value.currentStep ?: return
                        scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
                    }
                    CaptureFailureKind.CANCELLED,
                    CaptureFailureKind.PERMISSION_DENIED -> Unit
                }
            }

            is CaptureOutcome.Success -> {
                val afterCapture = uiState.value.session ?: return
                mutableUiState.update {
                    it.copy(
                        currentCaptureOutcome = outcome,
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
                        elapsedSeconds = state.session?.let {
                            ((System.currentTimeMillis() - it.currentStepStartedAtMs) / 1_000L).toInt().coerceAtLeast(0)
                        } ?: 0,
                        baselineImageUri = state.session?.baselineUriByStep?.get(step.order),
                        currentImageUri = outcome.artifact.imageUri
                    )
                )
                handleJudgmentOutcome(judgmentOutcome)
            }
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
                                eventType = "NETWORK_FAILURE"
                            )
                        )
                    )
                }
                val step = uiState.value.currentStep ?: return
                if (outcome.retryable) scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
            }

            is JudgmentOutcome.Success -> {
                applyVerdict(outcome.result)
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
                    scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
                    return
                }
                val completedAt = System.currentTimeMillis()
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
                            completedStepOrders = session.completedStepOrders + step.order,
                            stepCompletedAtMsByOrder = session.stepCompletedAtMsByOrder + (step.order to completedAt),
                            logs = session.logs + judgmentLog(step.order, result, "DONE")
                        )
                    )
                }
                announceCurrent("${step.order}단계 완료. 다음 단계로 넘어갈게요.")
                autoAdvanceJob?.cancel()
                autoAdvanceJob = viewModelScope.launch {
                    delay(2_000L)
                    if (uiState.value.currentScreen == AppScreen.S6_STEP_DONE) advanceToNextStep(manual = false)
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
                scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
            }

            JudgmentVerdict.CANNOT_TELL -> {
                val streak = session.cannotTellStreak + 1
                val nextPhase = if (streak >= 3) CookingPhase.MANUAL_MODE else CookingPhase.NEEDS_VIEW
                val nextScreen = if (streak >= 3) AppScreen.S8_MANUAL else AppScreen.S7_NEEDS_VIEW
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
                    scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
                }
            }
        }
    }

    private fun transitionToManualMode(message: String) {
        cancelInspectionWork()
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
        inspectionCountdownJob?.cancel()
        inspectionExecutionJob?.cancel()
        inspectionCountdownJob = null
        inspectionExecutionJob = null
        autoAdvanceJob?.cancel()
        autoAdvanceJob = null
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
                mutableUiState.update { it.copy(stepElapsedSeconds = elapsed, maxExpectedExceeded = elapsed > maximum) }
                delay(1_000L)
            }
        }
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
        eventType = "JUDGMENT"
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
