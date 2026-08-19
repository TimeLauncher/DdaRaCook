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
        assertTrue(recipes.first().steps[1].checkType == CheckType.TIMER_ONLY)
        assertTrue(recipes.first().steps[1].inspectionPolicy == null)
        assertTrue(recipes.first().steps[4].checkType == CheckType.TIMER_ONLY)
        assertTrue(recipes.first().steps[4].inspectionPolicy?.maxExpectedSeconds == 180)

        // 3단계에서 야채와 소세지 투입을 확인하고, 그 시점이 다음 비교 단계의 기준 사진이 된다.
        assertEquals(
            listOf(3),
            recipes.first().steps.filter { it.checkType == CheckType.PRESENCE }.map { it.order }
        )
        assertEquals(
            listOf(4),
            recipes.first().steps.filter { it.baselineOnStepStart }.map { it.order }
        )

        // 1단계와 4단계는 같은 STATE_TRANSITION 이지만 시작 이미지 정책이 반대다.
        // 1단계는 "덩어리가 없는가"(절대), 4단계는 "시작 시점 사진과 비교해"(상대) — CONTRACT §3.2
        assertTrue(!recipes.first().steps[0].needsStartImage)
        assertTrue(recipes.first().steps[3].needsStartImage)
        assertEquals("팬에 기름을 두르고 야채와 소세지를 넣는다.", recipes.first().steps[2].instruction)
        assertEquals("팬 안에 소세지와 썬 야채가 들어있는가", recipes.first().steps[2].checkCondition)
        assertEquals("야채와 소세지를 중약불로 볶는다", recipes.first().steps[3].instruction)
        assertEquals("시작 시점 사진과 비교해 소세지 칼집이 벌어졌는가", recipes.first().steps[3].checkCondition)
        assertTrue(recipes.first().steps.all { limitAnnouncement(it.voicePrompt).split(Regex("(?<=[.!?。！？])\\s+")).size <= 2 })

        val staticSample = recipes.first { it.id == "doenjang" }
        assertTrue(!staticSample.isMvpReady)
    }

    @Test
    fun beefBrisketPastaFollowsRecipeContract() {
        val pasta = RecipeFixtures.sampleRecipes().first { it.id == "beef-brisket-pasta" }

        assertTrue(pasta.validationErrors().isEmpty())
        assertEquals(8, pasta.steps.size)

        // 전 단계가 자동 판정이다 — 투입도 마무리도 존재 판정으로 확정한다.
        assertTrue(pasta.steps.all { it.isAutoCheck })
        assertEquals(
            listOf(1, 3, 5, 6, 8),
            pasta.steps.filter { it.checkType == CheckType.PRESENCE }.map { it.order }
        )

        // 상대 판정 단계의 기준점은 앞 단계에서 "재료가 들어갔다"가 확정된 시점이다 — 15초 추측이 아니라.
        assertEquals(listOf(4, 7), pasta.steps.filter { it.baselineOnStepStart }.map { it.order })
        assertTrue(pasta.steps.filter { it.baselineOnStepStart }.all { it.needsStartImage })

        // 면 삶기는 1단계를 **마칠 때** 걸리고, 이후 단계에서도 계속 돈다.
        val timer = pasta.steps.first().parallelTimer
        assertEquals(480, timer?.durationSeconds)
        assertEquals("면 삶기", timer?.label)
        assertEquals(1, pasta.steps.count { it.parallelTimer != null })
        assertEquals(timer?.doneAnnouncement, timer?.doneAnnouncement?.let(::limitAnnouncement))

        // 삶은 면을 넣는 6단계는 면이 익기 전에 시작할 수 없다.
        assertEquals(listOf(6), pasta.steps.filter { it.waitsForParallelTimer }.map { it.order })

        // 연속 DONE은 전부 1회다 — 2회는 측정 근거 없이 굳어 있었고 실기기에서 진행을 막았다.
        assertTrue(pasta.steps.mapNotNull { it.inspectionPolicy }.all { it.requiredConsecutiveDone == 1 })

        // CONTRACT §3.2 — 시작 대비 변화를 묻는 4·7단계만 기준 사진을 보낸다.
        assertEquals(listOf(4, 7), pasta.steps.filter { it.needsStartImage }.map { it.order })
        assertTrue(pasta.steps.filter { it.needsStartImage }.all { it.checkType == CheckType.COLOR_CHANGE })

        // F6-6 — 안내는 2문장 이내여야 잘리지 않는다.
        assertTrue(pasta.steps.all { limitAnnouncement(it.voicePrompt) == it.voicePrompt })
    }
}
