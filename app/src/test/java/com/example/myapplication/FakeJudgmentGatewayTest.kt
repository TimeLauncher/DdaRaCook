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
                recipeId = "sausage-vegetable-stir-fry",
                stepOrder = 1,
                instruction = "양파를 볶는다",
                checkType = CheckType.COLOR_CHANGE,
                checkCondition = "양파가 반투명해졌는가",
                needsStartImage = true,
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

        assertEquals("sausage-vegetable-stir-fry", recipes.first().id)
        assertTrue(recipes.first().isMvpReady)
        assertEquals("소세지야채볶음", recipes.first().title)
        assertEquals(5, recipes.first().steps.size)
        assertTrue(recipes.first().steps.first().inspectionPolicy?.requiredConsecutiveDone == 2)
        assertTrue(recipes.first().steps[1].checkType == CheckType.TIMER_ONLY)
        assertTrue(recipes.first().steps[1].inspectionPolicy == null)
        assertTrue(recipes.first().steps[3].checkType == CheckType.STATE_TRANSITION)
        assertTrue(recipes.first().steps[4].checkType == CheckType.TIMER_ONLY)
        assertTrue(recipes.first().steps[4].inspectionPolicy?.maxExpectedSeconds == 180)

        // 1단계와 4단계는 같은 STATE_TRANSITION 이지만 시작 이미지 정책이 반대다.
        // 1단계는 "덩어리가 없는가"(절대), 4단계는 "시작 시점 사진과 비교해"(상대) — CONTRACT §3.2
        assertTrue(!recipes.first().steps[0].needsStartImage)
        assertTrue(recipes.first().steps[3].needsStartImage)
        assertTrue(recipes.first().steps.all { limitAnnouncement(it.voicePrompt).split(Regex("(?<=[.!?。！？])\\s+")).size <= 2 })

        val kimchi = recipes.first { it.id == "kimchi" }
        assertTrue(!kimchi.isMvpReady)
    }

    @Test
    fun beefBrisketPastaFollowsRecipeContract() {
        val pasta = RecipeFixtures.sampleRecipes().first { it.id == "beef-brisket-pasta" }

        assertTrue(pasta.validationErrors().isEmpty())
        assertEquals(6, pasta.steps.size)

        // 1단계 면 삶기와 6단계 마무리는 자동 판정하지 않는다.
        assertEquals(listOf(1, 6), pasta.steps.filterNot { it.isAutoCheck }.map { it.order })
        assertTrue(pasta.steps.filterNot { it.isAutoCheck }.all { it.checkType == CheckType.TIMER_ONLY })

        // 면 삶기는 1단계에서 걸고 이후 단계에서도 계속 도는 병렬 타이머다.
        val timer = pasta.steps.first().parallelTimer
        assertEquals(480, timer?.durationSeconds)
        assertEquals("면 삶기", timer?.label)
        assertEquals(1, pasta.steps.count { it.parallelTimer != null })
        assertEquals(timer?.doneAnnouncement, timer?.doneAnnouncement?.let(::limitAnnouncement))

        // CONTRACT §3.2 — 시작 대비 변화를 묻는 3·5단계만 기준 사진을 보낸다.
        assertEquals(listOf(3, 5), pasta.steps.filter { it.needsStartImage }.map { it.order })
        assertTrue(pasta.steps.filter { it.needsStartImage }.all { it.checkType == CheckType.COLOR_CHANGE })
        assertEquals(1, pasta.steps.count { it.checkType == CheckType.PRESENCE })

        // F6-6 — 안내는 2문장 이내여야 잘리지 않는다.
        assertTrue(pasta.steps.all { limitAnnouncement(it.voicePrompt) == it.voicePrompt })
    }
}
