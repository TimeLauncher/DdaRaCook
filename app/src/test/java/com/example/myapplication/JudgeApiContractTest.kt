package com.example.myapplication

import com.example.myapplication.judgment.JudgeDebugOptions
import com.example.myapplication.judgment.retryDirective
import com.example.myapplication.judgment.shouldSendStartImage
import com.example.myapplication.judgment.toHeaders
import com.example.myapplication.judgment.toReasonCode
import com.example.myapplication.judgment.toServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeApiContractTest {
    @Test
    fun countUsesContractServerValue() {
        assertEquals("COUNT", CheckType.COUNT.toServerType())
    }

    @Test
    fun startImagePolicyFollowsStepNotCheckType() {
        // CONTRACT §3.2 — 같은 판정 유형이라도 완료 조건이 상대 판정이면 시작 이미지를 보낸다.
        // 판정 유형으로 가르던 v1.2 규칙으로는 아래 두 단계를 구분할 수 없었다.
        assertFalse(step(CheckType.STATE_TRANSITION, needsStartImage = false).shouldSendStartImage())
        assertTrue(step(CheckType.STATE_TRANSITION, needsStartImage = true).shouldSendStartImage())
    }

    @Test
    fun mvpRecipeCarriesContractStartImagePolicy() {
        val steps = RecipeFixtures.sampleRecipes()
            .first { it.id == "sausage-vegetable-stir-fry" }
            .steps

        assertFalse(steps[0].shouldSendStartImage())   // 1단계 · 절대 판정
        assertTrue(steps[2].shouldSendStartImage())    // 3단계 · 시작 대비 색 변화
        assertTrue(steps[3].shouldSendStartImage())    // 4단계 · 시작 대비 형태 변화
    }

    private fun step(checkType: CheckType, needsStartImage: Boolean) = RecipeStep(
        order = 1,
        instruction = "익힌다",
        checkType = checkType,
        checkCondition = "변했는가",
        needsStartImage = needsStartImage,
        inspectionPolicy = null,
        targetIngredients = emptyList(),
        voicePrompt = "익혀주세요",
        isAutoCheck = true
    )

    @Test
    fun unknownReasonCodeFallsBackToOther() {
        assertEquals(ReasonCode.OTHER, "NEW_SERVER_REASON".toReasonCode())
    }

    @Test
    fun debugOptionsCreateAllContractHeaders() {
        val headers = JudgeDebugOptions(
            mockVerdict = JudgmentVerdict.CANNOT_TELL,
            mockStatus = 503,
            mockDelayMs = 250
        ).toHeaders()

        assertEquals("CANNOT_TELL", headers["X-Mock-Verdict"])
        assertEquals("503", headers["X-Mock-Status"])
        assertEquals("250", headers["X-Mock-Delay-Ms"])
    }

    @Test
    fun rateLimitBacksOffAndServerUnavailableRetriesImmediately() {
        assertTrue(requireNotNull(retryDirective(429, null)).delayMs > 0L)
        assertEquals(0L, requireNotNull(retryDirective(503, null)).delayMs)
        assertNull(retryDirective(500, null))
    }
}
