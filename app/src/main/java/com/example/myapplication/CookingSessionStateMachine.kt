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
    val lastReasonCode: ReasonCode? = null
)

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
    INGREDIENTS
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
    val overrideType: String? = null
)

val CookingSession.totalDurationMs: Long
    get() = ((completedAtMs ?: System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0L)

fun CookingSession.stepDurationMs(stepOrder: Int, nowMs: Long = System.currentTimeMillis()): Long? {
    val started = stepStartedAtMsByOrder[stepOrder] ?: return null
    val ended = stepCompletedAtMsByOrder[stepOrder]
        ?: if (currentStepIndex + 1 == stepOrder) nowMs else return null
    return (ended - started).coerceAtLeast(0L)
}
