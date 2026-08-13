package com.example.myapplication.camera

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.removeCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Real Meta DAT implementation of the app camera boundary.
 *
 * The DAT device session is kept for the cooking session. Camera/stream capability is attached only
 * for one capture and is always removed in cleanup. A lifecycle timeout quarantines the session so
 * the scheduler cannot repeat against a potentially wedged glasses-side capability.
 */
class DatWearableCameraGateway(
    private val application: Application
) : WearableCameraGateway {
    companion object {
        private const val TAG = "DatCameraGateway"
        private const val FRAME_RATE = 24
        private const val CLEANUP_TIMEOUT_MS = 5_000L
    }

    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var deviceSelector: AutoDeviceSelector? = null
    private val photoStore = DatPhotoStore(application)
    private val captureMutex = Mutex()
    private val activeCaptureJob = AtomicReference<Job?>(null)
    private val initialized = AtomicBoolean(false)
    private val sessionStarting = AtomicBoolean(false)

    private val mutableState = MutableStateFlow<WearableCameraState>(WearableCameraState.NotStarted)
    override val state: StateFlow<WearableCameraState> = mutableState.asStateFlow()
    override val isFake: Boolean = false

    private var hasActiveDevice = false
    private var session: DeviceSession? = null
    private var registrationJob: Job? = null
    private var selectorJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        Wearables.initialize(application)
        val initializedSelector = AutoDeviceSelector()
        deviceSelector = initializedSelector
        registrationJob = gatewayScope.launch {
            Wearables.registrationState.collect { registration ->
                when (registration) {
                    RegistrationState.REGISTERED -> {
                        if (session == null) mutableState.value = WearableCameraState.Searching
                        ensureSession()
                    }
                    RegistrationState.REGISTERING -> {
                        mutableState.value = WearableCameraState.Registering
                    }
                    else -> {
                        stopSession()
                        mutableState.value = WearableCameraState.NotStarted
                    }
                }
            }
        }
        selectorJob = gatewayScope.launch {
            initializedSelector.activeDeviceFlow().collect { device ->
                hasActiveDevice = device != null
                if (device == null) {
                    if (session == null && isRegistered()) {
                        mutableState.value = WearableCameraState.Searching
                    }
                } else {
                    ensureSession()
                }
            }
        }
    }

    fun startRegistration(activity: Activity) {
        if (!initialized.get()) {
            mutableState.value = WearableCameraState.Error("Android 권한을 먼저 허용해주세요.")
            return
        }
        mutableState.value = WearableCameraState.Registering
        Wearables.startRegistration(activity)
    }

    fun prepareSession() {
        if (!initialized.get()) {
            mutableState.value = WearableCameraState.Error("Android 권한을 먼저 허용해주세요.")
            return
        }
        gatewayScope.launch { ensureSession() }
    }

    fun onWearableCameraPermissionResult(granted: Boolean) {
        if (granted && session?.state?.value == DeviceSessionState.STARTED) {
            mutableState.value = WearableCameraState.Ready
        } else {
            mutableState.value = WearableCameraState.PermissionRequired
        }
    }

    override suspend fun capture(request: CaptureRequest): CaptureOutcome {
        request.validationError()?.let { reason ->
            return failure(request, CaptureFailureKind.INVALID_REQUEST, reason)
        }
        availabilityFailure(request)?.let { return it }
        if (!captureMutex.tryLock()) {
            return failure(request, CaptureFailureKind.BUSY, "Another capture is active")
        }

        val job = checkNotNull(currentCoroutineContext()[Job]) { "Capture coroutine has no Job" }
        activeCaptureJob.set(job)
        return try {
            performCapture(request)
        } finally {
            activeCaptureJob.compareAndSet(job, null)
            captureMutex.unlock()
        }
    }

    override suspend fun deleteArtifact(imageUri: String): ArtifactDeletionOutcome =
        photoStore.delete(imageUri)

    override suspend fun release() {
        mutableState.value = WearableCameraState.Released
        val active = activeCaptureJob.getAndSet(null)
        val current = currentCoroutineContext()[Job]
        if (active != null && active !== current) active.cancelAndJoin()
        withContext(NonCancellable) { stopSession() }
        registrationJob?.cancelAndJoin()
        selectorJob?.cancelAndJoin()
        deviceSelector = null
        gatewayScope.cancel()
        mutableState.value = WearableCameraState.Released
    }

    private suspend fun ensureSession() {
        if (!initialized.get() || !isRegistered()) return
        val initializedSelector = deviceSelector ?: return
        val existing = session
        if (existing != null) {
            if (existing.state.value == DeviceSessionState.STARTED) checkWearablePermission()
            return
        }
        if (!hasActiveDevice) {
            mutableState.value = WearableCameraState.Searching
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) return

        mutableState.value = WearableCameraState.Connecting
        try {
            var createdSession: DeviceSession? = null
            var failureDescription: String? = null
            Wearables.createSession(initializedSelector)
                .onSuccess { createdSession = it }
                .onFailure { error, _ -> failureDescription = error.description }
            val created = createdSession
            if (created == null) {
                mutableState.value = WearableCameraState.Error(
                    failureDescription ?: "안경 세션을 만들지 못했습니다."
                )
                return
            }
            session = created
            observeSession(created)
            created.start()
        } finally {
            sessionStarting.set(false)
        }
    }

    private fun observeSession(observedSession: DeviceSession) {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        sessionStateJob = gatewayScope.launch {
            observedSession.state.collect { sessionState ->
                Log.i(TAG, "sessionState=$sessionState")
                when (sessionState) {
                    DeviceSessionState.STARTED -> checkWearablePermission()
                    DeviceSessionState.STARTING -> mutableState.value = WearableCameraState.Connecting
                    DeviceSessionState.STOPPING -> Unit
                    DeviceSessionState.STOPPED -> {
                        if (session === observedSession) session = null
                        mutableState.value = WearableCameraState.Disconnected
                    }
                    else -> Unit
                }
            }
        }
        sessionErrorJob = gatewayScope.launch {
            observedSession.errors.collect { error ->
                Log.e(TAG, "sessionError=${error.description}")
                mutableState.value = WearableCameraState.Error(error.description)
            }
        }
    }

    private suspend fun checkWearablePermission() {
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onSuccess { status ->
                mutableState.value = if (status == PermissionStatus.Granted) {
                    WearableCameraState.Ready
                } else {
                    WearableCameraState.PermissionRequired
                }
            }
            .onFailure { error, _ ->
                mutableState.value = WearableCameraState.Error(error.description)
            }
    }

    private suspend fun stopSession() {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        sessionStateJob = null
        sessionErrorJob = null
        session?.stop()
        session = null
    }

    private suspend fun performCapture(request: CaptureRequest): CaptureOutcome = coroutineScope {
        val currentSession = session
        if (currentSession == null || currentSession.state.value != DeviceSessionState.STARTED) {
            return@coroutineScope failure(
                request,
                CaptureFailureKind.NOT_READY,
                "Device session is not STARTED"
            )
        }

        val startedAt = SystemClock.elapsedRealtime()
        var firstFrameAt: Long? = null
        var camera: Camera? = null
        var stream: Stream? = null
        var videoJob: Job? = null
        var streamStateJob: Job? = null
        var streamErrorJob: Job? = null
        var terminalSignal: CompletableDeferred<Unit>? = null
        var outcome: CaptureOutcome? = null
        var quarantineReason: String? = null

        Log.i(
            TAG,
            "requestId=${request.requestId} purpose=${request.purpose} " +
                "session=${currentSession.state.value} " +
                "startedAt=$startedAt"
        )
        try {
            try {
                DatStreamingService.start(application)
            } catch (error: IllegalStateException) {
                outcome = failure(
                    request,
                    CaptureFailureKind.STREAM_START_FAILED,
                    error.message ?: "Foreground service start rejected"
                )
            }

            if (outcome == null) {
                var addFailure: String? = null
                currentSession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = FRAME_RATE,
                        compressVideo = true
                    )
                ).onSuccess { camera = it }
                    .onFailure { error, _ -> addFailure = error.description }
                if (camera == null) {
                    outcome = failure(
                        request,
                        CaptureFailureKind.STREAM_START_FAILED,
                        addFailure ?: "Failed to attach camera capability"
                    )
                }
            }

            if (outcome == null) {
                val attachedCamera = checkNotNull(camera)
                val attachedStream = attachedCamera.stream
                stream = attachedStream
                val firstFrameSignal = CompletableDeferred<Long>()
                val errorSignal = CompletableDeferred<String>()
                terminalSignal = CompletableDeferred()

                videoJob = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    attachedStream.videoStream.collect { frame ->
                        if (!frame.isCodecConfig && !firstFrameSignal.isCompleted) {
                            firstFrameSignal.complete(SystemClock.elapsedRealtime())
                        }
                    }
                }
                streamStateJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    attachedStream.state.collect { value ->
                        Log.i(TAG, "requestId=${request.requestId} streamState=$value")
                        if (
                            (value == StreamState.STOPPED || value == StreamState.CLOSED) &&
                            terminalSignal?.isCompleted == false
                        ) {
                            terminalSignal?.complete(Unit)
                        }
                    }
                }
                streamErrorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    attachedStream.errorStream.collect { error ->
                        Log.e(TAG, "requestId=${request.requestId} streamError=${error.description}")
                        if (!errorSignal.isCompleted) errorSignal.complete(error.description)
                    }
                }

                var startFailure: String? = null
                attachedStream.start()
                    .onFailure { error, _ -> startFailure = error.description }
                if (startFailure != null) {
                    outcome = failure(
                        request,
                        CaptureFailureKind.STREAM_START_FAILED,
                        checkNotNull(startFailure)
                    )
                } else {
                    mutableState.value = WearableCameraState.Connecting
                    val readiness = try {
                        withTimeout(request.streamTimeoutMs) {
                            select<StreamReadiness> {
                                firstFrameSignal.onAwait { StreamReadiness.Ready(it) }
                                errorSignal.onAwait { StreamReadiness.Failed(it) }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        StreamReadiness.TimedOut
                    }
                    when (readiness) {
                        is StreamReadiness.Ready -> {
                            firstFrameAt = readiness.elapsedRealtimeMs
                            mutableState.value = WearableCameraState.Capturing
                            val capturedAtEpochMs = System.currentTimeMillis()
                            val photoResult = capturePhoto(attachedStream, request.captureTimeoutMs)
                            when (photoResult) {
                                is PhotoCaptureResult.Failure -> {
                                    if (photoResult.timedOut) {
                                        quarantineReason = photoResult.message
                                    }
                                    outcome = failure(
                                        request,
                                        if (photoResult.timedOut) {
                                            CaptureFailureKind.CAPTURE_TIMEOUT
                                        } else {
                                            CaptureFailureKind.PHOTO_CAPTURE_FAILED
                                        },
                                        photoResult.message
                                    )
                                }
                                is PhotoCaptureResult.Success -> {
                                    val bitmap = withContext(Dispatchers.Default) {
                                        decodePhoto(photoResult.photo)
                                    }
                                    if (bitmap == null) {
                                        outcome = failure(
                                            request,
                                            CaptureFailureKind.PHOTO_CAPTURE_FAILED,
                                            "Captured photo could not be decoded"
                                        )
                                    } else {
                                        outcome = when (
                                            val saved = photoStore.save(
                                                request.requestId,
                                                capturedAtEpochMs,
                                                bitmap
                                            )
                                        ) {
                                            is PhotoStoreResult.Failure -> failure(
                                                request,
                                                CaptureFailureKind.FILE_SAVE_FAILED,
                                                saved.message
                                            )
                                            is PhotoStoreResult.Success -> CaptureOutcome.Success(
                                                CaptureArtifact(
                                                    requestId = request.requestId,
                                                    imageUri = saved.value.imageUri,
                                                    capturedAtEpochMs = capturedAtEpochMs,
                                                    streamStartedAtElapsedMs = startedAt,
                                                    firstFrameAtElapsedMs = firstFrameAt,
                                                    captureCompletedAtElapsedMs = SystemClock.elapsedRealtime(),
                                                    streamStoppedAtElapsedMs = 0L,
                                                    width = saved.value.width,
                                                    height = saved.value.height,
                                                    byteSize = saved.value.byteSize
                                                )
                                            )
                                        }
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                        is StreamReadiness.Failed -> {
                            quarantineReason = readiness.message
                            outcome = failure(
                                request,
                                CaptureFailureKind.STREAM_START_FAILED,
                                readiness.message
                            )
                        }
                        StreamReadiness.TimedOut -> {
                            val timeoutMessage = "No first frame within ${request.streamTimeoutMs}ms"
                            outcome = failure(
                                request,
                                CaptureFailureKind.STREAM_TIMEOUT,
                                timeoutMessage
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            outcome = failure(
                request,
                CaptureFailureKind.UNKNOWN,
                error.message ?: error::class.java.simpleName
            )
        } finally {
            mutableState.value = WearableCameraState.Busy
            val cleanupFailure = cleanupCamera(
                currentSession,
                camera,
                stream,
                terminalSignal,
                listOfNotNull(videoJob, streamStateJob, streamErrorJob)
            )
            val stoppedAt = SystemClock.elapsedRealtime()
            outcome = when {
                cleanupFailure != null && outcome is CaptureOutcome.Success -> failure(
                    request,
                    CaptureFailureKind.STREAM_STOP_FAILED,
                    cleanupFailure
                )
                outcome is CaptureOutcome.Success -> {
                    val success = outcome as CaptureOutcome.Success
                    success.copy(
                        artifact = success.artifact.copy(streamStoppedAtElapsedMs = stoppedAt)
                    )
                }
                else -> outcome
            }
            if (cleanupFailure != null) quarantineReason = cleanupFailure
            if (quarantineReason != null) {
                quarantineSession(checkNotNull(quarantineReason))
            } else if (session?.state?.value == DeviceSessionState.STARTED) {
                mutableState.value = WearableCameraState.Ready
            }
            Log.i(
                TAG,
                "requestId=${request.requestId} purpose=${request.purpose} " +
                    "firstFrameAt=$firstFrameAt stoppedAt=$stoppedAt " +
                    "outcome=${outcome?.javaClass?.simpleName}"
            )
        }
        checkNotNull(outcome) { "Capture completed without an outcome" }
    }

    private suspend fun capturePhoto(
        stream: Stream,
        timeoutMs: Long
    ): PhotoCaptureResult = try {
        withTimeout(timeoutMs) {
            var photo: PhotoData? = null
            var errorMessage: String? = null
            stream.capturePhoto()
                .onSuccess { photo = it }
                .onFailure { error, _ -> errorMessage = error.description }
            photo?.let { PhotoCaptureResult.Success(it) }
                ?: PhotoCaptureResult.Failure(
                    errorMessage ?: "DAT returned no photo data",
                    timedOut = false
                )
        }
    } catch (_: TimeoutCancellationException) {
        PhotoCaptureResult.Failure("Photo capture exceeded ${timeoutMs}ms", timedOut = true)
    }

    private suspend fun cleanupCamera(
        currentSession: DeviceSession,
        camera: Camera?,
        stream: Stream?,
        terminalSignal: CompletableDeferred<Unit>?,
        jobs: List<Job>
    ): String? = withContext(NonCancellable) {
        var cleanupFailure: String? = null
        try {
            withTimeout(CLEANUP_TIMEOUT_MS) {
                if (
                    camera != null &&
                    stream?.state?.value != StreamState.STOPPED &&
                    stream?.state?.value != StreamState.CLOSED
                ) {
                    camera.stop()
                    terminalSignal?.await()
                }
            }
        } catch (_: TimeoutCancellationException) {
            cleanupFailure = "Stream cleanup exceeded ${CLEANUP_TIMEOUT_MS}ms"
        } catch (error: Throwable) {
            cleanupFailure = error.message ?: error::class.java.simpleName
        }

        var removedFromSession = false
        currentSession.removeCamera()
            .onSuccess { removedFromSession = true }
            .onFailure { error, _ ->
                if (isAlreadyDetachedCamera(error.description)) {
                    // camera.stop() can close and detach the capability before removeCamera().
                    // The requested terminal state has already been reached, so this is clean.
                    removedFromSession = true
                    Log.i(TAG, "Camera capability was already detached during stream shutdown")
                } else if (cleanupFailure == null) {
                    cleanupFailure = error.description
                }
            }
        if (!removedFromSession) camera?.close()
        jobs.forEach { it.cancelAndJoin() }
        DatStreamingService.stop(application)
        cleanupFailure
    }

    private fun isAlreadyDetachedCamera(message: String): Boolean =
        message.contains("No capability of this type is attached", ignoreCase = true)

    private fun quarantineSession(reason: String) {
        Log.e(TAG, "Quarantining DAT session after lifecycle failure: $reason")
        mutableState.value = WearableCameraState.Error(
            "카메라 연결을 다시 준비해주세요. 같은 세션에서는 자동 재시도하지 않습니다."
        )
        session?.stop()
    }

    private fun availabilityFailure(request: CaptureRequest): CaptureOutcome.Failure? {
        val kind = when (mutableState.value) {
            WearableCameraState.Ready -> null
            WearableCameraState.NotStarted,
            WearableCameraState.Registering -> CaptureFailureKind.NOT_REGISTERED
            WearableCameraState.PermissionRequired -> CaptureFailureKind.PERMISSION_DENIED
            WearableCameraState.Searching -> CaptureFailureKind.DEVICE_NOT_FOUND
            WearableCameraState.Disconnected -> CaptureFailureKind.DEVICE_DISCONNECTED
            WearableCameraState.Connecting,
            WearableCameraState.Capturing,
            WearableCameraState.Busy -> CaptureFailureKind.NOT_READY
            is WearableCameraState.Error,
            WearableCameraState.Released -> CaptureFailureKind.NOT_READY
        }
        return kind?.let { failure(request, it, "Camera state is ${mutableState.value}") }
    }

    private fun failure(
        request: CaptureRequest,
        kind: CaptureFailureKind,
        debugMessage: String
    ) = CaptureOutcome.Failure(
        requestId = request.requestId,
        kind = kind,
        retryable = kind.retryable,
        userMessage = kind.defaultUserMessage,
        debugMessage = debugMessage
    )

    private fun isRegistered(): Boolean =
        Wearables.registrationState.value == RegistrationState.REGISTERED

    private fun decodePhoto(photo: PhotoData): Bitmap? = when (photo) {
        is PhotoData.Bitmap -> photo.bitmap
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
    }

    private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
        val buffer = data.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap?.recycle()
            return null
        }
        val matrix = exifOrientationMatrix(bytes)
        if (matrix.isIdentity) return bitmap
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "Failed to rotate captured photo", error)
            bitmap
        }
    }

    private fun exifOrientationMatrix(bytes: ByteArray): Matrix {
        val orientation = try {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (error: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
    }

    private sealed interface StreamReadiness {
        data class Ready(val elapsedRealtimeMs: Long) : StreamReadiness
        data class Failed(val message: String) : StreamReadiness
        data object TimedOut : StreamReadiness
    }

    private sealed interface PhotoCaptureResult {
        data class Success(val photo: PhotoData) : PhotoCaptureResult
        data class Failure(val message: String, val timedOut: Boolean) : PhotoCaptureResult
    }
}
