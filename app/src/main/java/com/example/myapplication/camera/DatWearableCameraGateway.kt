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
import com.meta.wearable.dat.camera.types.CaptureError
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
import com.meta.wearable.dat.core.types.DatResult
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
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
        /** docs/10-dat-replacement.md 가 "검증된" 조합으로 적어둔 값. */
        private const val FRAME_RATE = 24
        /**
         * 첫 디코딩 프레임을 기다리는 시간.
         *
         * 실측상 이 프레임은 2초·6초 어느 창에서도 **한 번도 도착하지 않는다**(firstFrameAt 항상
         * null). 기다리는 만큼 셔터만 늦어지므로 0으로 둔다. `capturePhoto()` 는 STREAMING
         * 상태면 유효하고, 사진이 늦게 와도 이제는 슬롯을 다시 열어 받는다.
         */
        private const val STREAM_WARMUP_TIMEOUT_MS = 0L
        private const val PHOTO_CAPTURE_RETRY_DELAY_MS = 1_000L
        private const val PHOTO_CAPTURE_MAX_ATTEMPTS = 2
        private const val CLEANUP_TIMEOUT_MS = 5_000L

        /**
         * 셔터를 누른 뒤, SDK 가 포기한 다음에도 사진이 오기를 기다리는 시간.
         *
         * 실측 도착이 셔터로부터 약 17초였고 SDK 는 10초에 포기하므로 7초 남짓이면 되지만,
         * 링크가 느린 날을 감안해 여유를 둔다. 이 시간에도 안 오면 그 촬영은 실패다.
         */
        private const val IN_FLIGHT_PHOTO_WAIT_MS = 15_000L
    }

    /** 진단 전용 시계. 셔터 시각과 EXIF 촬영 시각을 같은 눈금으로 보기 위한 것이다. */
    private val photoDiagnosticClock =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    /** 진단: 한 번의 촬영 동안 도착한 영상 프레임 수. */
    private var videoFrameCount = 0

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
                // ⚠️ videoQuality 를 내리면 사진이 **더 느리게** 온다 (실측 2026-08-27):
                //   HIGH · 2fps → 12.1~12.5초 · 99~104KB
                //   LOW  · 2fps → 22.6초      · 125KB
                // 링크 대역 경합이 병목이라는 가설은 이 결과로 기각됐다. 사진 전송이 영상
                // 스트림의 처리량에 **올라타는** 것으로 보인다 — 영상을 줄이면 사진도 느려진다.
                // 셔터→사진 도착 실측 (2026-08-27):
                //   HIGH · 2fps  · 무압축 → 12.1~12.5초   ← 기준선
                //   LOW  · 2fps  · 무압축 → 22.6초        (기각)
                //   HIGH · 2fps  · 압축   → 23.6~24.7초   (기각)
                //   HIGH · 24fps · 무압축 → 17.4~20.9초     (기각)
                // 늘려도 줄여도 느려진다. 공개 API 세 손잡이를 모두 흔들어 본 결과
                // **현재 조합이 최적**이다. 근거 없이 바꾸지 말 것.
                // ⚠️ 이 파라미터들을 흔들어도 사진 지연은 안 줄었다(실측 2026-08-27).
                // 원인은 스트림 설정이 아니라 **링크**였다 — 폰 Wi-Fi 가 꺼져 있어 카메라
                // 데이터가 블루투스로 폴백되고 있었다. 그래서 영상 프레임이 0장이고
                // 사진은 6~10KB/s 로 기어갔다. 설정은 SDK 기본 조합으로 되돌려 둔다.
                // docs/10-dat-replacement.md 는 "검증된 MEDIUM · 24 FPS" 로 스트림을 연다고
                // 적어두었는데 코드는 HIGH · 2 FPS 였다. 설계는 "첫 프레임 수신 뒤 capturePhoto()"
                // 인데 프레임이 0장이라 그 전제가 무너져 있다. 문서의 조합으로 되돌려 확인한다.
                currentSession.addCamera(
                    StreamConfiguration(
                        videoQuality = VideoQuality.MEDIUM,
                        frameRate = FRAME_RATE
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
                val streamingSignal = CompletableDeferred<Unit>()
                val errorSignal = CompletableDeferred<String>()
                terminalSignal = CompletableDeferred()

                videoFrameCount = 0
                videoJob = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    attachedStream.videoStream.collect { frame ->
                        // 진단: 프레임이 정말 한 장도 안 오는지, 아니면 오는데 전부
                        // codecConfig 라서 걸러지는지 가른다. 앞 세 장만 자세히 남긴다.
                        videoFrameCount += 1
                        if (videoFrameCount <= 3) {
                            Log.i(
                                TAG,
                                "videoFrame #$videoFrameCount ${frame.width}x${frame.height} " +
                                    "compressed=${frame.isCompressed} " +
                                    "codecConfig=${frame.isCodecConfig} " +
                                    "bytes=${frame.buffer.remaining()}"
                            )
                        }
                        if (!frame.isCodecConfig && !firstFrameSignal.isCompleted) {
                            firstFrameSignal.complete(SystemClock.elapsedRealtime())
                        }
                    }
                }
                streamStateJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    attachedStream.state.collect { value ->
                        Log.i(TAG, "requestId=${request.requestId} streamState=$value")
                        if (value == StreamState.STREAMING && !streamingSignal.isCompleted) {
                            streamingSignal.complete(Unit)
                        }
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
                                streamingSignal.onAwait { StreamReadiness.Ready }
                                errorSignal.onAwait { StreamReadiness.Failed(it) }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        StreamReadiness.TimedOut
                    }
                    when (readiness) {
                        StreamReadiness.Ready -> {
                            // DAT defines STREAMING as the point where frames are flowing. Photo
                            // capture is valid in this state. Give the decoded frame collector a
                            // short warm-up window so capturePhoto() does not race stream startup.
                            firstFrameAt = if (STREAM_WARMUP_TIMEOUT_MS <= 0L) {
                                firstFrameSignal.takeIf { it.isCompleted }?.getCompleted()
                            } else {
                                try {
                                    withTimeout(STREAM_WARMUP_TIMEOUT_MS) {
                                        firstFrameSignal.await()
                                    }
                                } catch (_: TimeoutCancellationException) {
                                    null
                                }
                            }
                            mutableState.value = WearableCameraState.Capturing
                            val capturedAtEpochMs = System.currentTimeMillis()
                            val photoResult = capturePhotoWithRetry(
                                stream = attachedStream,
                                timeoutMs = request.captureTimeoutMs,
                                requestId = request.requestId
                            )
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
                                            .also {
                                                if (it is PhotoStoreResult.Success) {
                                                    Log.i(
                                                        TAG,
                                                        "saved photo ${it.value.width}x${it.value.height} " +
                                                            "bytes=${it.value.byteSize}"
                                                    )
                                                }
                                            }
                                        ) {
                                            is PhotoStoreResult.Failure -> failure(
                                                request,
                                                CaptureFailureKind.FILE_SAVE_FAILED,
                                                saved.message
                                            )
                                            is PhotoStoreResult.Success -> CaptureOutcome.Success(
                                                // 진단: 스트림 설정이 사진 해상도에 영향을 주는지 본다.
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
                            val timeoutMessage =
                                "Stream did not reach STREAMING within ${request.streamTimeoutMs}ms"
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
                    "videoFrames=$videoFrameCount firstFrameAt=$firstFrameAt stoppedAt=$stoppedAt " +
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

    private suspend fun capturePhotoWithRetry(
        stream: Stream,
        timeoutMs: Long,
        requestId: String
    ): PhotoCaptureResult {
        val shutterAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "requestId=$requestId shutter issuedAt=${photoDiagnosticClock.format(java.util.Date())}"
        )

        // 셔터는 여기 한 번뿐이다.
        val first = capturePhoto(stream, timeoutMs)
        if (first is PhotoCaptureResult.Success) {
            logDelivered(requestId, "shutter", shutterAt)
            return first
        }
        val firstFailure = first as PhotoCaptureResult.Failure
        Log.w(TAG, "requestId=$requestId shutter call failed=${firstFailure.message}")

        // SDK 의 PHOTO_CAPTURE_TIMEOUT_MS(실측 10초)가 실제 사진 도착(실측 17초)보다 짧아서,
        // 위 호출은 사진이 **오는 중**인데도 실패로 끝난다. 그때 SDK 는 대기 슬롯
        // (StreamImpl.photoCaptureRequest)을 비우고, 뒤늦게 도착한 사진은 받을 곳이 없어 버려진다.
        //
        // 예전에는 capturePhoto() 를 한 번 더 불러 슬롯을 다시 열었는데, 그 호출이 안경에
        // 촬영 명령까지 보내서 셔터가 두 번 울렸다(그리고 그 두 번째 사진은 늘 버려졌다).
        // 여기서는 촬영 명령 없이 **슬롯만** 다시 열어 오는 중인 사진을 받는다.
        if (stream.state.value == StreamState.STREAMING) {
            when (val inFlight = awaitInFlightPhoto(stream, requestId)) {
                is PhotoCaptureResult.Success -> {
                    logDelivered(requestId, "in-flight", shutterAt)
                    return inFlight
                }
                is PhotoCaptureResult.Failure -> return inFlight
                // 슬롯에 접근할 수 없는 SDK 라면 예전처럼 재촬영으로 되돌아간다.
                null -> {
                    Log.w(TAG, "requestId=$requestId in-flight 대기 불가 · 재촬영으로 폴백")
                    delay(PHOTO_CAPTURE_RETRY_DELAY_MS)
                    val retry = capturePhoto(stream, timeoutMs)
                    if (retry is PhotoCaptureResult.Success) {
                        logDelivered(requestId, "retry-shutter", shutterAt)
                    }
                    return retry
                }
            }
        }
        return firstFailure
    }

    private fun logDelivered(requestId: String, via: String, shutterAt: Long) {
        Log.i(
            TAG,
            "requestId=$requestId photo delivered via=$via " +
                "msSinceShutter=${SystemClock.elapsedRealtime() - shutterAt} " +
                "at=${photoDiagnosticClock.format(java.util.Date())}"
        )
    }

    /**
     * 촬영 명령 없이 **오는 중인 사진**을 받는다.
     *
     * SDK 의 사진 배달 경로는 `AtomicReference<PhotoCaptureRequest>` 슬롯 하나가 전부다.
     * 사진이 도착하면 리스너가 그 슬롯을 `getAndSet(null)` 로 꺼내 안에 든 continuation 을
     * `DatResult.success(photo)` 로 깨운다. `PhotoCaptureRequest` 에는 식별자가 없어서
     * **어느 셔터의 사진인지 구분하지 않는다** — 그래서 슬롯만 열어두면 그 사진이 우리에게 온다.
     *
     * 슬롯에 손댈 수 없는 SDK 버전이면 null 을 돌려주고 호출부가 예전 방식으로 폴백한다.
     */
    private suspend fun awaitInFlightPhoto(
        stream: Stream,
        requestId: String
    ): PhotoCaptureResult? {
        val slot = DatPhotoSlot.slotOf(stream) ?: return null
        if (!DatPhotoSlot.canCreateRequest()) return null
        return try {
            withTimeout(IN_FLIGHT_PHOTO_WAIT_MS) {
                val result = suspendCancellableCoroutine<DatResult<PhotoData, CaptureError>> { continuation ->
                    val pending = DatPhotoSlot.newRequest(continuation)
                    if (pending == null) {
                        continuation.cancel(IllegalStateException("PhotoCaptureRequest 생성 실패"))
                    } else {
                        slot.set(pending)
                        continuation.invokeOnCancellation { slot.compareAndSet(pending, null) }
                    }
                }
                result.getOrNull()
                    ?.let { PhotoCaptureResult.Success(it) }
                    ?: PhotoCaptureResult.Failure(
                        "오는 중인 사진이 실패로 도착: ${result.errorOrNull()?.description}",
                        timedOut = false
                    )
            }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "requestId=$requestId in-flight 대기 ${IN_FLIGHT_PHOTO_WAIT_MS}ms 초과")
            PhotoCaptureResult.Failure(
                "사진이 ${IN_FLIGHT_PHOTO_WAIT_MS}ms 안에 도착하지 않음",
                timedOut = true
            )
        }
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
        is PhotoData.Bitmap -> {
            Log.i(TAG, "delivered photo type=Bitmap (EXIF 없음 — 촬영 시각 확인 불가)")
            photo.bitmap
        }
        is PhotoData.HEIC -> decodeWithOrientation(photo.data)
    }

    /**
     * 진단 전용. 배달된 사진이 **몇 번째 셔터의 것인지** 가리기 위해 안경이 EXIF 에 적어둔
     * 촬영 시각을 찍어본다. 이 값이 1차 호출 시각에 가까우면 2차 셔터는 헛방이라는 뜻이다.
     */
    private fun logPhotoCaptureTime(bytes: ByteArray) {
        runCatching {
            ByteArrayInputStream(bytes).use { input ->
                val exif = ExifInterface(input)
                val original = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                val subSec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
                val digitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                Log.i(
                    TAG,
                    "delivered photo type=HEIC bytes=${bytes.size} " +
                        "exifOriginal=$original.$subSec exifDigitized=$digitized"
                )
            }
        }.onFailure { Log.i(TAG, "delivered photo type=HEIC · EXIF 읽기 실패: ${it.message}") }
    }

    private fun decodeWithOrientation(data: ByteBuffer): Bitmap? {
        val buffer = data.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        logPhotoCaptureTime(bytes)
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
        data object Ready : StreamReadiness
        data class Failed(val message: String) : StreamReadiness
        data object TimedOut : StreamReadiness
    }

    private sealed interface PhotoCaptureResult {
        data class Success(val photo: PhotoData) : PhotoCaptureResult
        data class Failure(val message: String, val timedOut: Boolean) : PhotoCaptureResult
    }
}

/**
 * DAT SDK 의 사진 대기 슬롯에 접근한다.
 *
 * `StreamImpl` 은 `AtomicReference<PhotoCaptureRequest>` 하나로 "지금 사진을 기다리는 사람"을
 * 관리한다. 사진이 도착하면 리스너가 그 슬롯을 비우면서 안에 든 continuation 을 깨운다.
 * `PhotoCaptureRequest` 에 식별자가 없으므로 **어느 셔터의 사진인지 따지지 않는다.**
 *
 * 정식 공개 API 가 아니라 Kotlin `internal` 이 이름만 바뀌어 노출된 것이므로, SDK 가 올라가면
 * 이 접근은 조용히 실패할 수 있다. 그래서 전부 [runCatching] 으로 감싸고 실패하면 null 을
 * 돌려준다 — 호출부는 그때 예전처럼 재촬영으로 되돌아간다.
 */
private object DatPhotoSlot {
    private const val SLOT_GETTER =
        "getPhotoCaptureRequest\$fbandroid_java_com_meta_wearable_dat_camera_camera"
    private const val REQUEST_CLASS =
        "com.meta.wearable.dat.camera.internal.StreamImpl\$PhotoCaptureRequest"

    private val requestConstructor by lazy {
        runCatching {
            Class.forName(REQUEST_CLASS).constructors.firstOrNull { it.parameterCount == 1 }
        }.getOrNull()
    }

    fun canCreateRequest(): Boolean = requestConstructor != null

    @Suppress("UNCHECKED_CAST")
    fun slotOf(stream: Stream): AtomicReference<Any?>? = runCatching {
        stream.javaClass.getMethod(SLOT_GETTER).invoke(stream) as? AtomicReference<Any?>
    }.getOrNull()

    fun newRequest(continuation: Any): Any? = runCatching {
        requestConstructor?.newInstance(continuation)
    }.getOrNull()
}
