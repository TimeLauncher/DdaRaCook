package com.example.myapplication.judgment

import com.example.myapplication.CheckType
import com.example.myapplication.ReasonCode
import com.example.myapplication.JudgmentVerdict
import kotlinx.coroutines.flow.StateFlow

data class JudgmentRequest(
    val requestId: String,
    val cookingSessionId: String,
    val recipeId: String,
    val stepOrder: Int,
    val instruction: String,
    val checkType: CheckType,
    val checkCondition: String?,
    /** CONTRACT §3.2 — 완료 조건이 시작 시점 대비 변화를 묻는가. `RecipeStep`에서 그대로 온다. */
    val needsStartImage: Boolean,
    val elapsedSeconds: Int,
    val baselineImageUri: String?,
    val currentImageUri: String,
    val requestedAtMs: Long = System.currentTimeMillis()
)

data class JudgmentResult(
    val requestId: String,
    val cookingSessionId: String,
    val stepOrder: Int,
    val verdict: JudgmentVerdict,
    val reasonCode: ReasonCode,
    val roundTripMs: Long,
    val vlmLatencyMs: Long? = null,
    val requestedAtMs: Long = 0L,
    val respondedAtMs: Long = System.currentTimeMillis()
)

sealed interface JudgmentOutcome {
    data class Success(val result: JudgmentResult) : JudgmentOutcome
    data class Failure(
        val requestId: String,
        val message: String,
        val retryable: Boolean,
        val requestedAtMs: Long = 0L,
        val respondedAtMs: Long = System.currentTimeMillis()
    ) : JudgmentOutcome
}

sealed interface JudgmentGatewayState {
    data object Idle : JudgmentGatewayState
    data object Judging : JudgmentGatewayState
    data object Released : JudgmentGatewayState
}

interface JudgmentGateway {
    val state: StateFlow<JudgmentGatewayState>

    suspend fun judge(request: JudgmentRequest): JudgmentOutcome

    suspend fun release()
}
