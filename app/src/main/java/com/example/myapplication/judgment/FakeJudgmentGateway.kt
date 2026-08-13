package com.example.myapplication.judgment

import com.example.myapplication.JudgmentVerdict
import com.example.myapplication.ReasonCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.measureTimeMillis

class FakeJudgmentGateway : JudgmentGateway {
    private val mutableState = MutableStateFlow<JudgmentGatewayState>(JudgmentGatewayState.Idle)
    override val state: StateFlow<JudgmentGatewayState> = mutableState.asStateFlow()

    private var behavior: FakeJudgmentBehavior = FakeJudgmentBehavior.Success(
        verdict = JudgmentVerdict.DONE,
        reasonCode = ReasonCode.VISIBLE_CHANGE
    )

    fun setBehavior(behavior: FakeJudgmentBehavior) {
        this.behavior = behavior
    }

    override suspend fun judge(request: JudgmentRequest): JudgmentOutcome {
        mutableState.value = JudgmentGatewayState.Judging
        return try {
            when (val selected = behavior) {
                is FakeJudgmentBehavior.Success -> {
                    var roundTrip = 0L
                    roundTrip = measureTimeMillis { delay(selected.delayMs) }
                    JudgmentOutcome.Success(
                        JudgmentResult(
                            requestId = request.requestId,
                            cookingSessionId = request.cookingSessionId,
                            stepOrder = request.stepOrder,
                            verdict = selected.verdict,
                            reasonCode = selected.reasonCode,
                            roundTripMs = roundTrip,
                            vlmLatencyMs = selected.vlmLatencyMs,
                            requestedAtMs = request.requestedAtMs,
                            respondedAtMs = System.currentTimeMillis()
                        )
                    )
                }

                is FakeJudgmentBehavior.Failure -> {
                    delay(selected.delayMs)
                    JudgmentOutcome.Failure(
                        requestId = request.requestId,
                        message = selected.message,
                        retryable = selected.retryable,
                        requestedAtMs = request.requestedAtMs,
                        respondedAtMs = System.currentTimeMillis()
                    )
                }
            }
        } finally {
            mutableState.value = JudgmentGatewayState.Idle
        }
    }

    override suspend fun release() {
        mutableState.value = JudgmentGatewayState.Released
    }
}

sealed interface FakeJudgmentBehavior {
    data class Success(
        val verdict: JudgmentVerdict,
        val reasonCode: ReasonCode,
        val delayMs: Long = 1_000L,
        val vlmLatencyMs: Long? = delayMs
    ) : FakeJudgmentBehavior

    data class Failure(
        val message: String,
        val retryable: Boolean,
        val delayMs: Long = 1_000L
    ) : FakeJudgmentBehavior
}
