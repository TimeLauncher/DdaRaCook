package com.example.myapplication.judgment

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.ImageCropTarget
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LocalCropPhaseTiming(
    val totalMs: Long,
    val modelLoadMs: Long,
    val preprocessMs: Long,
    val inferenceMs: Long,
    val postprocessMs: Long,
    val encodeMs: Long
)

internal data class LocalRoiCropResult(
    val jpegBytes: ByteArray,
    val mode: String,
    val detectionCount: Int,
    val width: Int,
    val height: Int,
    val timing: LocalCropPhaseTiming
)

/** Runs the same fixed-prompt ONNX detector as the server, but once per Android process. */
internal class OnDeviceRoiCropper(context: Context) {
    private val applicationContext = context.applicationContext
    private val sessionLock = Any()
    private val inferenceLock = Any()

    @Volatile
    private var sessionBundle: SessionBundle? = null

    @Volatile
    private var sessionFailure: Throwable? = null

    fun warmUp() {
        val started = SystemClock.elapsedRealtime()
        val (bundle, modelLoadMs) = getSession()
        val input = directFloatBuffer(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * CHANNELS)
        val inferenceStarted = SystemClock.elapsedRealtime()
        synchronized(inferenceLock) {
            runSession(bundle, input).also { /* Force graph initialization. */ }
        }
        Log.i(
            TAG,
            "phone YOLO ready provider=${bundle.provider} " +
                "load=${modelLoadMs}ms inference=${elapsedMs(inferenceStarted)}ms " +
                "total=${elapsedMs(started)}ms"
        )
    }

    fun crop(jpegBytes: ByteArray, target: ImageCropTarget): LocalRoiCropResult {
        val totalStarted = SystemClock.elapsedRealtime()
        val preprocessStarted = SystemClock.elapsedRealtime()
        val source = requireNotNull(BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)) {
            "폰 YOLO가 이미지를 디코딩하지 못했습니다."
        }

        var output: Bitmap? = null
        try {
            if (target == ImageCropTarget.LEGACY_BOTTOM_60) {
                val preprocessMs = elapsedMs(preprocessStarted)
                val postprocessStarted = SystemClock.elapsedRealtime()
                output = legacyBottomSixty(source)
                val postprocessMs = elapsedMs(postprocessStarted)
                val encodeStarted = SystemClock.elapsedRealtime()
                val encoded = encodeJpeg(output)
                val encodeMs = elapsedMs(encodeStarted)
                return result(
                    encoded = encoded,
                    output = output,
                    target = target,
                    mode = "LOCAL_LEGACY_BOTTOM_60",
                    detectionCount = 0,
                    totalStarted = totalStarted,
                    modelLoadMs = 0,
                    preprocessMs = preprocessMs,
                    inferenceMs = 0,
                    postprocessMs = postprocessMs,
                    encodeMs = encodeMs
                )
            }

            val (bundle, modelLoadMs) = getSession()
            val prepared = prepareTensor(source)
            val preprocessMs = elapsedMs(preprocessStarted)

            val inferenceStarted = SystemClock.elapsedRealtime()
            val rawOutput = synchronized(inferenceLock) {
                runSession(bundle, prepared.tensor)
            }
            val inferenceMs = elapsedMs(inferenceStarted)

            val postprocessStarted = SystemClock.elapsedRealtime()
            val detections = localAgnosticNms(
                parseLocalRoiDetections(
                    values = rawOutput.values,
                    rowCount = rawOutput.rowCount,
                    fieldCount = rawOutput.fieldCount,
                    sourceWidth = source.width,
                    sourceHeight = source.height,
                    scale = prepared.scale,
                    padX = prepared.padX,
                    padY = prepared.padY
                )
            )
            val selected = selectLocalActiveDetection(detections, target)
            output = if (selected == null) {
                legacyBottomSixty(source)
            } else {
                detectedRoi(source, selected, selected.target)
            }
            val postprocessMs = elapsedMs(postprocessStarted)

            val encodeStarted = SystemClock.elapsedRealtime()
            val encoded = encodeJpeg(output)
            val encodeMs = elapsedMs(encodeStarted)
            return result(
                encoded = encoded,
                output = output,
                target = target,
                mode = if (selected == null) "LOCAL_FALLBACK_BOTTOM_60" else "LOCAL_YOLO_ROI",
                detectionCount = detections.size,
                totalStarted = totalStarted,
                modelLoadMs = modelLoadMs,
                preprocessMs = preprocessMs,
                inferenceMs = inferenceMs,
                postprocessMs = postprocessMs,
                encodeMs = encodeMs
            )
        } finally {
            if (output != null && output !== source && !output.isRecycled) output.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun getSession(): Pair<SessionBundle, Long> {
        sessionBundle?.let { return it to 0L }
        sessionFailure?.let { throw IllegalStateException("폰 YOLO 모델을 불러오지 못했습니다.", it) }
        val started = SystemClock.elapsedRealtime()
        synchronized(sessionLock) {
            sessionBundle?.let { return it to elapsedMs(started) }
            sessionFailure?.let { throw IllegalStateException("폰 YOLO 모델을 불러오지 못했습니다.", it) }
            try {
                val modelBytes = applicationContext.assets.open(MODEL_ASSET).use { it.readBytes() }
                val environment = OrtEnvironment.getEnvironment()
                sessionBundle = createXnnpackSession(environment, modelBytes)
                    ?: createCpuSession(environment, modelBytes)
            } catch (error: Throwable) {
                sessionFailure = error
                throw error
            }
            return requireNotNull(sessionBundle) to elapsedMs(started)
        }
    }

    private fun createXnnpackSession(
        environment: OrtEnvironment,
        modelBytes: ByteArray
    ): SessionBundle? = runCatching {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }
        val session = environment.createSession(modelBytes, options)
        SessionBundle(
            environment = environment,
            options = options,
            session = session,
            inputName = session.inputNames.first(),
            provider = "XNNPACK"
        )
    }.onFailure { error ->
        Log.w(TAG, "XNNPACK session unavailable; using ORT CPU", error)
    }.getOrNull()

    private fun createCpuSession(
        environment: OrtEnvironment,
        modelBytes: ByteArray
    ): SessionBundle {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        val session = environment.createSession(modelBytes, options)
        return SessionBundle(
            environment = environment,
            options = options,
            session = session,
            inputName = session.inputNames.first(),
            provider = "ORT_CPU"
        )
    }

    private fun prepareTensor(source: Bitmap): PreparedTensor {
        val scale = min(
            MODEL_INPUT_SIZE.toFloat() / source.width,
            MODEL_INPUT_SIZE.toFloat() / source.height
        )
        val resizedWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val padX = (MODEL_INPUT_SIZE - resizedWidth) / 2
        val padY = (MODEL_INPUT_SIZE - resizedHeight) / 2
        val resized = Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
        val canvasBitmap = Bitmap.createBitmap(
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
        Canvas(canvasBitmap).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(resized, padX.toFloat(), padY.toFloat(), null)
        }
        if (resized !== source) resized.recycle()

        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        canvasBitmap.getPixels(
            pixels,
            0,
            MODEL_INPUT_SIZE,
            0,
            0,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE
        )
        canvasBitmap.recycle()

        val planeSize = pixels.size
        val tensor = directFloatBuffer(planeSize * CHANNELS)
        pixels.forEachIndexed { index, pixel ->
            tensor.put(index, Color.red(pixel) / 255f)
            tensor.put(planeSize + index, Color.green(pixel) / 255f)
            tensor.put(planeSize * 2 + index, Color.blue(pixel) / 255f)
        }
        tensor.rewind()
        return PreparedTensor(tensor, scale, padX, padY)
    }

    private fun runSession(bundle: SessionBundle, input: FloatBuffer): RawModelOutput {
        input.rewind()
        OnnxTensor.createTensor(
            bundle.environment,
            input,
            longArrayOf(1, CHANNELS.toLong(), MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        ).use { tensor ->
            bundle.session.run(mapOf(bundle.inputName to tensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val shape = (output.info as TensorInfo).shape
                require(shape.size == 3 && shape[0] == 1L && shape[2] >= 6L) {
                    "예상하지 못한 YOLO 출력 형태입니다: ${shape.contentToString()}"
                }
                val buffer = output.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                return RawModelOutput(
                    values = values,
                    rowCount = shape[1].toInt(),
                    fieldCount = shape[2].toInt()
                )
            }
        }
    }

    private fun detectedRoi(
        source: Bitmap,
        detection: LocalRoiDetection,
        target: ImageCropTarget
    ): Bitmap {
        val aspectRatio = if (target == ImageCropTarget.CUTTING_BOARD_ROI) 4f / 3f else 1f
        val window = localCropWindowForDetection(
            imageWidth = source.width,
            imageHeight = source.height,
            detection = detection,
            outputAspectRatio = aspectRatio
        )
        val cropped = Bitmap.createBitmap(
            source,
            window.left,
            window.top,
            window.width,
            window.height
        )
        val outputWidth = ROI_OUTPUT_LONG_EDGE
        val outputHeight = if (target == ImageCropTarget.CUTTING_BOARD_ROI) {
            ROI_OUTPUT_LONG_EDGE * 3 / 4
        } else {
            ROI_OUTPUT_LONG_EDGE
        }
        val normalized = Bitmap.createScaledBitmap(cropped, outputWidth, outputHeight, true)
        if (normalized !== cropped) cropped.recycle()
        return normalized
    }

    private fun legacyBottomSixty(source: Bitmap): Bitmap {
        val window = localBottomSixtyWindow(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source,
            window.left,
            window.top,
            window.width,
            window.height
        )
        val dimensions = scaledDimensions(cropped.width, cropped.height, ROI_OUTPUT_LONG_EDGE)
        val normalized = if (dimensions.width == cropped.width && dimensions.height == cropped.height) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, dimensions.width, dimensions.height, true)
        }
        if (normalized !== cropped) cropped.recycle()
        return normalized
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
            "폰 YOLO 크롭 JPEG 저장에 실패했습니다."
        }
        output.toByteArray()
    }

    private fun result(
        encoded: ByteArray,
        output: Bitmap,
        target: ImageCropTarget,
        mode: String,
        detectionCount: Int,
        totalStarted: Long,
        modelLoadMs: Long,
        preprocessMs: Long,
        inferenceMs: Long,
        postprocessMs: Long,
        encodeMs: Long
    ): LocalRoiCropResult {
        val cropResult = LocalRoiCropResult(
            jpegBytes = encoded,
            mode = mode,
            detectionCount = detectionCount,
            width = output.width,
            height = output.height,
            timing = LocalCropPhaseTiming(
                totalMs = elapsedMs(totalStarted),
                modelLoadMs = modelLoadMs,
                preprocessMs = preprocessMs,
                inferenceMs = inferenceMs,
                postprocessMs = postprocessMs,
                encodeMs = encodeMs
            )
        )
        Log.i(
            TAG,
            "crop target=$target mode=$mode detections=$detectionCount " +
                "total=${cropResult.timing.totalMs}ms load=${modelLoadMs}ms " +
                "pre=${preprocessMs}ms infer=${inferenceMs}ms " +
                "post=${postprocessMs}ms jpeg=${encodeMs}ms"
        )
        return cropResult
    }

    private data class SessionBundle(
        val environment: OrtEnvironment,
        val options: OrtSession.SessionOptions,
        val session: OrtSession,
        val inputName: String,
        val provider: String
    )

    private data class PreparedTensor(
        val tensor: FloatBuffer,
        val scale: Float,
        val padX: Int,
        val padY: Int
    )

    private data class RawModelOutput(
        val values: FloatArray,
        val rowCount: Int,
        val fieldCount: Int
    )

    private companion object {
        const val TAG = "OnDeviceRoiCropper"
        const val MODEL_ASSET = "models/yoloe-26n-cook-roi.onnx"
        const val MODEL_INPUT_SIZE = 640

        /**
         * VLM 입력 해상도.
         *
         * 2026-08-26 에 512 로 낮췄다가 768 로 되돌렸다. 512 는 NVIDIA 지연
         * 대책이었는데 주 백엔드가 Gemini 로 바뀌며 근거가 사라졌고, 정확도
         * 측정(Gemini 19/21)이 전부 768 기준이다. 서버 roi_cropper.py 참고.
         *
         * ⚠️ 서버의 `roi_cropper.ROI_OUTPUT_LONG_EDGE` 와 같은 값이어야 한다.
         *    폰 YOLO 가 실패해 서버 크롭으로 폴백할 때 두 경로가 다른 크기를
         *    내면 같은 단계인데 판정 입력이 달라진다.
         */
        const val ROI_OUTPUT_LONG_EDGE = 768
        const val CHANNELS = 3
        const val JPEG_QUALITY = 80

        fun elapsedMs(started: Long): Long =
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)

        fun directFloatBuffer(size: Int): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}
