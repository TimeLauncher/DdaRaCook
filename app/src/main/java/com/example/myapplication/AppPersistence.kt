package com.example.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppPersistence(context: Context, preferenceName: String = "ttaracook_state") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun loadRecipes(fallback: List<Recipe>): List<Recipe> = runCatching {
        val raw = preferences.getString(KEY_RECIPES, null) ?: return fallback
        JSONArray(raw).toRecipeList().ifEmpty { fallback }
    }.getOrDefault(fallback)

    fun saveRecipes(recipes: List<Recipe>) {
        preferences.edit().putString(KEY_RECIPES, recipes.toJson().toString()).apply()
    }

    fun loadSession(): CookingSession? = runCatching {
        preferences.getString(KEY_SESSION, null)?.let { JSONObject(it).toSession() }
    }.getOrNull()

    fun saveSession(session: CookingSession?) {
        preferences.edit().apply {
            if (session == null) remove(KEY_SESSION) else putString(KEY_SESSION, session.toJson().toString())
        }.apply()
    }

    internal fun clear() = preferences.edit().clear().commit()

    private companion object {
        const val KEY_RECIPES = "recipes"
        const val KEY_SESSION = "active_session"
    }
}

private fun List<Recipe>.toJson() = JSONArray().also { array ->
    forEach { recipe ->
        array.put(JSONObject().apply {
            put("id", recipe.id); put("title", recipe.title); put("heroNote", recipe.heroNote); put("isMvpReady", recipe.isMvpReady)
            put("ingredients", JSONArray().also { ingredients -> recipe.ingredients.forEach { ingredients.put(JSONObject().put("name", it.name).put("amount", it.amount)) } })
            put("steps", JSONArray().also { steps -> recipe.steps.forEach { step ->
                steps.put(JSONObject().apply {
                    put("instruction", step.instruction); put("checkType", step.checkType.name); put("checkCondition", step.checkCondition)
                    put("targetIngredients", JSONArray(step.targetIngredients)); put("voicePrompt", step.voicePrompt); put("helperText", step.helperText); put("isAutoCheck", step.isAutoCheck)
                    step.inspectionPolicy?.let { policy -> put("policy", JSONObject().apply {
                        put("earliest", policy.earliestCheckSeconds); put("interval", policy.checkIntervalSeconds); put("burst", policy.burstSeconds)
                        put("consecutive", policy.requiredConsecutiveDone); put("maximum", policy.maxExpectedSeconds)
                    }) }
                })
            } })
        })
    }
}

private fun JSONArray.toRecipeList(): List<Recipe> = buildList {
    for (index in 0 until length()) {
        val json = getJSONObject(index)
        val ingredientJson = json.getJSONArray("ingredients")
        val ingredients = List(ingredientJson.length()) { i -> ingredientJson.getJSONObject(i).let { Ingredient(it.getString("name"), it.getString("amount")) } }
        val stepJson = json.getJSONArray("steps")
        val steps = List(stepJson.length()) { i ->
            val step = stepJson.getJSONObject(i)
            val policy = step.optJSONObject("policy")?.let { InspectionPolicy(it.getInt("earliest"), it.getInt("interval"), it.getInt("burst"), it.getInt("consecutive"), it.getInt("maximum")) }
            val targets = step.getJSONArray("targetIngredients")
            RecipeStep(i + 1, step.getString("instruction"), enumValueOf(step.getString("checkType")), step.optString("checkCondition").takeIf(String::isNotBlank), policy,
                List(targets.length()) { targets.getString(it) }, step.getString("voicePrompt"), step.getString("helperText"), step.getBoolean("isAutoCheck"))
        }
        add(Recipe(json.getString("id"), json.getString("title"), ingredients, steps, json.getString("heroNote"), json.getBoolean("isMvpReady")))
    }
}

private fun CookingSession.toJson() = JSONObject().apply {
    put("id", id); put("recipeId", recipeId); put("phase", phase.name); put("mode", mode.name); put("currentStepIndex", currentStepIndex)
    put("cannotTellStreak", cannotTellStreak); put("consecutiveDoneCount", consecutiveDoneCount); put("networkFailureCount", networkFailureCount)
    put("autoDoneCount", autoDoneCount); put("notDoneCount", notDoneCount); put("cannotTellCount", cannotTellCount); put("manualNextCount", manualNextCount); put("undoDoneCount", undoDoneCount)
    put("cameraActiveMs", cameraActiveMs); put("startedAtMs", startedAtMs); put("completedAtMs", completedAtMs); put("currentStepStartedAtMs", currentStepStartedAtMs)
    put("lastCaptureUriByStep", lastCaptureUriByStep.mapJson()); put("baselineUriByStep", baselineUriByStep.mapJson())
    put("stepStartedAtMsByOrder", stepStartedAtMsByOrder.mapJson()); put("stepCompletedAtMsByOrder", stepCompletedAtMsByOrder.mapJson())
    put("completedStepOrders", JSONArray(completedStepOrders.toList())); put("currentVerdict", currentVerdict?.name); put("lastRoundTripMs", lastRoundTripMs)
    put("lastVlmLatencyMs", lastVlmLatencyMs); put("lastReasonCode", lastReasonCode?.name)
    put("logs", JSONArray().also { array -> logs.forEach { log -> array.put(log.toJson()) } })
}

private fun JSONObject.toSession(): CookingSession {
    val storedPhase = enumValueOf<CookingPhase>(getString("phase"))
    return CookingSession(
        id = getString("id"), recipeId = getString("recipeId"),
        phase = if (storedPhase in setOf(CookingPhase.CAPTURING, CookingPhase.JUDGING, CookingPhase.PROMPTING_USER)) CookingPhase.WAITING_FOR_CHECK else storedPhase,
        mode = enumValueOf(getString("mode")), currentStepIndex = getInt("currentStepIndex"), cannotTellStreak = optInt("cannotTellStreak"),
        consecutiveDoneCount = optInt("consecutiveDoneCount"), networkFailureCount = optInt("networkFailureCount"), autoDoneCount = optInt("autoDoneCount"),
        notDoneCount = optInt("notDoneCount"), cannotTellCount = optInt("cannotTellCount"), manualNextCount = optInt("manualNextCount"), undoDoneCount = optInt("undoDoneCount"),
        cameraActiveMs = optLong("cameraActiveMs"), lastCaptureUriByStep = optJSONObject("lastCaptureUriByStep").stringMap(), baselineUriByStep = optJSONObject("baselineUriByStep").stringMap(),
        logs = optJSONArray("logs").logs(), activeRequestId = null, currentVerdict = optString("currentVerdict").enumOrNull<JudgmentVerdict>(),
        lastRoundTripMs = nullableLong("lastRoundTripMs"), lastVlmLatencyMs = nullableLong("lastVlmLatencyMs"), startedAtMs = optLong("startedAtMs", System.currentTimeMillis()),
        completedAtMs = nullableLong("completedAtMs"), currentStepStartedAtMs = optLong("currentStepStartedAtMs", System.currentTimeMillis()),
        stepStartedAtMsByOrder = optJSONObject("stepStartedAtMsByOrder").longMap(), stepCompletedAtMsByOrder = optJSONObject("stepCompletedAtMsByOrder").longMap(),
        completedStepOrders = optJSONArray("completedStepOrders").intSet(), lastReasonCode = optString("lastReasonCode").enumOrNull<ReasonCode>()
    )
}

private fun SessionLogEntry.toJson() = JSONObject().apply {
    put("timestampMs", timestampMs); put("stepOrder", stepOrder); put("message", message); put("verdict", verdict?.name); put("roundTripMs", roundTripMs)
    put("vlmLatencyMs", vlmLatencyMs); put("reasonCode", reasonCode?.name); put("requestId", requestId); put("eventType", eventType); put("manualOverride", manualOverride); put("overrideType", overrideType)
}

private fun JSONArray?.logs(): List<SessionLogEntry> = if (this == null) emptyList() else List(length()) { i -> getJSONObject(i).let { json ->
    SessionLogEntry(json.getLong("timestampMs"), json.getInt("stepOrder"), json.getString("message"), json.optString("verdict").enumOrNull<JudgmentVerdict>(),
        json.nullableLong("roundTripMs"), json.nullableLong("vlmLatencyMs"), json.optString("reasonCode").enumOrNull<ReasonCode>(),
        json.optString("requestId").takeIf(String::isNotBlank), json.optString("eventType", "INFO"), json.optBoolean("manualOverride"), json.optString("overrideType").takeIf(String::isNotBlank))
} }
private fun Map<Int, *>.mapJson() = JSONObject().also { json -> forEach { (key, value) -> json.put(key.toString(), value) } }
private fun JSONObject?.stringMap() = if (this == null) emptyMap() else keys().asSequence().associate { it.toInt() to getString(it) }
private fun JSONObject?.longMap() = if (this == null) emptyMap() else keys().asSequence().associate { it.toInt() to getLong(it) }
private fun JSONArray?.intSet() = if (this == null) emptySet() else (0 until length()).mapTo(mutableSetOf()) { getInt(it) }
private fun JSONObject.nullableLong(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
private inline fun <reified T : Enum<T>> String.enumOrNull(): T? = takeIf(String::isNotBlank)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
