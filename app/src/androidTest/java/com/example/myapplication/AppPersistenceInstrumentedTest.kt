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
            completedStepOrders = setOf(1, 2),
            logs = listOf(
                SessionLogEntry(
                    timestampMs = 2_000L,
                    stepOrder = 2,
                    message = "판정",
                    verdict = JudgmentVerdict.DONE,
                    requestId = "request",
                    requestedAtMs = 1_000L,
                    respondedAtMs = 2_000L,
                    imageUri = "file:///capture.jpg",
                    groundTruth = JudgmentVerdict.NOT_DONE
                )
            )
        )

        persistence.saveRecipes(recipes)
        persistence.saveSession(session)

        assertEquals(recipes.size, persistence.loadRecipes(emptyList()).size)
        val restored = persistence.loadSession()
        assertNotNull(restored)
        assertEquals(CookingPhase.WAITING_FOR_CHECK, restored?.phase)
        assertEquals(null, restored?.activeRequestId)
        assertEquals(setOf(1, 2), restored?.completedStepOrders)
        assertEquals(1_000L, restored?.logs?.single()?.requestedAtMs)
        assertEquals(JudgmentVerdict.NOT_DONE, restored?.logs?.single()?.groundTruth)
    }

    @Test
    fun judgmentModeDefaultsToRealServerAndPersistsExplicitChoice() {
        assertEquals(false, persistence.loadUseMockJudgment())

        persistence.saveUseMockJudgment(true)
        assertEquals(true, persistence.loadUseMockJudgment())

        persistence.saveUseMockJudgment(false)
        assertEquals(false, persistence.loadUseMockJudgment())
    }

    @Test
    fun legacyFixturesMigrateToSausageRecipeAndKeepCustomRecipes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixtures = RecipeFixtures.sampleRecipes()
        val custom = fixtures.first().copy(id = "custom-recipe", title = "내 레시피", isMvpReady = false)
        persistence.saveRecipes(listOf(fixtures.first { it.id == "kimchi" }, custom))
        context.getSharedPreferences("persistence-test", android.content.Context.MODE_PRIVATE)
            .edit().putInt("recipe_fixture_version", 1).commit()

        val migrated = persistence.loadRecipes(fixtures)

        assertEquals("sausage-vegetable-stir-fry", migrated.first().id)
        assertEquals(false, migrated.first { it.id == "kimchi" }.isMvpReady)
        assertNotNull(migrated.firstOrNull { it.id == "custom-recipe" })
    }
}
