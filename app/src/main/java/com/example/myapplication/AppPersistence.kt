package com.example.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPersistence(context: Context, preferenceName: String = "ttaracook_state") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun loadRecipes(fallback: List<Recipe>): List<Recipe> = runCatching {
        val raw = preferences.getString(KEY_RECIPES, null) ?: return fallback
        val stored = JSONArray(raw).toRecipeList()
        if (preferences.getInt(KEY_FIXTURE_VERSION, 0) < CURRENT_FIXTURE_VERSION) {
            val builtInIds = fallback.mapTo(mutableSetOf(), Recipe::id)
            val migrated = fallback + stored.filterNot { it.id in builtInIds }
            saveRecipes(migrated)
            migrated
        } else {
            stored.ifEmpty { fallback }
        }
    }.getOrDefault(fallback)

    fun saveRecipes(recipes: List<Recipe>) {
        preferences.edit()
            .putString(KEY_RECIPES, recipes.toJson().toString())
            .putInt(KEY_FIXTURE_VERSION, CURRENT_FIXTURE_VERSION)
            .apply()
    }

    fun loadSession(): CookingSession? = runCatching {
        preferences.getString(KEY_SESSION, null)?.let { JSONObject(it).toSession() }
    }.getOrNull()

    fun saveSession(session: CookingSession?) {
        val editor = preferences.edit()
        if (session == null) editor.remove(KEY_SESSION) else editor.putString(KEY_SESSION, session.toJson().toString())
        editor.apply()
    }

    fun loadServerBaseUrl(fallback: String): String =
        preferences.getString(KEY_SERVER_BASE_URL, null)?.takeIf(String::isNotBlank) ?: fallback

    fun saveServerBaseUrl(value: String) {
        preferences.edit().putString(KEY_SERVER_BASE_URL, value).apply()
    }

    fun loadUseMockJudgment(fallback: Boolean = false): Boolean =
        if (preferences.contains(KEY_USE_MOCK_JUDGMENT)) {
            preferences.getBoolean(KEY_USE_MOCK_JUDGMENT, fallback)
        } else {
            fallback
        }

    fun saveUseMockJudgment(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_USE_MOCK_JUDGMENT, enabled).apply()
    }

    internal fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_RECIPES = "recipes"
        const val KEY_SESSION = "active_session"
        const val KEY_SERVER_BASE_URL = "server_base_url"
        const val KEY_USE_MOCK_JUDGMENT = "use_mock_judgment"
        const val KEY_FIXTURE_VERSION = "recipe_fixture_version"
        const val CURRENT_FIXTURE_VERSION = 4
    }
}

private fun List<Recipe>.toJson() = JSONArray().also { array ->
    forEach { recipe ->
        array.put(JSONObject().apply {
            put("id", recipe.id)
            put("title", recipe.title)
            put("heroNote", recipe.heroNote)
            put("isMvpReady", recipe.isMvpReady)
            put("ingredients", JSONArray().also { ingredients ->
                recipe.ingredients.forEach { ingredient ->
                    ingredients.put(JSONObject().put("name", ingredient.name).put("amount", ingredient.amount))
                }
            })
            put("steps", JSONArray().also { steps ->
                recipe.steps.forEach { step ->
                    steps.put(JSONObject().apply {
                        put("order", step.order)
                        put("instruction", step.instruction)
                        put("checkType", step.checkType.name)
                        put("checkCondition", step.checkCondition)
                        put("needsStartImage", step.needsStartImage)
                        put("targetIngredients", JSONArray(step.targetIngredients))
                        put("voicePrompt", step.voicePrompt)
                        put("isAutoCheck", step.isAutoCheck)
                        step.parallelTimer?.let { timer ->
                            put("parallelTimer", JSONObject().apply {
                                put("label", timer.label)
                                put("durationSeconds", timer.durationSeconds)
                                put("doneAnnouncement", timer.doneAnnouncement)
                            })
                        }
                        step.inspectionPolicy?.let { policy ->
                            put("inspectionPolicy", JSONObject().apply {
                                put("earliestCheckSeconds", policy.earliestCheckSeconds)
                                put("checkIntervalSeconds", policy.checkIntervalSeconds)
                                put("burstSeconds", policy.burstSeconds)
                                put("requiredConsecutiveDone", policy.requiredConsecutiveDone)
                                put("maxExpectedSeconds", policy.maxExpectedSeconds)
                            })
                        }
                    })
                }
            })
        })
    }
}

private fun JSONArray.toRecipeList(): List<Recipe> = buildList {
    for (index in 0 until length()) {
        val json = getJSONObject(index)
        val ingredientsJson = json.getJSONArray("ingredients")
        val ingredients = buildList {
            for (ingredientIndex in 0 until ingredientsJson.length()) {
                val ingredient = ingredientsJson.getJSONObject(ingredientIndex)
                add(Ingredient(ingredient.getString("name"), ingredient.getString("amount")))
            }
        }
        val stepsJson = json.getJSONArray("steps")
        val steps = buildList {
            for (stepIndex in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(stepIndex)
                val policy = step.optJSONObject("inspectionPolicy")?.let {
                    InspectionPolicy(
                        earliestCheckSeconds = it.getInt("earliestCheckSeconds"),
                        checkIntervalSeconds = it.getInt("checkIntervalSeconds"),
                        burstSeconds = it.getInt("burstSeconds"),
                        requiredConsecutiveDone = it.getInt("requiredConsecutiveDone"),
                        maxExpectedSeconds = it.getInt("maxExpectedSeconds")
                    )
                }
                val parallelTimer = step.optJSONObject("parallelTimer")?.let {
                    ParallelTimer(
                        label = it.getString("label"),
                        durationSeconds = it.getInt("durationSeconds"),
                        doneAnnouncement = it.getString("doneAnnouncement")
                    )
                }
                val targetsJson = step.getJSONArray("targetIngredients")
                val checkType = enumValueOf<CheckType>(step.getString("checkType"))
                add(
                    RecipeStep(
                        order = stepIndex + 1,
                        instruction = step.getString("instruction"),
                        checkType = checkType,
                        checkCondition = step.optString("checkCondition").takeIf(String::isNotBlank),
                        // 이 키가 없는 옛 저장본은 CONTRACT §3.2 v1.2 규칙(COLOR_CHANGE 만 전송)으로 읽는다.
                        needsStartImage = step.optBoolean("needsStartImage", checkType == CheckType.COLOR_CHANGE),
                        inspectionPolicy = policy,
                        targetIngredients = List(targetsJson.length()) { targetsJson.getString(it) },
                        voicePrompt = step.getString("voicePrompt"),
                        isAutoCheck = step.getBoolean("isAutoCheck"),
                        parallelTimer = parallelTimer
                    )
                )
            }
        }
        add(
            Recipe(
                id = json.getString("id"),
                title = json.getString("title"),
                ingredients = ingredients,
                steps = steps,
                heroNote = json.getString("heroNote"),
                isMvpReady = json.getBoolean("isMvpReady")
            )
        )
    }
}

private fun CookingSession.toJson() = JSONObject().apply {
    put("id", id)
    put("recipeId", recipeId)
    put("phase", phase.name)
    put("mode", mode.name)
    put("currentStepIndex", currentStepIndex)
    put("cannotTellStreak", cannotTellStreak)
    put("consecutiveDoneCount", consecutiveDoneCount)
    put("networkFailureCount", networkFailureCount)
    put("autoDoneCount", autoDoneCount)
    put("notDoneCount", notDoneCount)
    put("cannotTellCount", cannotTellCount)
    put("manualNextCount", manualNextCount)
    put("undoDoneCount", undoDoneCount)
    put("cameraActiveMs", cameraActiveMs)
    put("startedAtMs", startedAtMs)
    put("completedAtMs", completedAtMs)
    put("currentStepStartedAtMs", currentStepStartedAtMs)
    put("lastCaptureUriByStep", lastCaptureUriByStep.toJson())
    put("baselineUriByStep", baselineUriByStep.toJson())
    put("stepStartedAtMsByOrder", stepStartedAtMsByOrder.toJson())
    put("stepCompletedAtMsByOrder", stepCompletedAtMsByOrder.toJson())
    put("completedStepOrders", JSONArray(completedStepOrders.toList()))
    put("currentVerdict", currentVerdict?.name)
    put("lastRoundTripMs", lastRoundTripMs)
    put("lastVlmLatencyMs", lastVlmLatencyMs)
    put("lastReasonCode", lastReasonCode?.name)
    // 앱을 껐다 켜도 냄비는 계속 끓는다. 남은 시간이 아니라 만료 **시각**을 저장한다.
    put("parallelTimerEndsAtMs", parallelTimerEndsAtMs)
    put("parallelTimerLabel", parallelTimerLabel)
    put("parallelTimerMessage", parallelTimerMessage)
    put("parallelTimerFired", parallelTimerFired)
    put("logs", JSONArray().also { array -> logs.forEach { array.put(it.toJson()) } })
}

private fun JSONObject.toSession(): CookingSession {
    val restoredPhase = enumValueOf<CookingPhase>(getString("phase"))
    return CookingSession(
        id = getString("id"),
        recipeId = getString("recipeId"),
        phase = if (restoredPhase in setOf(CookingPhase.CAPTURING, CookingPhase.JUDGING, CookingPhase.PROMPTING_USER)) CookingPhase.WAITING_FOR_CHECK else restoredPhase,
        mode = enumValueOf(getString("mode")),
        currentStepIndex = getInt("currentStepIndex"),
        cannotTellStreak = optInt("cannotTellStreak"),
        consecutiveDoneCount = optInt("consecutiveDoneCount"),
        networkFailureCount = optInt("networkFailureCount"),
        autoDoneCount = optInt("autoDoneCount"),
        notDoneCount = optInt("notDoneCount"),
        cannotTellCount = optInt("cannotTellCount"),
        manualNextCount = optInt("manualNextCount"),
        undoDoneCount = optInt("undoDoneCount"),
        cameraActiveMs = optLong("cameraActiveMs"),
        lastCaptureUriByStep = optJSONObject("lastCaptureUriByStep").toStringMap(),
        baselineUriByStep = optJSONObject("baselineUriByStep").toStringMap(),
        logs = optJSONArray("logs").toLogs(),
        activeRequestId = null,
        currentVerdict = optString("currentVerdict").enumOrNull<JudgmentVerdict>(),
        lastRoundTripMs = optNullableLong("lastRoundTripMs"),
        lastVlmLatencyMs = optNullableLong("lastVlmLatencyMs"),
        startedAtMs = optLong("startedAtMs", System.currentTimeMillis()),
        completedAtMs = optNullableLong("completedAtMs"),
        currentStepStartedAtMs = optLong("currentStepStartedAtMs", System.currentTimeMillis()),
        stepStartedAtMsByOrder = optJSONObject("stepStartedAtMsByOrder").toLongMap(),
        stepCompletedAtMsByOrder = optJSONObject("stepCompletedAtMsByOrder").toLongMap(),
        completedStepOrders = optJSONArray("completedStepOrders").toIntSet(),
        lastReasonCode = optString("lastReasonCode").enumOrNull<ReasonCode>(),
        parallelTimerEndsAtMs = optNullableLong("parallelTimerEndsAtMs"),
        parallelTimerLabel = optString("parallelTimerLabel").takeIf(String::isNotBlank),
        parallelTimerMessage = optString("parallelTimerMessage").takeIf(String::isNotBlank),
        parallelTimerFired = optBoolean("parallelTimerFired")
    )
}

private fun SessionLogEntry.toJson() = JSONObject().apply {
    put("timestampMs", timestampMs)
    put("stepOrder", stepOrder)
    put("message", message)
    put("verdict", verdict?.name)
    put("roundTripMs", roundTripMs)
    put("vlmLatencyMs", vlmLatencyMs)
    put("reasonCode", reasonCode?.name)
    put("requestId", requestId)
    put("eventType", eventType)
    put("manualOverride", manualOverride)
    put("overrideType", overrideType)
    put("requestedAtMs", requestedAtMs)
    put("respondedAtMs", respondedAtMs)
    put("imageUri", imageUri)
    put("groundTruth", groundTruth?.name)
}

private fun JSONArray?.toLogs(): List<SessionLogEntry> = if (this == null) emptyList() else buildList {
    for (index in 0 until length()) {
        val json = getJSONObject(index)
        add(SessionLogEntry(
            timestampMs = json.getLong("timestampMs"),
            stepOrder = json.getInt("stepOrder"),
            message = json.getString("message"),
            verdict = json.optString("verdict").enumOrNull<JudgmentVerdict>(),
            roundTripMs = json.optNullableLong("roundTripMs"),
            vlmLatencyMs = json.optNullableLong("vlmLatencyMs"),
            reasonCode = json.optString("reasonCode").enumOrNull<ReasonCode>(),
            requestId = json.optString("requestId").takeIf(String::isNotBlank),
            eventType = json.optString("eventType", "INFO"),
            manualOverride = json.optBoolean("manualOverride"),
            overrideType = json.optString("overrideType").takeIf(String::isNotBlank),
            requestedAtMs = json.optNullableLong("requestedAtMs"),
            respondedAtMs = json.optNullableLong("respondedAtMs"),
            imageUri = json.optString("imageUri").takeIf(String::isNotBlank),
            groundTruth = json.optString("groundTruth").enumOrNull<JudgmentVerdict>()
        ))
    }
}

private fun Map<Int, *>.toJson() = JSONObject().also { json -> forEach { (key, value) -> json.put(key.toString(), value) } }
private fun JSONObject?.toStringMap(): Map<Int, String> = if (this == null) emptyMap() else keys().asSequence().associate { it.toInt() to getString(it) }
private fun JSONObject?.toLongMap(): Map<Int, Long> = if (this == null) emptyMap() else keys().asSequence().associate { it.toInt() to getLong(it) }
private fun JSONArray?.toIntSet(): Set<Int> = if (this == null) emptySet() else (0 until length()).mapTo(mutableSetOf()) { getInt(it) }
private fun JSONObject.optNullableLong(name: String): Long? = if (isNull(name) || !has(name)) null else getLong(name)
private inline fun <reified T : Enum<T>> String.enumOrNull(): T? = takeIf(String::isNotBlank)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
