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
            parallelTimerEndsAtMs = 9_999_000L,
            parallelTimerLabel = "면 삶기",
            parallelTimerMessage = "면을 건져 두세요.",
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

        // 앱을 껐다 켜도 냄비는 계속 끓는다 — 만료 시각과 안내 문구가 함께 살아남아야 한다.
        assertEquals(9_999_000L, restored?.parallelTimerEndsAtMs)
        assertEquals("면 삶기", restored?.parallelTimerLabel)
        assertEquals(false, restored?.parallelTimerFired)

        val restoredTimer = persistence.loadRecipes(emptyList())
            .first { it.id == "beef-brisket-pasta" }
            .steps.first().parallelTimer
        assertEquals(480, restoredTimer?.durationSeconds)
        assertEquals("면 삶기", restoredTimer?.label)
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
        persistence.saveRecipes(listOf(fixtures.first { it.id == "doenjang" }, custom))
        persistence.saveSession(
            CookingSession(
                id = "old-seven-step-session",
                recipeId = "sausage-vegetable-stir-fry",
                currentStepIndex = 5
            )
        )
        context.getSharedPreferences("persistence-test", android.content.Context.MODE_PRIVATE)
            .edit().putInt("recipe_fixture_version", 1).commit()

        val migrated = persistence.loadRecipes(fixtures)

        assertEquals("sausage-vegetable-stir-fry", migrated.first().id)
        assertEquals(5, migrated.first().steps.size)
        assertEquals(false, migrated.first { it.id == "doenjang" }.isMvpReady)
        assertNotNull(migrated.firstOrNull { it.id == "custom-recipe" })
        assertEquals(null, persistence.loadSession())
    }
}
