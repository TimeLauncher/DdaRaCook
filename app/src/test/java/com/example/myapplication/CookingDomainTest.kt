package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingDomainTest {
    @Test
    fun recipeValidationRejectsMissingAutomaticPolicy() {
        val recipe = Recipe(
            id = "custom", title = "테스트", ingredients = emptyList(), heroNote = "", isMvpReady = false,
            steps = listOf(RecipeStep(1, "익힌다", CheckType.COLOR_CHANGE, "색이 변했는가", null, emptyList(), "익혀주세요", "", true))
        )
        assertTrue(recipe.validationErrors().any { "자동 검사 정책" in it })
    }

    @Test
    fun announcementIsLimitedToTwoSentences() {
        assertEquals("첫 문장입니다. 두 번째 문장입니다.", limitAnnouncement("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다."))
    }

    @Test
    fun stepDurationUsesStructuredTimestamps() {
        val session = CookingSession(
            id = "session", recipeId = "recipe",
            stepStartedAtMsByOrder = mapOf(1 to 1_000L), stepCompletedAtMsByOrder = mapOf(1 to 6_000L)
        )
        assertEquals(5_000L, session.stepDurationMs(1))
    }
}
