package com.example.myapplication

import com.example.myapplication.judgment.FakeJudgmentBehavior
import com.example.myapplication.judgment.FakeJudgmentGateway
import com.example.myapplication.judgment.JudgmentOutcome
import com.example.myapplication.judgment.JudgmentRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeJudgmentGatewayTest {
    @Test
    fun fakeGatewayReturnsConfiguredVerdict() = runBlocking {
        val gateway = FakeJudgmentGateway()
        gateway.setBehavior(
            FakeJudgmentBehavior.Success(
                verdict = JudgmentVerdict.CANNOT_TELL,
                reasonCode = ReasonCode.TARGET_NOT_VISIBLE,
                delayMs = 1L
            )
        )

        val outcome = gateway.judge(
            JudgmentRequest(
                requestId = "req-1",
                cookingSessionId = "session-1",
                recipeId = "kimchi",
                stepOrder = 1,
                instruction = "양파를 볶는다",
                checkType = CheckType.COLOR_CHANGE,
                checkCondition = "양파가 반투명해졌는가",
                elapsedSeconds = 10,
                baselineImageUri = "content://baseline",
                currentImageUri = "content://current"
            )
        )

        assertTrue(outcome is JudgmentOutcome.Success)
        val success = outcome as JudgmentOutcome.Success
        assertEquals(JudgmentVerdict.CANNOT_TELL, success.result.verdict)
        assertEquals(ReasonCode.TARGET_NOT_VISIBLE, success.result.reasonCode)
    }

    @Test
    fun recipeFixtureExposesMvpRecipeFirst() {
        val recipes = RecipeFixtures.sampleRecipes()

        assertEquals("kimchi", recipes.first().id)
        assertTrue(recipes.first().isMvpReady)
        assertTrue(recipes.first().steps.isNotEmpty())
    }
}
