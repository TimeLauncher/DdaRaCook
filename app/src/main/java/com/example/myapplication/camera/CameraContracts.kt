package com.example.myapplication.camera

import kotlinx.coroutines.flow.StateFlow

data class CaptureRequest(
    val requestId: String,
    val cookingSessionId: String,
    val stepOrder: Int,
    val purpose: CapturePurpose,
    val burstDurationMs: Long = 3_000L,
    val streamTimeoutMs: Long = 5_000L,
    val captureTimeoutMs: Long = 5_000L
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
    BUSY,
    CANCELLED,
    DEVICE_DISCONNECTED,
    STREAM_TIMEOUT,
    CAPTURE_TIMEOUT,
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

interface WearableCameraGateway {
    val state: StateFlow<WearableCameraState>

    suspend fun capture(request: CaptureRequest): CaptureOutcome

    suspend fun release()
}
