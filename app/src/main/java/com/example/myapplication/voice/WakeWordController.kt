package com.example.myapplication.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

data class WakeWordStatus(
    val message: String,
    val ready: Boolean = false,
    val listening: Boolean = false,
    val downloadPercent: Int? = null,
    val error: Boolean = false
)

/**
 * Foreground-only wake-word detector. [VoiceAudioRouter] selects the active glasses or phone
 * communication input before this detector opens its recorder.
 *
 * The Korean Vosk model is downloaded once from the official Alpha Cephei host and then runs
 * completely on-device. The product wake phrase is spoken as "따라쿡"; the acoustic model emits
 * the two in-vocabulary tokens "따라 쿡", which are normalized before matching.
 */
class WakeWordController(
    context: Context,
    private val onWakeWord: () -> Unit,
    private val onStatus: (WakeWordStatus) -> Unit
) : RecognitionListener {
    private companion object {
        private const val TAG = "WakeWordController"
        private const val MODEL_NAME = "vosk-model-small-ko-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip"
        private const val MODEL_SHA256 =
            "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989"
        private const val SAMPLE_RATE = 16_000.0f
        private const val WAKE_GRAMMAR = "[\"따라 쿡\", \"[unk]\"]"
        private const val REQUIRED_STABLE_PARTIALS = 2
        private const val MIN_STABLE_PARTIAL_MS = 150L
        private const val POST_COMMAND_COOLDOWN_MS = 1_000L
    }

    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelsRoot = File(applicationContext.filesDir, "voice-models")
    private val modelDirectory = File(modelsRoot, MODEL_NAME)

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var preparationJob: Job? = null
    private var resumeJob: Job? = null
    private var sessionActive = false
    private var temporarilyPaused = false
    private var cooldownActive = false
    private var wakeDispatched = false
    private var released = false
    private val candidateTracker = WakeWordCandidateTracker(
        requiredStablePartials = REQUIRED_STABLE_PARTIALS,
        minimumStableMs = MIN_STABLE_PARTIAL_MS
    )

    fun activate() {
        if (released) return
        sessionActive = true
        reconcile()
    }

    fun deactivate() {
        sessionActive = false
        temporarilyPaused = false
        cooldownActive = false
        resumeJob?.cancel()
        resumeJob = null
        stopListening()
        publish(WakeWordStatus("음성 호출 꺼짐"))
    }

    fun pause() {
        if (released) return
        temporarilyPaused = true
        candidateTracker.reset()
        stopListening()
        if (sessionActive) {
            publish(WakeWordStatus("다른 음성 작업이 끝나기를 기다리는 중", ready = model != null))
        }
    }

    fun resume() {
        if (released) return
        val applyCooldown = wakeDispatched
        temporarilyPaused = false
        wakeDispatched = false
        candidateTracker.reset()
        resumeJob?.cancel()
        if (applyCooldown && sessionActive) {
            cooldownActive = true
            publish(WakeWordStatus("음성 호출 다시 준비하는 중", ready = model != null))
            resumeJob = scope.launch {
                delay(POST_COMMAND_COOLDOWN_MS)
                cooldownActive = false
                resumeJob = null
                reconcile()
            }
        } else {
            cooldownActive = false
            reconcile()
        }
    }

    fun release() {
        if (released) return
        released = true
        sessionActive = false
        temporarilyPaused = true
        preparationJob?.cancel()
        preparationJob = null
        resumeJob?.cancel()
        resumeJob = null
        stopListening()
        runCatching { model?.close() }
        model = null
        scope.cancel()
    }

    private fun reconcile() {
        if (!sessionActive || temporarilyPaused || cooldownActive || released) return
        if (model == null) prepareModel() else startListening()
    }

    private fun prepareModel() {
        if (preparationJob?.isActive == true) return
        preparationJob = scope.launch {
            try {
                val preparedDirectory = withContext(Dispatchers.IO) { ensureModelInstalled() }
                publish(WakeWordStatus("온디바이스 호출 모델 불러오는 중"))
                val loadedModel = withContext(Dispatchers.IO) { Model(preparedDirectory.absolutePath) }
                if (released) {
                    loadedModel.close()
                    return@launch
                }
                model = loadedModel
                publish(WakeWordStatus("'따라쿡' 호출 준비 완료", ready = true))
                reconcile()
            } catch (error: Exception) {
                Log.e(TAG, "Wake-word model preparation failed", error)
                publish(
                    WakeWordStatus(
                        message = "호출 모델 준비 실패: ${error.message ?: error.javaClass.simpleName}",
                        error = true
                    )
                )
            } finally {
                preparationJob = null
            }
        }
    }

    private suspend fun ensureModelInstalled(): File {
        if (isValidModel(modelDirectory)) return modelDirectory
        modelsRoot.mkdirs()
        val archive = File(applicationContext.cacheDir, "$MODEL_NAME.zip.part")
        downloadModel(archive)
        installArchive(archive)
        check(isValidModel(modelDirectory)) { "설치된 호출 모델 파일이 불완전합니다." }
        archive.delete()
        return modelDirectory
    }

    private suspend fun downloadModel(destination: File) {
        destination.parentFile?.mkdirs()
        destination.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode in 200..299) {
                "모델 다운로드 HTTP ${connection.responseCode}"
            }
            val totalBytes = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0L) {
                            val percent = ((downloadedBytes * 100L) / totalBytes).toInt()
                            if (percent != lastPercent && (percent % 2 == 0 || percent == 100)) {
                                lastPercent = percent
                                withContext(Dispatchers.Main.immediate) {
                                    publish(
                                        WakeWordStatus(
                                            message = "온디바이스 호출 모델 다운로드 중 $percent%",
                                            downloadPercent = percent
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash == MODEL_SHA256) { "호출 모델 무결성 검증에 실패했습니다." }
        } finally {
            connection.disconnect()
        }
    }

    private fun installArchive(archive: File) {
        publish(WakeWordStatus("온디바이스 호출 모델 설치 중"))
        val stagingRoot = File(modelsRoot, ".$MODEL_NAME-installing")
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()
        val canonicalRoot = stagingRoot.canonicalFile
        ZipInputStream(FileInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(stagingRoot, entry.name).canonicalFile
                check(
                    output.path == canonicalRoot.path ||
                        output.path.startsWith(canonicalRoot.path + File.separator)
                ) { "잘못된 모델 압축 경로입니다." }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { destination -> zip.copyTo(destination) }
                }
                zip.closeEntry()
            }
        }
        val extractedModel = File(stagingRoot, MODEL_NAME)
        check(isValidModel(extractedModel)) { "압축 해제된 호출 모델이 불완전합니다." }
        modelDirectory.deleteRecursively()
        if (!extractedModel.renameTo(modelDirectory)) {
            extractedModel.copyRecursively(modelDirectory, overwrite = true)
        }
        stagingRoot.deleteRecursively()
    }

    private fun startListening() {
        if (!sessionActive || temporarilyPaused || released || speechService != null) return
        val activeModel = model ?: return
        try {
            candidateTracker.reset()
            val activeRecognizer = Recognizer(activeModel, SAMPLE_RATE, WAKE_GRAMMAR)
            recognizer = activeRecognizer
            speechService = SpeechService(activeRecognizer, SAMPLE_RATE).also { it.startListening(this) }
            publish(
                WakeWordStatus(
                    message = "'따라쿡'이라고 부르세요",
                    ready = true,
                    listening = true
                )
            )
        } catch (error: Exception) {
            runCatching { recognizer?.close() }
            recognizer = null
            Log.e(TAG, "Unable to start wake-word listening", error)
            publish(
                WakeWordStatus(
                    message = "호출어 감지 시작 실패: ${error.message ?: error.javaClass.simpleName}",
                    ready = true,
                    error = true
                )
            )
        }
    }

    private fun stopListening() {
        candidateTracker.reset()
        val activeService = speechService
        val activeRecognizer = recognizer
        speechService = null
        recognizer = null
        if (activeService != null) {
            runCatching { activeService.stop() }
            runCatching { activeService.shutdown() }
        }
        runCatching { activeRecognizer?.close() }
    }

    override fun onResult(hypothesis: String) = detectFinalWakeWord(hypothesis, "text")

    override fun onPartialResult(hypothesis: String) {
        if (wakeDispatched || temporarilyPaused || cooldownActive || released) return
        val text = hypothesisText(hypothesis, "partial")
        val matched = candidateTracker.observePartial(text, SystemClock.elapsedRealtime())
        debugHypothesis("partial", text, matched)
        if (matched) dispatchWakeWord()
    }

    override fun onFinalResult(hypothesis: String) {
        detectFinalWakeWord(hypothesis, "text")
        mainHandler.post {
            if (!wakeDispatched && sessionActive && !temporarilyPaused && !cooldownActive && !released) {
                stopListening()
                scope.launch {
                    delay(300L)
                    reconcile()
                }
            }
        }
    }

    override fun onError(exception: Exception) {
        Log.e(TAG, "Wake-word recognition error", exception)
        mainHandler.post {
            stopListening()
            if (sessionActive && !temporarilyPaused && !cooldownActive && !released) {
                publish(WakeWordStatus("호출어 감지를 다시 시작하는 중", ready = model != null))
                scope.launch {
                    delay(1_000L)
                    reconcile()
                }
            }
        }
    }

    override fun onTimeout() {
        mainHandler.post {
            stopListening()
            if (sessionActive && !temporarilyPaused && !cooldownActive && !released) reconcile()
        }
    }

    private fun detectFinalWakeWord(hypothesis: String, field: String) {
        if (wakeDispatched || temporarilyPaused || cooldownActive || released) return
        val text = hypothesisText(hypothesis, field)
        val matched = candidateTracker.observeFinal(text)
        debugHypothesis("final", text, matched)
        if (matched) dispatchWakeWord()
    }

    private fun dispatchWakeWord() {
        if (wakeDispatched || temporarilyPaused || cooldownActive || released) return
        wakeDispatched = true
        mainHandler.post {
            if (!sessionActive || temporarilyPaused || cooldownActive || released) return@post
            temporarilyPaused = true
            stopListening()
            publish(WakeWordStatus("호출어 인식됨", ready = true))
            onWakeWord()
        }
    }

    private fun hypothesisText(hypothesis: String, field: String): String = runCatching {
        JSONObject(hypothesis).optString(field)
    }.getOrDefault("")

    private fun debugHypothesis(source: String, text: String, matched: Boolean) {
        if (!BuildConfig.DEBUG || text.isBlank()) return
        Log.d(TAG, "$source=${text.take(40)} matched=$matched")
    }

    private fun publish(status: WakeWordStatus) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStatus(status)
        } else {
            mainHandler.post { if (!released) onStatus(status) }
        }
    }

    private fun isValidModel(directory: File): Boolean =
        File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile &&
            File(directory, "graph/Gr.fst").isFile
}

internal fun isTtaraCookWakeWord(text: String): Boolean =
    text.filterNot(Char::isWhitespace) == "따라쿡"

internal class WakeWordCandidateTracker(
    private val requiredStablePartials: Int = 2,
    private val minimumStableMs: Long = 150L
) {
    private var stablePartialCount = 0
    private var firstStablePartialAtMs = 0L

    init {
        require(requiredStablePartials >= 1)
        require(minimumStableMs >= 0L)
    }

    fun observePartial(text: String, nowMs: Long): Boolean {
        if (!isTtaraCookWakeWord(text)) {
            reset()
            return false
        }
        if (stablePartialCount == 0) firstStablePartialAtMs = nowMs
        stablePartialCount += 1
        return stablePartialCount >= requiredStablePartials &&
            nowMs - firstStablePartialAtMs >= minimumStableMs
    }

    fun observeFinal(text: String): Boolean {
        val matched = isTtaraCookWakeWord(text)
        reset()
        return matched
    }

    fun reset() {
        stablePartialCount = 0
        firstStablePartialAtMs = 0L
    }
}
