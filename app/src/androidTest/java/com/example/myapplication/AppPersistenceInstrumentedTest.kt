package com.example.myapplication

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPersistenceInstrumentedTest {
    private lateinit var persistence: AppPersistence

    @Before
    fun setUp() {
        persistence = AppPersistence(ApplicationProvider.getApplicationContext(), "persistence-test")
        persistence.clear()
    }

    @After
    fun tearDown() {
        persistence.clear()
    }

    @Test
    fun recipesAndActiveSessionSurviveRoundTrip() {
        val recipes = RecipeFixtures.sampleRecipes()
        val session = CookingSession(
            id = "session-test",
            recipeId = recipes.first().id,
            phase = CookingPhase.JUDGING,
            currentStepIndex = 2,
            activeRequestId = "in-flight",
            completedStepOrders = setOf(1, 2)
        )

        persistence.saveRecipes(recipes)
        persistence.saveSession(session)

        assertEquals(recipes.size, persistence.loadRecipes(emptyList()).size)
        val restored = persistence.loadSession()
        assertNotNull(restored)
        assertEquals(CookingPhase.WAITING_FOR_CHECK, restored?.phase)
        assertEquals(null, restored?.activeRequestId)
        assertEquals(setOf(1, 2), restored?.completedStepOrders)
    }
}
