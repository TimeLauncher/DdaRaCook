package com.example.myapplication.recipeimport

import com.example.myapplication.BuildConfig
import com.example.myapplication.CheckType
import com.example.myapplication.Ingredient
import com.example.myapplication.InspectionPolicy
import com.example.myapplication.ParallelTimer
import com.example.myapplication.Recipe
import com.example.myapplication.RecipeStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class RecipeImportResult(
    val recipe: Recipe,
    val warnings: List<String>,
    val sourceTitle: String
)

class RecipeImportException(message: String) : Exception(message)

class YouTubeRecipeApiService(
    baseUrl: String = BuildConfig.JUDGE_BASE_URL,
    private val teamToken: String = BuildConfig.JUDGE_TEAM_TOKEN
) {
    @Volatile
    private var baseUrl: String = baseUrl

    fun updateBaseUrl(value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val valid = runCatching { URL(normalized) }.getOrNull()?.protocol in setOf("http", "https")
        if (valid) baseUrl = normalized
        return valid
    }

    suspend fun extract(url: String): RecipeImportResult = withContext(Dispatchers.IO) {
        if (teamToken.isBlank()) throw RecipeImportException("레시피 추출 서버 인증 설정이 없습니다.")
        val connection = (URL("${baseUrl.trimEnd('/')}/extract-recipe").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $teamToken")
        }
        try {
            val requestBody = JSONObject().put("url", url.trim()).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                throw RecipeImportException(detail ?: "레시피 추출 서버 오류 ($status)")
            }
            parseRecipeExtractionResponse(body)
        } catch (error: RecipeImportException) {
            throw error
        } catch (_: SocketTimeoutException) {
            throw RecipeImportException("레시피 추출 시간이 초과되었습니다. 다시 시도해 주세요.")
        } catch (error: Exception) {
            throw RecipeImportException(error.message ?: "레시피를 추출하지 못했습니다.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        // 서버는 레시피 모델을 90초씩 최대 2회 호출할 수 있다.
        const val READ_TIMEOUT_MS = 200_000
    }
}

internal fun parseRecipeExtractionResponse(body: String): RecipeImportResult {
    val root = JSONObject(body)
    val recipeJson = root.getJSONObject("recipe")
    val ingredientsJson = recipeJson.getJSONArray("ingredients")
    val ingredients = List(ingredientsJson.length()) { index ->
        ingredientsJson.getJSONObject(index).let {
            Ingredient(name = it.getString("name"), amount = it.getString("amount"))
        }
    }
    val stepsJson = recipeJson.getJSONArray("steps")
    val steps = List(stepsJson.length()) { index ->
        stepsJson.getJSONObject(index).toRecipeStep(index + 1)
    }
    val warningsJson = root.optJSONArray("warnings") ?: JSONArray()
    val sourceTitle = root.optJSONObject("source")?.optString("title").orEmpty()
    return RecipeImportResult(
        recipe = Recipe(
            id = "",
            title = recipeJson.getString("title"),
            ingredients = ingredients,
            steps = steps,
            heroNote = recipeJson.optString("heroNote", "YouTube 자막에서 추출 · 저장 전 확인"),
            isMvpReady = false
        ),
        warnings = List(warningsJson.length()) { warningsJson.getString(it) },
        sourceTitle = sourceTitle
    )
}

private fun JSONObject.toRecipeStep(order: Int): RecipeStep {
    val instruction = getString("instruction")
    val checkType = serverCheckTypeToApp(getString("checkType"))
    val policy = optJSONObject("inspectionPolicy")?.let {
        InspectionPolicy(
            earliestCheckSeconds = it.getInt("earliestCheckSeconds"),
            checkIntervalSeconds = it.getInt("checkIntervalSeconds"),
            burstSeconds = it.getInt("burstSeconds"),
            requiredConsecutiveDone = it.getInt("requiredConsecutiveDone"),
            maxExpectedSeconds = it.getInt("maxExpectedSeconds")
        )
    }
    val timer = optJSONObject("parallelTimer")?.let {
        ParallelTimer(
            label = it.getString("label"),
            durationSeconds = it.getInt("durationSeconds"),
            doneAnnouncement = it.getString("doneAnnouncement")
        )
    }
    val targets = optJSONArray("targetIngredients") ?: JSONArray()
    return RecipeStep(
        order = order,
        instruction = instruction,
        checkType = checkType,
        checkCondition = optNullableString("checkCondition"),
        needsStartImage = optBoolean("needsStartImage"),
        inspectionPolicy = policy,
        targetIngredients = List(targets.length()) { targets.getString(it) },
        voicePrompt = optString("voicePrompt").takeIf(String::isNotBlank) ?: instruction,
        isAutoCheck = optBoolean("isAutoCheck", checkType != CheckType.TIMER_ONLY),
        parallelTimer = timer,
        waitsForParallelTimer = optBoolean("waitsForParallelTimer"),
        baselineOnStepStart = optBoolean("baselineOnStepStart")
    )
}

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotBlank)

internal fun serverCheckTypeToApp(value: String): CheckType = when (value) {
    "PRESENCE" -> CheckType.PRESENCE
    "COUNT" -> CheckType.COUNT
    "IDENTIFY" -> CheckType.IDENTIFICATION
    "COLOR_CHANGE" -> CheckType.COLOR_CHANGE
    "STATE_CHANGE" -> CheckType.STATE_TRANSITION
    "TIME_ONLY" -> CheckType.TIMER_ONLY
    else -> throw RecipeImportException("지원하지 않는 판정 유형입니다: $value")
}
