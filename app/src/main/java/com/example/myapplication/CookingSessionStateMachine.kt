package com.example.myapplication

data class CookingSession(
    val id: String,
    val recipeId: String,
    val phase: CookingPhase = CookingPhase.IDLE,
    val mode: SessionMode = SessionMode.AUTO,
    val currentStepIndex: Int = 0,
    val cannotTellStreak: Int = 0,
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
    val lastVlmLatencyMs: Long? = null
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
    RESUME_AUTO
}

data class SessionLogEntry(
    val timestampMs: Long,
    val stepOrder: Int,
    val message: String,
    val verdict: JudgmentVerdict? = null,
    val roundTripMs: Long? = null,
    val vlmLatencyMs: Long? = null
)
