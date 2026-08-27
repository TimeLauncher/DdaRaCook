package com.example.myapplication.recipeimport

import com.example.myapplication.BuildConfig
import com.example.myapplication.CheckType
import com.example.myapplication.Ingredient
import com.example.myapplication.ImageCropTarget
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

/**
 * 서버의 `parse_youtube_video_id` 가 받아주는 형태인지 미리 본다.
 *
 * 왕복 한 번과 400 오류를 아끼려는 것뿐이므로 video id 자체는 검사하지 않는다.
 * 최종 판정은 언제나 서버가 한다.
 */
internal fun looksLikeYoutubeLink(value: String): Boolean {
    val url = value.trim()
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        return false
    }
    val host = runCatching { URL(url).host }.getOrNull()?.lowercase()
        ?.removePrefix("www.")?.removePrefix("m.") ?: return false
    return host == "youtu.be" || host == "youtube.com" || host == "music.youtube.com"
}

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

        /**
         * 서버의 최악 소요 시간보다 **길어야** 한다.
         *
         * 서버 예산(recipe_extractor.py): 호스팅 자막 45초 + 제목 조회 8초 + 모델 150초 = 203초.
         * 이보다 짧으면 서버가 자기 오류 메시지("모델 응답 시간이 초과되었습니다")를 낼 틈도 없이
         * 앱이 먼저 소켓을 끊어, 원인을 알 수 없는 일반 타임아웃만 사용자에게 남는다.
         * 서버 예산을 바꾸면 이 값도 함께 올린다.
         */
        const val READ_TIMEOUT_MS = 230_000
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
    val isAutoCheck = optBoolean("isAutoCheck", checkType != CheckType.TIMER_ONLY)
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
        isAutoCheck = isAutoCheck,
        imageCropTarget = importedStepCropTarget(isAutoCheck),
        parallelTimer = timer,
        waitsForParallelTimer = optBoolean("waitsForParallelTimer"),
        baselineOnStepStart = optBoolean("baselineOnStepStart")
    )
}

internal fun importedStepCropTarget(isAutoCheck: Boolean): ImageCropTarget =
    if (isAutoCheck) ImageCropTarget.AUTO_ROI else ImageCropTarget.LEGACY_BOTTOM_60

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
