package com.example.myapplication.judgment

import com.example.myapplication.CheckType
import com.example.myapplication.ImageCropTarget
import com.example.myapplication.ReasonCode
import com.example.myapplication.JudgmentVerdict
import kotlinx.coroutines.flow.StateFlow

enum class JudgmentImagePolicy {
    /** 자동 카메라: 폰 YOLO에 쓸 전체 시야를 긴 변 최대 1365px로 준비한다. */
    AUTOMATIC_CAMERA,

    /** 수동 모드: 크롭 없이 긴 변만 최대 1024px로 축소한다. */
    MANUAL_MODE
}

data class JudgmentRequest(
    val requestId: String,
    val cookingSessionId: String,
    val recipeId: String,
    val stepOrder: Int,
    val instruction: String,
    val checkType: CheckType,
    val checkCondition: String?,
    /** CONTRACT §3.2 — 완료 조건이 시작 시점 대비 변화를 묻는가. `RecipeStep`에서 그대로 온다. */
    val needsStartImage: Boolean,
    val elapsedSeconds: Int,
    val baselineImageUri: String?,
    val currentImageUri: String,
    val imagePolicy: JudgmentImagePolicy = JudgmentImagePolicy.AUTOMATIC_CAMERA,
    val cropTarget: ImageCropTarget = ImageCropTarget.LEGACY_BOTTOM_60,
    val requestedAtMs: Long = System.currentTimeMillis()
)

data class JudgmentResult(
    val requestId: String,
    val cookingSessionId: String,
    val stepOrder: Int,
    val verdict: JudgmentVerdict,
    val reasonCode: ReasonCode,
    val roundTripMs: Long,
    val vlmLatencyMs: Long? = null,
    val timing: JudgmentTimingBreakdown? = null,
    val requestedAtMs: Long = 0L,
    val respondedAtMs: Long = System.currentTimeMillis()
)

data class JudgmentTimingBreakdown(
    val totalMs: Long,
    val imagePreparationMs: Long,
    val httpRoundTripMs: Long,
    val responseParseMs: Long,
    val retryBackoffMs: Long,
    val serverHandlerMs: Long,
    val serverValidationMs: Long,
    val currentCropMs: Long,
    val startCropMs: Long,
    val cropTotalMs: Long,
    val localModelLoadMs: Long,
    val localPreprocessMs: Long,
    val localInferenceMs: Long,
    val localPostprocessMs: Long,
    val localEncodeMs: Long,
    val serverCropTotalMs: Long,
    val judgeSetupMs: Long,
    val promptBuildMs: Long,
    val vlmWallMs: Long,
    val serverOtherMs: Long,
    val transportAndFrameworkMs: Long,
    val currentCropMode: String,
    val startCropMode: String? = null,
    val currentDetectionCount: Int = 0,
    val startDetectionCount: Int? = null,
    val serverCurrentCropMode: String? = null
)

data class CropPreviewTiming(
    val totalMs: Long,
    val imagePreparationMs: Long,
    val modelLoadMs: Long,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long,
    val encodeMs: Long
)

data class CropPreviewResult(
    val sourceImageUri: String,
    val croppedImageBase64: String,
    val cropMode: String,
    val cropTarget: ImageCropTarget,
    val detectionCount: Int,
    val width: Int,
    val height: Int,
    val timing: CropPreviewTiming
)

sealed interface CropPreviewOutcome {
    data class Success(val result: CropPreviewResult) : CropPreviewOutcome
    data class Failure(val message: String) : CropPreviewOutcome
}

sealed interface JudgmentOutcome {
    data class Success(val result: JudgmentResult) : JudgmentOutcome
    data class Failure(
        val requestId: String,
        val message: String,
        val retryable: Boolean,
        val requestedAtMs: Long = 0L,
        val respondedAtMs: Long = System.currentTimeMillis()
    ) : JudgmentOutcome
}

sealed interface JudgmentGatewayState {
    data object Idle : JudgmentGatewayState
    data object Judging : JudgmentGatewayState
    data object Released : JudgmentGatewayState
}

interface JudgmentGateway {
    val state: StateFlow<JudgmentGatewayState>

    suspend fun judge(request: JudgmentRequest): JudgmentOutcome

    suspend fun release()
}
