package com.example.myapplication

import com.example.myapplication.judgment.JudgmentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingDomainTest {
    @Test
    fun recipeValidationRejectsMissingAutomaticPolicy() {
        val recipe = Recipe(
            id = "custom",
            title = "테스트",
            ingredients = emptyList(),
            steps = listOf(
                RecipeStep(
                    order = 1,
                    instruction = "익힌다",
                    checkType = CheckType.COLOR_CHANGE,
                    checkCondition = "색이 변했는가",
                    inspectionPolicy = null,
                    targetIngredients = emptyList(),
                    voicePrompt = "익혀주세요",
                    helperText = "",
                    isAutoCheck = true
                )
            ),
            heroNote = "",
            isMvpReady = false
        )

        assertTrue(recipe.validationErrors().any { "자동 검사 정책" in it })
    }

    @Test
    fun currentJudgmentRequiresRequestSessionAndStepMatch() {
        val session = CookingSession(id = "session", recipeId = "recipe", activeRequestId = "request")
        val matching = JudgmentResult(
            requestId = "request",
            cookingSessionId = "session",
            stepOrder = 2,
            verdict = JudgmentVerdict.DONE,
            reasonCode = ReasonCode.VISIBLE_CHANGE,
            roundTripMs = 10
        )

        assertTrue(isCurrentJudgment(session, 2, matching))
        assertFalse(isCurrentJudgment(session, 1, matching))
        assertFalse(isCurrentJudgment(session, 2, matching.copy(requestId = "old")))
    }

    @Test
    fun announcementIsLimitedToTwoSentences() {
        assertEquals("첫 문장입니다. 두 번째 문장입니다.", limitAnnouncement("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다."))
    }

    @Test
    fun stepDurationUsesStructuredTimestamps() {
        val session = CookingSession(
            id = "session",
            recipeId = "recipe",
            stepStartedAtMsByOrder = mapOf(1 to 1_000L),
            stepCompletedAtMsByOrder = mapOf(1 to 6_000L)
        )

        assertEquals(5_000L, session.stepDurationMs(1))
    }
}
