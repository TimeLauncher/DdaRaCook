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
    val elapsedSeconds: Int,
    val baselineImageUri: String?,
    val currentImageUri: String
)

data class JudgmentResult(
    val requestId: String,
    val verdict: JudgmentVerdict,
    val reasonCode: ReasonCode,
    val roundTripMs: Long,
    val vlmLatencyMs: Long? = null
)

sealed interface JudgmentOutcome {
    data class Success(val result: JudgmentResult) : JudgmentOutcome
    data class Failure(
        val requestId: String,
        val message: String,
        val retryable: Boolean
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
