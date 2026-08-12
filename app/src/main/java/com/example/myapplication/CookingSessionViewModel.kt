package com.example.myapplication

import android.app.Application
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class CookingSessionViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val cameraGateway = FakeWearableCameraGateway(application)
    private val judgmentGateway = FakeJudgmentGateway()
    private val initialRecipes = RecipeFixtures.sampleRecipes().filter { it.id == "kimchi" } +
        RecipeFixtures.sampleRecipes().filter { it.id != "kimchi" }

    private val mutableUiState = MutableStateFlow(
        CookingSessionUiState(
            recipes = initialRecipes,
            selectedRecipeId = initialRecipes.first().id
        )
    )
    val uiState: StateFlow<CookingSessionUiState> = mutableUiState.asStateFlow()

    private var announcementId = 0L
    private var inspectionCountdownJob: Job? = null
    private var inspectionExecutionJob: Job? = null

    init {
        viewModelScope.launch {
            cameraGateway.state.collectLatest { state ->
                mutableUiState.update { it.copy(cameraState = state) }
            }
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
        mutableUiState.update { it.copy(currentScreen = AppScreen.S1_HOME, session = null, nextInspectionInSeconds = null) }
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
        val state = uiState.value
        val recipe = state.selectedRecipe ?: return
        val session = state.session ?: return
        val nextIndex = session.currentStepIndex + 1
        if (nextIndex >= recipe.steps.size) {
            cancelInspectionWork()
            mutableUiState.update {
                it.copy(
                    currentScreen = AppScreen.S9_SUMMARY,
                    session = session.copy(phase = CookingPhase.SESSION_COMPLETED)
                )
            }
            announceCurrent("${recipe.title} 조리가 끝났습니다.")
            return
        }
        startStep(nextIndex, manualOnly = session.mode == SessionMode.MANUAL_ONLY, manualIncrement = true)
    }

    fun moveToPreviousStep() {
        val session = uiState.value.session ?: return
        if (session.currentStepIndex == 0) return
        val updated = session.copy(
            currentStepIndex = session.currentStepIndex - 1,
            undoDoneCount = session.undoDoneCount + 1,
            phase = if (session.mode == SessionMode.MANUAL_ONLY) CookingPhase.MANUAL_MODE else CookingPhase.STEP_STARTING
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
        val normalized = text.trim().lowercase()
        when {
            "다음" in normalized -> continueToNextStep()
            "아직" in normalized -> keepCurrentStepAndReschedule()
            "다시" in normalized -> repeatCurrentStep()
            "이전" in normalized -> moveToPreviousStep()
            "확인" in normalized -> triggerImmediateInspection()
            "자동 확인 다시" in normalized || "자동확인 다시" in normalized -> resumeAutoMode()
        }
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
        viewModelScope.launch { judgmentGateway.release() }
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
        val session = previous.copy(
            recipeId = recipe.id,
            phase = CookingPhase.STEP_STARTING,
            mode = if (manualOnly) SessionMode.MANUAL_ONLY else previous.mode,
            currentStepIndex = stepIndex,
            manualNextCount = if (manualIncrement) previous.manualNextCount + 1 else previous.manualNextCount,
            cannotTellStreak = if (keepCounts) previous.cannotTellStreak else 0,
            currentVerdict = null,
            activeRequestId = null,
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
                    judgmentGateway.setBehavior(
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
                val judgmentOutcome = judgmentGateway.judge(
                    JudgmentRequest(
                        requestId = request.requestId,
                        cookingSessionId = state.session?.id.orEmpty(),
                        recipeId = recipe.id,
                        stepOrder = step.order,
                        instruction = step.instruction,
                        checkType = step.checkType,
                        checkCondition = step.checkCondition,
                        elapsedSeconds = step.inspectionPolicy?.earliestCheckSeconds ?: 0,
                        baselineImageUri = state.session?.baselineUriByStep?.get(step.order),
                        currentImageUri = outcome.artifact.imageUri
                    )
                )
                handleJudgmentOutcome(judgmentOutcome)
            }
        }
    }

    private fun handleJudgmentOutcome(outcome: JudgmentOutcome) {
        when (outcome) {
            is JudgmentOutcome.Failure -> {
                mutableUiState.update {
                    val session = it.session ?: return@update it
                    it.copy(
                        judgingInFlight = false,
                        judgeError = outcome.message,
                        session = session.copy(
                            phase = CookingPhase.WAITING_FOR_CHECK,
                            activeRequestId = null,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = currentStepOrder(session),
                                message = "판정 실패: ${outcome.message}"
                            )
                        )
                    )
                }
                val step = uiState.value.currentStep ?: return
                scheduleInspection(step.inspectionPolicy?.checkIntervalSeconds ?: 10)
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
                mutableUiState.update {
                    it.copy(
                        currentScreen = AppScreen.S6_STEP_DONE,
                        judgingInFlight = false,
                        session = session.copy(
                            phase = CookingPhase.STEP_COMPLETED,
                            autoDoneCount = session.autoDoneCount + 1,
                            cannotTellStreak = 0,
                            currentVerdict = result.verdict,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = step.order,
                                message = "DONE",
                                verdict = result.verdict,
                                roundTripMs = result.roundTripMs,
                                vlmLatencyMs = result.vlmLatencyMs
                            )
                        )
                    )
                }
                announceCurrent("${step.order}단계 완료. 다음 단계로 넘어갈게요.")
            }

            JudgmentVerdict.NOT_DONE -> {
                mutableUiState.update {
                    it.copy(
                        currentScreen = AppScreen.S5_COOKING,
                        judgingInFlight = false,
                        session = session.copy(
                            phase = CookingPhase.WAITING_FOR_CHECK,
                            notDoneCount = session.notDoneCount + 1,
                            currentVerdict = result.verdict,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = step.order,
                                message = "NOT_DONE",
                                verdict = result.verdict,
                                roundTripMs = result.roundTripMs,
                                vlmLatencyMs = result.vlmLatencyMs
                            )
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
                            cannotTellCount = session.cannotTellCount + 1,
                            currentVerdict = result.verdict,
                            lastRoundTripMs = result.roundTripMs,
                            lastVlmLatencyMs = result.vlmLatencyMs,
                            logs = session.logs + SessionLogEntry(
                                timestampMs = System.currentTimeMillis(),
                                stepOrder = step.order,
                                message = "CANNOT_TELL",
                                verdict = result.verdict,
                                roundTripMs = result.roundTripMs,
                                vlmLatencyMs = result.vlmLatencyMs
                            )
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
            it.copy(pendingAnnouncement = PendingAnnouncement(announcementId, message))
        }
    }

    private fun cancelInspectionWork() {
        inspectionCountdownJob?.cancel()
        inspectionExecutionJob?.cancel()
        inspectionCountdownJob = null
        inspectionExecutionJob = null
    }

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
}
