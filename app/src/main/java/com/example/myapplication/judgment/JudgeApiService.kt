package com.example.myapplication.judgment

import android.content.Context
import android.util.Base64
import com.example.myapplication.BuildConfig
import com.example.myapplication.CheckType
import com.example.myapplication.JudgmentVerdict
import com.example.myapplication.ReasonCode
import com.example.myapplication.RecipeStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.system.measureTimeMillis

data class JudgeDebugOptions(
    val mockVerdict: JudgmentVerdict? = null,
    val mockStatus: Int? = null,
    val mockDelayMs: Long? = null
)

class JudgeApiService(
    private val context: Context,
    baseUrl: String = BuildConfig.JUDGE_BASE_URL,
    private val teamToken: String = BuildConfig.JUDGE_TEAM_TOKEN,
    private val debugOptions: JudgeDebugOptions = JudgeDebugOptions()
) : JudgmentGateway {
    @Volatile
    private var baseUrl: String = baseUrl
    private val imageNormalizer = ImageNormalizer(context)
    private val mutableState = MutableStateFlow<JudgmentGatewayState>(JudgmentGatewayState.Idle)
    override val state: StateFlow<JudgmentGatewayState> = mutableState.asStateFlow()

    override suspend fun judge(request: JudgmentRequest): JudgmentOutcome {
        if (request.checkType == CheckType.TIMER_ONLY) {
            return failure(request, "시간 전용 단계는 판정 서버를 호출하지 않습니다.")
        }
        if (teamToken.isBlank()) {
            return failure(request, "판정 서버 인증 설정이 없습니다.")
        }

        mutableState.value = JudgmentGatewayState.Judging
        return try {
            withContext(Dispatchers.IO) {
                judgeInternal(request = request, hasRetried = false)
            }
        } finally {
            mutableState.value = JudgmentGatewayState.Idle
        }
    }

    override suspend fun release() {
        mutableState.value = JudgmentGatewayState.Released
    }

    suspend fun checkHealth(): ServerHealth = withContext(Dispatchers.IO) {
        if (teamToken.isBlank()) return@withContext ServerHealth(false, "판정 서버 인증 설정이 없습니다.")
        val connection = (URL("${baseUrl.trimEnd('/')}/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HEALTH_TIMEOUT_MS
            readTimeout = HEALTH_TIMEOUT_MS
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) return@withContext ServerHealth(false, "판정 서버 상태 확인 실패 ($status)")
            val body = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(body)
            if (json.optString("status") == "ok") ServerHealth(true, "판정 서버 연결 준비 완료")
            else ServerHealth(false, "판정 서버가 준비되지 않았습니다.")
        } catch (_: Exception) {
            ServerHealth(false, "판정 서버에 연결할 수 없습니다.")
        } finally {
            connection.disconnect()
        }
    }

    fun updateBaseUrl(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val valid = runCatching { URL(normalized) }.getOrNull()?.protocol in setOf("http", "https")
        if (valid) baseUrl = normalized
        return valid
    }

    private suspend fun judgeInternal(
        request: JudgmentRequest,
        hasRetried: Boolean
    ): JudgmentOutcome {
        val connection = (URL("${baseUrl.trimEnd('/')}/judge-step").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $teamToken")
            debugOptions.toHeaders().forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        return try {
            val requestJson = createRequestJson(request)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
            }

            var responseCode = -1
            var responseBody = ""
            val roundTripMs = measureTimeMillis {
                responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                responseBody = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }

            if (responseCode !in 200..299) {
                val retry = retryDirective(responseCode, connection.getHeaderField("Retry-After"))
                if (retry != null && !hasRetried) {
                    if (retry.delayMs > 0) delay(retry.delayMs)
                    return judgeInternal(request = request, hasRetried = true)
                }
                failure(
                    request = request,
                    message = serverErrorMessage(responseCode, responseBody),
                    retryable = retry != null
                )
            } else {
                val json = JSONObject(responseBody)
                JudgmentOutcome.Success(
                    JudgmentResult(
                        requestId = request.requestId,
                        cookingSessionId = request.cookingSessionId,
                        stepOrder = request.stepOrder,
                        verdict = json.getString("verdict").toVerdict(),
                        reasonCode = json.getString("reasonCode").toReasonCode(),
                        vlmLatencyMs = json.optLong("vlmLatencyMs", 0L),
                        roundTripMs = roundTripMs,
                        requestedAtMs = request.requestedAtMs,
                        respondedAtMs = System.currentTimeMillis()
                    )
                )
            }
        } catch (error: SocketTimeoutException) {
            if (!hasRetried) {
                judgeInternal(request = request, hasRetried = true)
            } else {
                failure(request, "판정 서버가 제한 시간 안에 응답하지 않았습니다.", retryable = true)
            }
        } catch (error: Exception) {
            failure(request, error.message ?: "판정 요청에 실패했습니다.")
        } finally {
            connection.disconnect()
        }
    }

    private fun createRequestJson(request: JudgmentRequest): JSONObject {
        val currentImage = readImageAsBase64(request.currentImageUri, request.imagePolicy)
        return JSONObject().apply {
            put("requestId", request.requestId)
            put("recipeId", request.recipeId)
            put("stepOrder", request.stepOrder)
            put("instruction", request.instruction)
            put("checkType", request.checkType.toServerType())
            put("checkCondition", request.checkCondition.orEmpty())
            put("elapsedSeconds", request.elapsedSeconds)
            if (request.needsStartImage) {
                put(
                    "startImage",
                    request.baselineImageUri?.let { readImageAsBase64(it, request.imagePolicy) }
                        ?: JSONObject.NULL
                )
            }
            put("currentImage", currentImage)
        }
    }

    private fun readImageAsBase64(uriValue: String, policy: JudgmentImagePolicy): String {
        val normalized = imageNormalizer.normalize(uriValue, policy)
        return Base64.encodeToString(normalized.jpegBytes, Base64.NO_WRAP)
    }

    private fun failure(
        request: JudgmentRequest,
        message: String,
        retryable: Boolean = false
    ): JudgmentOutcome.Failure = JudgmentOutcome.Failure(
        requestId = request.requestId,
        message = message,
        retryable = retryable,
        requestedAtMs = request.requestedAtMs,
        respondedAtMs = System.currentTimeMillis()
    )

    private fun serverErrorMessage(statusCode: Int, responseBody: String): String {
        val detail = runCatching { JSONObject(responseBody).optString("detail") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        return if (detail == null) "판정 서버 오류 ($statusCode)" else "판정 서버 오류 ($statusCode): $detail"
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 8_000
        const val HEALTH_TIMEOUT_MS = 5_000
    }
}

data class ServerHealth(val ready: Boolean, val message: String)

internal data class RetryDirective(val delayMs: Long)

internal fun retryDirective(statusCode: Int, retryAfter: String?): RetryDirective? = when (statusCode) {
    429 -> RetryDirective(delayMs = retryAfter.toBackoffMillis())
    503 -> RetryDirective(delayMs = 0L)
    else -> null
}

private fun String?.toBackoffMillis(): Long {
    val seconds = this?.trim()?.toLongOrNull()?.coerceIn(1L, 30L) ?: DEFAULT_RATE_LIMIT_BACKOFF_SECONDS
    return seconds * 1_000L
}

internal fun JudgeDebugOptions.toHeaders(): Map<String, String> = buildMap {
    mockVerdict?.let { put("X-Mock-Verdict", it.name) }
    mockStatus?.let {
        require(it in setOf(400, 401, 403, 429, 500, 503)) { "지원하지 않는 mock 상태 코드입니다." }
        put("X-Mock-Status", it.toString())
    }
    mockDelayMs?.let {
        require(it in 0L..30_000L) { "mock 지연은 0~30000ms여야 합니다." }
        put("X-Mock-Delay-Ms", it.toString())
    }
}

/**
 * CONTRACT §3.2 — `startImage` 전송 여부.
 *
 * v1.2까지는 `checkType == COLOR_CHANGE` 로 정했으나, 판정 유형으로는 가를 수 없다.
 * 쏘야 1단계와 4단계는 둘 다 STATE_CHANGE 인데 1단계는 절대 판정("덩어리가 없는가"),
 * 4단계는 상대 판정("처음보다 벌어졌는가")이다. 기준을 **완료 조건이 시작 시점 대비
 * 변화를 묻는가**로 옮기고, 그 판단은 레시피가 `RecipeStep.needsStartImage` 로 내린다.
 */
internal fun RecipeStep.shouldSendStartImage(): Boolean = needsStartImage

internal fun CheckType.toServerType(): String = when (this) {
    CheckType.PRESENCE -> "PRESENCE"
    CheckType.COUNT -> "COUNT"
    CheckType.IDENTIFICATION -> "IDENTIFY"
    CheckType.COLOR_CHANGE -> "COLOR_CHANGE"
    CheckType.STATE_TRANSITION -> "STATE_CHANGE"
    CheckType.TIMER_ONLY -> "TIME_ONLY"
}

internal fun String.toVerdict(): JudgmentVerdict =
    runCatching { JudgmentVerdict.valueOf(this) }.getOrDefault(JudgmentVerdict.CANNOT_TELL)

internal fun String.toReasonCode(): ReasonCode = when (this) {
    "VISIBLE_CHANGE" -> ReasonCode.VISIBLE_CHANGE
    "NO_CHANGE" -> ReasonCode.NO_CHANGE
    "TARGET_NOT_VISIBLE" -> ReasonCode.TARGET_NOT_VISIBLE
    "BLURRY" -> ReasonCode.BLURRY
    "OTHER" -> ReasonCode.OTHER
    else -> ReasonCode.OTHER
}

private const val DEFAULT_RATE_LIMIT_BACKOFF_SECONDS = 2L
