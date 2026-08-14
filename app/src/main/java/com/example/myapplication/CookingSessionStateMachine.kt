package com.example.myapplication

data class CookingSession(
    val id: String,
    val recipeId: String,
    val phase: CookingPhase = CookingPhase.IDLE,
    val mode: SessionMode = SessionMode.AUTO,
    val currentStepIndex: Int = 0,
    val cannotTellStreak: Int = 0,
    val consecutiveDoneCount: Int = 0,
    val networkFailureCount: Int = 0,
    val autoDoneCount: Int = 0,
    val notDoneCount: Int = 0,
    val cannotTellCount: Int = 0,
    val manualNextCount: Int = 0,
    val undoDoneCount: Int = 0,
    val cameraActiveMs: Long = 0L,
    val lastCaptureUriByStep: Map<Int, String> = emptyMap(),
    val baselineUriByStep: Map<Int, String> = emptyMap(),
    val logs: List<SessionLogEntry> = emptyList(),
    val activeRequestId: String? = null,
    val currentVerdict: JudgmentVerdict? = null,
    val lastRoundTripMs: Long? = null,
    val lastVlmLatencyMs: Long? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
    val currentStepStartedAtMs: Long = System.currentTimeMillis(),
    val stepStartedAtMsByOrder: Map<Int, Long> = emptyMap(),
    val stepCompletedAtMsByOrder: Map<Int, Long> = emptyMap(),
    val completedStepOrders: Set<Int> = emptySet(),
    val lastReasonCode: ReasonCode? = null,
    /** 병렬 타이머 만료 **절대 시각**. 단계가 바뀌어도 유지된다 (`RecipeStep.parallelTimer`). */
    val parallelTimerEndsAtMs: Long? = null,
    val parallelTimerLabel: String? = null,
    val parallelTimerMessage: String? = null,
    val parallelTimerFired: Boolean = false
)

/** 병렬 타이머 남은 초. 타이머가 없으면 null, 다 됐으면 0. */
fun CookingSession.parallelTimerRemainingSeconds(nowMs: Long = System.currentTimeMillis()): Int? {
    val endsAt = parallelTimerEndsAtMs ?: return null
    val remainingMs = (endsAt - nowMs).coerceAtLeast(0L)
    return ((remainingMs + 999L) / 1_000L).toInt()
}

enum class CookingPhase {
    IDLE,
    PREPARING_DEVICE,
    READY,
    STEP_STARTING,
    WAITING_FOR_CHECK,
    PROMPTING_USER,
    CAPTURING,
    JUDGING,
    NETWORK_RETRY,
    NEEDS_VIEW,
    STEP_COMPLETED,
    MANUAL_MODE,
    SESSION_COMPLETED
}

enum class SessionMode {
    AUTO,
    MANUAL_ONLY
}

enum class VoiceCommand {
    NEXT,
    NOT_YET,
    REPEAT,
    PREVIOUS,
    CHECK_NOW,
    RESUME_AUTO,
    INGREDIENTS,
    VERIFY_INGREDIENT,
    CURRENT_STEP
}

data class SessionLogEntry(
    val timestampMs: Long,
    val stepOrder: Int,
    val message: String,
    val verdict: JudgmentVerdict? = null,
    val roundTripMs: Long? = null,
    val vlmLatencyMs: Long? = null,
    val reasonCode: ReasonCode? = null,
    val requestId: String? = null,
    val eventType: String = "INFO",
    val manualOverride: Boolean = false,
    val overrideType: String? = null,
    val requestedAtMs: Long? = null,
    val respondedAtMs: Long? = null,
    val imageUri: String? = null,
    val groundTruth: JudgmentVerdict? = null
)

data class EvaluationMetrics(
    val labeledCount: Int,
    val correctCount: Int,
    val falsePositiveCount: Int,
    val missedDoneCount: Int,
    val cannotTellCount: Int
) {
    val accuracyPercent: Int
        get() = if (labeledCount == 0) 0 else (correctCount * 100) / labeledCount
}

fun CookingSession.evaluationMetrics(): EvaluationMetrics {
    val labeled = logs.filter { it.verdict != null && it.groundTruth != null }
    return EvaluationMetrics(
        labeledCount = labeled.size,
        correctCount = labeled.count { it.verdict == it.groundTruth },
        falsePositiveCount = labeled.count { it.verdict == JudgmentVerdict.DONE && it.groundTruth != JudgmentVerdict.DONE },
        missedDoneCount = labeled.count { it.verdict != JudgmentVerdict.DONE && it.groundTruth == JudgmentVerdict.DONE },
        cannotTellCount = labeled.count { it.verdict == JudgmentVerdict.CANNOT_TELL }
    )
}

val CookingSession.totalDurationMs: Long
    get() = ((completedAtMs ?: System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0L)

fun CookingSession.stepDurationMs(stepOrder: Int, nowMs: Long = System.currentTimeMillis()): Long? {
    val started = stepStartedAtMsByOrder[stepOrder] ?: return null
    val ended = stepCompletedAtMsByOrder[stepOrder] ?: if (currentStepIndex + 1 == stepOrder) nowMs else return null
    return (ended - started).coerceAtLeast(0L)
}
