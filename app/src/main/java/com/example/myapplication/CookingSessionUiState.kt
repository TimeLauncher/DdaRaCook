package com.example.myapplication

import com.example.myapplication.camera.CaptureFailureKind
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.WearableCameraState

enum class AppScreen {
    S0_SERVICE_HOME,
    S1_HOME,
    S2_RECIPE_DETAIL,
    S3_RECIPE_EDITOR,
    S4_DEVICE,
    S5_COOKING,
    S6_STEP_DONE,
    S7_NEEDS_VIEW,
    S8_MANUAL,
    S9_SUMMARY,
    S10_MY
}

data class PendingAnnouncement(
    val id: Long,
    val message: String
)

data class CookingSessionUiState(
    val recipes: List<Recipe>,
    val selectedRecipeId: String = recipes.firstOrNull()?.id.orEmpty(),
    val currentScreen: AppScreen = AppScreen.S0_SERVICE_HOME,
    val recipeDetailReturnScreen: AppScreen = AppScreen.S0_SERVICE_HOME,
    val session: CookingSession? = null,
    val cameraState: WearableCameraState = WearableCameraState.NotStarted,
    val currentCaptureOutcome: CaptureOutcome? = null,
    val lastCaptureFailureKind: CaptureFailureKind? = null,
    val consecutiveCameraTimeouts: Int = 0,
    val pendingAnnouncement: PendingAnnouncement? = null,
    val isListening: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val deviceHint: String = "가짜 안경 게이트웨이를 사용 중입니다.",
    val nextInspectionInSeconds: Int? = null,
    val judgingInFlight: Boolean = false,
    val judgeError: String? = null,
    val speechError: String? = null,
    val lastVoiceTranscript: String? = null,
    val stepElapsedSeconds: Int = 0,
    val maxExpectedExceeded: Boolean = false,
    val parallelTimerRemainingSeconds: Int? = null,
    val hasResumableSession: Boolean = false,
    val resumeAutoAfterDeviceSetup: Boolean = false,
    val useFakeCamera: Boolean = true,
    val useMockJudgment: Boolean = false,
    val selectedMockVerdict: JudgmentVerdict = JudgmentVerdict.DONE,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val serverReady: Boolean? = null,
    val serverStatusMessage: String? = null,
    val serverBaseUrl: String = "",
    val scrappedRecipeIds: Set<String> = emptySet(),
    val viewedRecipeIds: List<String> = emptyList(),
    val voiceGuidanceEnabled: Boolean = true,
    val presentationSimulationSelected: Boolean = false,
    val presentationCaptureVisible: Boolean = false
) {
    val isPresentationSimulation: Boolean
        get() = presentationSimulationSelected ||
            session?.logs?.any { it.eventType == PresentationSimulation.EVENT_TYPE } == true

    val selectedRecipe: Recipe?
        get() = recipes.firstOrNull { it.id == selectedRecipeId }

    val currentStep: RecipeStep?
        get() = selectedRecipe?.steps?.getOrNull(session?.currentStepIndex ?: 0)
}
