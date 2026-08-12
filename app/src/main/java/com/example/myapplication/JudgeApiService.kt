package com.example.myapplication

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import kotlin.system.measureTimeMillis

private const val JUDGE_BASE_URL = "https://ddaracook-server.onrender.com"
private const val JUDGE_TOKEN = "cookassist-Z9u05mUPGURTgSXb1NCgF7nKZgv-Okll"

data class JudgeResult(
    val verdict: JudgmentVerdict,
    val reasonCode: ReasonCode,
    val vlmLatencyMs: Int,
    val roundTripMs: Int,
    val backend: String,
    val promptVersion: String
)

sealed interface JudgeApiOutcome {
    data class Success(val result: JudgeResult) : JudgeApiOutcome
    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val shouldRetry: Boolean = false
    ) : JudgeApiOutcome
}

object JudgeApiService {
    private const val SAMPLE_JPEG_BASE64 =
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBUQEBAVFRUVFRUVFRUVFRUVFRUVFRUXFhUVFRUYHSggGBolGxUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGxAQGy0mICYtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBIgACEQEDEQH/xAAXAAADAQAAAAAAAAAAAAAAAAAAAQID/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEAMQAAAB6AAAAP/EABQQAQAAAAAAAAAAAAAAAAAAACD/2gAIAQEAAT8Af//EABQRAQAAAAAAAAAAAAAAAAAAACD/2gAIAQIBAT8Af//EABQRAQAAAAAAAAAAAAAAAAAAACD/2gAIAQMBAT8Af//Z"

    fun judgeStep(
        recipe: Recipe,
        step: RecipeStep,
        elapsedSeconds: Int,
        mockVerdict: JudgmentVerdict? = null
    ): JudgeApiOutcome {
        return judgeStepInternal(
            recipe = recipe,
            step = step,
            elapsedSeconds = elapsedSeconds,
            mockVerdict = mockVerdict,
            hasRetried = false
        )
    }

    private fun judgeStepInternal(
        recipe: Recipe,
        step: RecipeStep,
        elapsedSeconds: Int,
        mockVerdict: JudgmentVerdict?,
        hasRetried: Boolean
    ): JudgeApiOutcome {
        val connection = (URL("$JUDGE_BASE_URL/judge-step").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $JUDGE_TOKEN")
            if (mockVerdict != null) {
                setRequestProperty("X-Mock-Verdict", mockVerdict.name)
            }
        }

        return try {
            val requestJson = JSONObject().apply {
                put("requestId", UUID.randomUUID().toString())
                put("recipeId", recipe.id)
                put("stepOrder", step.order)
                put("instruction", step.instruction)
                put("checkType", step.checkType.toServerType())
                put("checkCondition", step.checkCondition.orEmpty())
                put("elapsedSeconds", elapsedSeconds)
                if (step.checkType == CheckType.COLOR_CHANGE || step.checkType == CheckType.STATE_TRANSITION) {
                    put("startImage", SAMPLE_JPEG_BASE64)
                }
                put("currentImage", SAMPLE_JPEG_BASE64)
            }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
            }

            var responseCode = -1
            var responseBody = ""
            var roundTripMs = 0
            roundTripMs = measureTimeMillis {
                responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                responseBody = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }.toInt()

            if (responseCode !in 200..299) {
                val retryable = responseCode == 503
                if (retryable && !hasRetried) {
                    return judgeStepInternal(
                        recipe = recipe,
                        step = step,
                        elapsedSeconds = elapsedSeconds,
                        mockVerdict = mockVerdict,
                        hasRetried = true
                    )
                }
                JudgeApiOutcome.Failure(
                    message = "서버 오류 $responseCode: $responseBody",
                    statusCode = responseCode,
                    shouldRetry = retryable
                )
            } else {
                val json = JSONObject(responseBody)
                JudgeApiOutcome.Success(
                    result = JudgeResult(
                        verdict = json.getString("verdict").toVerdict(),
                        reasonCode = json.getString("reasonCode").toReasonCode(),
                        vlmLatencyMs = json.optInt("vlmLatencyMs", 0),
                        roundTripMs = roundTripMs,
                        backend = json.optString("backend", ""),
                        promptVersion = json.optString("promptVersion", "")
                    )
                )
            }
        } catch (e: SocketTimeoutException) {
            if (!hasRetried) {
                judgeStepInternal(
                    recipe = recipe,
                    step = step,
                    elapsedSeconds = elapsedSeconds,
                    mockVerdict = mockVerdict,
                    hasRetried = true
                )
            } else {
                JudgeApiOutcome.Failure(
                    message = "판정 서버 타임아웃: ${e.message ?: "8초 내 응답 없음"}",
                    shouldRetry = true
                )
            }
        } catch (e: Exception) {
            JudgeApiOutcome.Failure(e.message ?: "판정 요청에 실패했습니다.")
        } finally {
            connection.disconnect()
        }
    }
}

private fun CheckType.toServerType(): String {
    return when (this) {
        CheckType.PRESENCE -> "PRESENCE"
        CheckType.IDENTIFICATION -> "IDENTIFY"
        CheckType.COLOR_CHANGE -> "COLOR_CHANGE"
        CheckType.STATE_TRANSITION -> "STATE_CHANGE"
        CheckType.TIMER_ONLY -> "TIME_ONLY"
    }
}

private fun String.toVerdict(): JudgmentVerdict {
    return runCatching { JudgmentVerdict.valueOf(this) }.getOrDefault(JudgmentVerdict.CANNOT_TELL)
}

private fun String.toReasonCode(): ReasonCode {
    return when (this) {
        "VISIBLE_CHANGE" -> ReasonCode.VISIBLE_CHANGE
        "NO_CHANGE" -> ReasonCode.NO_CHANGE
        "TARGET_NOT_VISIBLE" -> ReasonCode.TARGET_NOT_VISIBLE
        "BLURRY" -> ReasonCode.BLURRY
        "CONDITION_NOT_MET" -> ReasonCode.CONDITION_NOT_MET
        "MODEL_UNCERTAIN" -> ReasonCode.MODEL_UNCERTAIN
        "OTHER" -> ReasonCode.OTHER
        else -> ReasonCode.NETWORK_RETRY
    }
}
