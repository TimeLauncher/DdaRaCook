package com.example.myapplication

import com.example.myapplication.camera.CaptureFailureKind
import com.example.myapplication.camera.CaptureOutcome
import com.example.myapplication.camera.WearableCameraState

enum class AppScreen {
    S1_HOME,
    S2_RECIPE_DETAIL,
    S3_RECIPE_EDITOR,
    S4_DEVICE,
    S5_COOKING,
    S6_STEP_DONE,
    S7_NEEDS_VIEW,
    S8_MANUAL,
    S9_SUMMARY
}

data class PendingAnnouncement(
    val id: Long,
    val message: String
)

data class CookingSessionUiState(
    val recipes: List<Recipe>,
    val selectedRecipeId: String = recipes.firstOrNull()?.id.orEmpty(),
    val currentScreen: AppScreen = AppScreen.S1_HOME,
    val session: CookingSession? = null,
    val cameraState: WearableCameraState = WearableCameraState.NotStarted,
    val currentCaptureOutcome: CaptureOutcome? = null,
    val lastCaptureFailureKind: CaptureFailureKind? = null,
    val pendingAnnouncement: PendingAnnouncement? = null,
    val isListening: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val deviceHint: String = "가짜 안경 게이트웨이를 사용 중입니다.",
    val nextInspectionInSeconds: Int? = null,
    val judgingInFlight: Boolean = false,
    val judgeError: String? = null,
    val speechError: String? = null,
    val stepElapsedSeconds: Int = 0,
    val maxExpectedExceeded: Boolean = false,
    val hasResumableSession: Boolean = false,
    val useMockJudgment: Boolean = true,
    val selectedMockVerdict: JudgmentVerdict = JudgmentVerdict.DONE,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val serverReady: Boolean? = null,
    val serverStatusMessage: String? = null,
    val serverBaseUrl: String = ""
) {
    val selectedRecipe: Recipe?
        get() = recipes.firstOrNull { it.id == selectedRecipeId }

    val currentStep: RecipeStep?
        get() = selectedRecipe?.steps?.getOrNull(session?.currentStepIndex ?: 0)
}
