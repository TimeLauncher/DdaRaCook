package com.example.myapplication.camera

import kotlinx.coroutines.flow.StateFlow

data class CaptureRequest(
    val requestId: String,
    val cookingSessionId: String,
    val stepOrder: Int,
    val purpose: CapturePurpose,
    val burstDurationMs: Long = 3_000L,
    val streamTimeoutMs: Long = 5_000L,
    val captureTimeoutMs: Long = 10_000L
)

enum class CapturePurpose {
    BASELINE,
    INSPECTION,
    MANUAL_CHECK
}

data class CaptureArtifact(
    val requestId: String,
    val imageUri: String,
    val capturedAtEpochMs: Long,
    val streamStartedAtElapsedMs: Long,
    val firstFrameAtElapsedMs: Long?,
    val captureCompletedAtElapsedMs: Long,
    val streamStoppedAtElapsedMs: Long,
    val width: Int?,
    val height: Int?,
    val byteSize: Long
) {
    val streamStartupLatencyMs: Long?
        get() = firstFrameAtElapsedMs?.minus(streamStartedAtElapsedMs)

    val totalCaptureLatencyMs: Long
        get() = captureCompletedAtElapsedMs - streamStartedAtElapsedMs
}

enum class CaptureFailureKind {
    INVALID_REQUEST,
    NOT_REGISTERED,
    BUSY,
    CANCELLED,
    DEVICE_NOT_FOUND,
    DEVICE_DISCONNECTED,
    SESSION_START_FAILED,
    STREAM_START_FAILED,
    STREAM_TIMEOUT,
    PHOTO_CAPTURE_FAILED,
    CAPTURE_TIMEOUT,
    FILE_SAVE_FAILED,
    STREAM_STOP_FAILED,
    PERMISSION_DENIED,
    NOT_READY,
    UNKNOWN
}

sealed interface CaptureOutcome {
    data class Success(val artifact: CaptureArtifact) : CaptureOutcome

    data class Failure(
        val requestId: String,
        val kind: CaptureFailureKind,
        val retryable: Boolean,
        val userMessage: String,
        val debugMessage: String? = null
    ) : CaptureOutcome
}

sealed interface WearableCameraState {
    data object NotStarted : WearableCameraState
    data object Registering : WearableCameraState
    data object PermissionRequired : WearableCameraState
    data object Searching : WearableCameraState
    data object Connecting : WearableCameraState
    data object Ready : WearableCameraState
    data object Capturing : WearableCameraState
    data object Busy : WearableCameraState
    data object Disconnected : WearableCameraState
    data class Error(val message: String) : WearableCameraState
    data object Released : WearableCameraState
}

sealed interface ArtifactDeletionOutcome {
    data object Deleted : ArtifactDeletionOutcome
    data object NotFound : ArtifactDeletionOutcome
    data class Failure(val debugMessage: String) : ArtifactDeletionOutcome
}

interface WearableCameraGateway {
    val state: StateFlow<WearableCameraState>

    /** True only for the app's SDK-free debug implementation. */
    val isFake: Boolean

    suspend fun capture(request: CaptureRequest): CaptureOutcome

    /** Deletes a cache artifact owned by this gateway after judgment or user discard. */
    suspend fun deleteArtifact(imageUri: String): ArtifactDeletionOutcome

    suspend fun release()
}

internal fun CaptureRequest.validationError(): String? = when {
    requestId.isBlank() -> "requestId must not be blank"
    cookingSessionId.isBlank() -> "cookingSessionId must not be blank"
    stepOrder < 1 -> "stepOrder must be at least 1"
    burstDurationMs <= 0L -> "burstDurationMs must be positive"
    streamTimeoutMs <= 0L -> "streamTimeoutMs must be positive"
    captureTimeoutMs <= 0L -> "captureTimeoutMs must be positive"
    else -> null
}

internal val CaptureFailureKind.retryable: Boolean
    get() = when (this) {
        CaptureFailureKind.NOT_REGISTERED,
        CaptureFailureKind.DEVICE_NOT_FOUND,
        CaptureFailureKind.DEVICE_DISCONNECTED,
        CaptureFailureKind.SESSION_START_FAILED,
        CaptureFailureKind.STREAM_START_FAILED,
        CaptureFailureKind.STREAM_TIMEOUT,
        CaptureFailureKind.PHOTO_CAPTURE_FAILED,
        CaptureFailureKind.CAPTURE_TIMEOUT,
        CaptureFailureKind.STREAM_STOP_FAILED,
        CaptureFailureKind.PERMISSION_DENIED,
        CaptureFailureKind.NOT_READY,
        CaptureFailureKind.BUSY -> true
        CaptureFailureKind.INVALID_REQUEST,
        CaptureFailureKind.FILE_SAVE_FAILED,
        CaptureFailureKind.CANCELLED,
        CaptureFailureKind.UNKNOWN -> false
    }

internal val CaptureFailureKind.defaultUserMessage: String
    get() = when (this) {
        CaptureFailureKind.INVALID_REQUEST -> "촬영 요청이 올바르지 않습니다."
        CaptureFailureKind.NOT_REGISTERED -> "Meta AI 앱에서 안경 연결을 등록해주세요."
        CaptureFailureKind.DEVICE_NOT_FOUND -> "사용 가능한 안경을 찾지 못했습니다."
        CaptureFailureKind.DEVICE_DISCONNECTED -> "안경 연결이 끊겼습니다."
        CaptureFailureKind.SESSION_START_FAILED -> "안경 세션을 시작하지 못했습니다."
        CaptureFailureKind.STREAM_START_FAILED -> "카메라 스트림을 시작하지 못했습니다."
        CaptureFailureKind.STREAM_TIMEOUT -> "카메라 스트림이 준비되지 않았습니다."
        CaptureFailureKind.PHOTO_CAPTURE_FAILED -> "사진을 촬영하지 못했습니다."
        CaptureFailureKind.CAPTURE_TIMEOUT -> "사진 촬영 시간이 초과되었습니다."
        CaptureFailureKind.FILE_SAVE_FAILED -> "촬영한 사진을 저장하지 못했습니다."
        CaptureFailureKind.STREAM_STOP_FAILED -> "카메라 스트림을 정상 종료하지 못했습니다."
        CaptureFailureKind.PERMISSION_DENIED -> "Meta AI 앱에서 카메라 권한을 허용해주세요."
        CaptureFailureKind.NOT_READY -> "안경 카메라가 아직 준비되지 않았습니다."
        CaptureFailureKind.BUSY -> "이미 다른 촬영을 진행 중입니다."
        CaptureFailureKind.CANCELLED -> "촬영이 취소되었습니다."
        CaptureFailureKind.UNKNOWN -> "촬영 중 알 수 없는 오류가 발생했습니다."
    }
