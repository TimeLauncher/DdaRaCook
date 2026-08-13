package com.example.myapplication.camera

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class FakeWearableCameraGateway(
    private val context: Context
) : WearableCameraGateway {
    private val mutableState = MutableStateFlow<WearableCameraState>(WearableCameraState.NotStarted)
    override val state: StateFlow<WearableCameraState> = mutableState.asStateFlow()
    override val isFake: Boolean = true

    private val captureMutex = Mutex()
    private var behavior: FakeCaptureBehavior = FakeCaptureBehavior.Success()

    fun setState(state: WearableCameraState) {
        mutableState.value = state
    }

    fun setBehavior(behavior: FakeCaptureBehavior) {
        this.behavior = behavior
    }

    override suspend fun capture(request: CaptureRequest): CaptureOutcome {
        if (!captureMutex.tryLock()) {
            return CaptureOutcome.Failure(
                requestId = request.requestId,
                kind = CaptureFailureKind.BUSY,
                retryable = true,
                userMessage = "이미 촬영 중입니다.",
                debugMessage = "Fake gateway BUSY"
            )
        }

        return try {
            when (val currentState = mutableState.value) {
                WearableCameraState.Ready,
                WearableCameraState.Busy,
                WearableCameraState.Capturing -> Unit
                WearableCameraState.Disconnected -> {
                    return CaptureOutcome.Failure(
                        requestId = request.requestId,
                        kind = CaptureFailureKind.DEVICE_DISCONNECTED,
                        retryable = true,
                        userMessage = "안경 연결이 끊겼습니다.",
                        debugMessage = "Disconnected before capture"
                    )
                }
                else -> {
                    return CaptureOutcome.Failure(
                        requestId = request.requestId,
                        kind = CaptureFailureKind.NOT_READY,
                        retryable = true,
                        userMessage = "카메라가 아직 준비되지 않았습니다.",
                        debugMessage = "State=$currentState"
                    )
                }
            }

            mutableState.value = WearableCameraState.Capturing
            val startedAt = System.currentTimeMillis()

            when (val selected = behavior) {
                is FakeCaptureBehavior.Success -> {
                    delay(selected.delayMs)
                    val firstFrameAt = startedAt + selected.firstFrameDelayMs
                    val completedAt = System.currentTimeMillis()
                    val file = ensureSampleImageFile()
                    mutableState.value = WearableCameraState.Ready
                    CaptureOutcome.Success(
                        artifact = CaptureArtifact(
                            requestId = request.requestId,
                            imageUri = Uri.fromFile(file).toString(),
                            capturedAtEpochMs = completedAt,
                            streamStartedAtElapsedMs = startedAt,
                            firstFrameAtElapsedMs = firstFrameAt,
                            captureCompletedAtElapsedMs = completedAt,
                            streamStoppedAtElapsedMs = completedAt + 50L,
                            width = 1280,
                            height = 720,
                            byteSize = file.length()
                        )
                    )
                }

                is FakeCaptureBehavior.Failure -> {
                    delay(selected.delayMs)
                    mutableState.value = if (selected.kind == CaptureFailureKind.DEVICE_DISCONNECTED) {
                        WearableCameraState.Disconnected
                    } else {
                        WearableCameraState.Ready
                    }
                    CaptureOutcome.Failure(
                        requestId = request.requestId,
                        kind = selected.kind,
                        retryable = selected.retryable,
                        userMessage = selected.userMessage,
                        debugMessage = selected.debugMessage
                    )
                }

                FakeCaptureBehavior.Disconnect -> {
                    delay(500L)
                    mutableState.value = WearableCameraState.Disconnected
                    CaptureOutcome.Failure(
                        requestId = request.requestId,
                        kind = CaptureFailureKind.DEVICE_DISCONNECTED,
                        retryable = true,
                        userMessage = "촬영 중 연결이 끊겼습니다.",
                        debugMessage = "Fake disconnect during capture"
                    )
                }
            }
        } catch (_: CancellationException) {
            mutableState.value = WearableCameraState.Ready
            CaptureOutcome.Failure(
                requestId = request.requestId,
                kind = CaptureFailureKind.CANCELLED,
                retryable = true,
                userMessage = "촬영이 취소되었습니다.",
                debugMessage = "Capture coroutine cancelled"
            )
        } finally {
            if (mutableState.value == WearableCameraState.Capturing) {
                mutableState.value = WearableCameraState.Ready
            }
            captureMutex.unlock()
        }
    }

    override suspend fun release() {
        mutableState.value = WearableCameraState.Released
    }

    override suspend fun deleteArtifact(imageUri: String): ArtifactDeletionOutcome {
        val uri = runCatching { Uri.parse(imageUri) }.getOrNull()
            ?: return ArtifactDeletionOutcome.Failure("Invalid fake artifact URI")
        if (uri.scheme != "file" || uri.path.isNullOrBlank()) {
            return ArtifactDeletionOutcome.Failure("Fake artifact is not a file URI")
        }
        val file = File(requireNotNull(uri.path))
        return when {
            !file.exists() -> ArtifactDeletionOutcome.NotFound
            file.delete() -> ArtifactDeletionOutcome.Deleted
            else -> ArtifactDeletionOutcome.Failure("Fake artifact could not be deleted")
        }
    }

    private suspend fun ensureSampleImageFile(): File {
        val target = File(context.cacheDir, "fake-capture-${UUID.randomUUID()}.jpg")
        if (!target.exists()) {
            FileOutputStream(target).use { out ->
                out.write(SAMPLE_JPEG_BYTES)
            }
        }
        return target
    }
}

sealed interface FakeCaptureBehavior {
    data class Success(
        val delayMs: Long = 1_500L,
        val firstFrameDelayMs: Long = 700L
    ) : FakeCaptureBehavior

    data class Failure(
        val kind: CaptureFailureKind,
        val retryable: Boolean,
        val userMessage: String,
        val debugMessage: String,
        val delayMs: Long = 1_000L
    ) : FakeCaptureBehavior

    data object Disconnect : FakeCaptureBehavior
}

private val SAMPLE_JPEG_BYTES = byteArrayOf(
    -1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1, 1, 1, 0, 72, 0, 72, 0, 0,
    -1, -37, 0, 67, 0, 8, 6, 6, 7, 6, 5, 8, 7, 7, 7, 9, 9, 8, 10, 12, 20, 13,
    12, 11, 11, 12, 25, 18, 19, 15, 20, 29, 26, 31, 30, 29, 26, 28, 28, 32, 36,
    46, 39, 32, 34, 44, 35, 28, 28, 40, 55, 41, 44, 48, 49, 52, 52, 52, 31, 39,
    57, 61, 56, 50, 60, 46, 51, 52, 50, -1, -64, 0, 17, 8, 0, 1, 0, 1, 3, 1,
    34, 0, 2, 17, 1, 3, 17, 1, -1, -60, 0, 20, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 8, -1, -60, 0, 20, 16, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, -1, -38, 0, 12, 3, 1, 0, 2, 17, 3, 17, 0, 63, 0, -1, -39
)
