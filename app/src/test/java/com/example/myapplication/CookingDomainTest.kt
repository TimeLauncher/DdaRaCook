package com.example.myapplication

import com.example.myapplication.judgment.JudgmentResult
import com.example.myapplication.camera.CapturePurpose
import com.example.myapplication.camera.CaptureRequest
import com.example.myapplication.ui.theme.Flame
import com.example.myapplication.ui.theme.Herb
import com.example.myapplication.ui.theme.Rim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingDomainTest {
    @Test
    fun persistedRecipesAreMigratedToThirtySecondAutomaticInspection() {
        val migrated = RecipeFixtures.sampleRecipes().map { recipe ->
            recipe.copy(steps = recipe.steps.map { step ->
                step.copy(
                    inspectionPolicy = step.inspectionPolicy?.copy(
                        earliestCheckSeconds = 8,
                        checkIntervalSeconds = 10
                    )
                )
            })
        }.withAutomaticInspectionInterval()

        val policies = migrated.flatMap(Recipe::steps)
            .filter(RecipeStep::isAutoCheck)
            .mapNotNull(RecipeStep::inspectionPolicy)
        assertTrue(policies.isNotEmpty())
        assertTrue(policies.all { it.earliestCheckSeconds == 30 })
        assertTrue(policies.all { it.checkIntervalSeconds == 30 })
    }


    @Test
    fun parallelTimerRemainingCountsDownFromAbsoluteDeadline() {
        val now = 1_000_000L
        val session = CookingSession(
            id = "session",
            recipeId = "beef-brisket-pasta",
            parallelTimerEndsAtMs = now + 480_000L
        )

        assertEquals(480, session.parallelTimerRemainingSeconds(now))
        assertEquals(1, session.parallelTimerRemainingSeconds(now + 479_500L))
        // 만료 후에도 음수로 내려가지 않는다. 티커는 0을 만료 신호로 쓴다.
        assertEquals(0, session.parallelTimerRemainingSeconds(now + 500_000L))
        assertEquals(null, CookingSession(id = "s", recipeId = "r").parallelTimerRemainingSeconds(now))
    }

    @Test
    fun recipeValidationRejectsWaitWithoutEarlierTimer() {
        val recipe = Recipe(
            id = "custom",
            title = "테스트",
            ingredients = emptyList(),
            steps = listOf(
                RecipeStep(
                    order = 1,
                    instruction = "삶은 면을 넣는다",
                    checkType = CheckType.PRESENCE,
                    checkCondition = "면이 들어갔는가",
                    needsStartImage = false,
                    inspectionPolicy = InspectionPolicy(30, 30, 2, 1, 60),
                    targetIngredients = emptyList(),
                    voicePrompt = "면을 넣으세요",
                    isAutoCheck = true,
                    waitsForParallelTimer = true
                )
            ),
            heroNote = "",
            isMvpReady = false
        )

        // 앞에 켤 타이머가 없으면 자동 진행이 영영 오지 않는다.
        assertTrue(recipe.validationErrors().any { "기다릴 병렬 타이머" in it })
    }

    @Test
    fun formatRemainingReadsNaturally() {
        assertEquals("40초", formatRemaining(40))
        assertEquals("3분", formatRemaining(180))
        assertEquals("3분 20초", formatRemaining(200))
        assertEquals("0초", formatRemaining(-5))
    }

    @Test
    fun recipeValidationRejectsBrokenParallelTimer() {
        val recipe = Recipe(
            id = "custom",
            title = "테스트",
            ingredients = emptyList(),
            steps = listOf(
                RecipeStep(
                    order = 1,
                    instruction = "면을 삶는다",
                    checkType = CheckType.TIMER_ONLY,
                    checkCondition = null,
                    needsStartImage = false,
                    inspectionPolicy = null,
                    targetIngredients = emptyList(),
                    voicePrompt = "면을 삶아주세요",
                    isAutoCheck = false,
                    parallelTimer = ParallelTimer(label = "면 삶기", durationSeconds = 0, doneAnnouncement = "")
                )
            ),
            heroNote = "",
            isMvpReady = false
        )

        assertTrue(recipe.validationErrors().any { "병렬 타이머 시간" in it })
        assertTrue(recipe.validationErrors().any { "병렬 타이머 완료 안내" in it })
    }

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
                    needsStartImage = true,
                    inspectionPolicy = null,
                    targetIngredients = emptyList(),
                    voicePrompt = "익혀주세요",
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
    fun staleCaptureCannotOverwriteStepAfterNextButton() {
        val request = CaptureRequest(
            requestId = "capture-1",
            cookingSessionId = "session",
            stepOrder = 3,
            purpose = CapturePurpose.INSPECTION
        )
        val active = CookingSession(
            id = "session",
            recipeId = "recipe",
            currentStepIndex = 2,
            activeRequestId = request.requestId
        )

        assertTrue(isCurrentCaptureRequest(active, 3, request))
        assertFalse(
            isCurrentCaptureRequest(
                active.copy(currentStepIndex = 3, activeRequestId = null),
                4,
                request
            )
        )
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

    @Test
    fun cannotTellTransitionsToManualOnlyOnThirdJudgment() {
        assertEquals(CookingPhase.NEEDS_VIEW to AppScreen.S7_NEEDS_VIEW, cannotTellDestination(2))
        assertEquals(CookingPhase.MANUAL_MODE to AppScreen.S8_MANUAL, cannotTellDestination(3))
    }

    @Test
    fun galleryJudgmentRequiresPhotoStepAndComparisonBaseline() {
        val steps = RecipeFixtures.sampleRecipes()
            .first { it.id == "sausage-vegetable-stir-fry" }
            .steps
            .associateBy(RecipeStep::order)

        assertEquals(null, galleryJudgmentValidationError(requireNotNull(steps[1]), hasBaselineImage = false))
        assertTrue(galleryJudgmentValidationError(requireNotNull(steps[2]), hasBaselineImage = false) != null)
        assertTrue(galleryJudgmentValidationError(requireNotNull(steps[4]), hasBaselineImage = false) != null)
        assertEquals(null, galleryJudgmentValidationError(requireNotNull(steps[4]), hasBaselineImage = true))
    }

    @Test
    fun manualStepThreeDonePhotoBecomesStepFourBaseline() {
        val recipe = RecipeFixtures.sampleRecipes()
            .first { it.id == "sausage-vegetable-stir-fry" }
        val galleryUri = "content://gallery/sausage-step-3"
        val session = CookingSession(
            id = "session",
            recipeId = recipe.id,
            mode = SessionMode.MANUAL_ONLY,
            currentStepIndex = 2,
            lastCaptureUriByStep = mapOf(3 to galleryUri)
        )

        assertEquals(
            4 to galleryUri,
            doneBaselineTarget(recipe, session, 3, JudgmentVerdict.DONE)
        )
        assertEquals(
            null,
            doneBaselineTarget(recipe, session, 3, JudgmentVerdict.NOT_DONE)
        )
    }

    @Test
    fun automaticStepFourReusesCarriedStepThreePhotoWithoutNewBaselineCapture() {
        val stepFour = RecipeFixtures.sampleRecipes()
            .first { it.id == "sausage-vegetable-stir-fry" }
            .steps
            .first { it.order == 4 }
        val session = CookingSession(
            id = "session",
            recipeId = "sausage-vegetable-stir-fry",
            baselineUriByStep = mapOf(4 to "content://capture/step-3-done")
        )

        assertTrue(hasReusableStartImage(stepFour, session))
        assertFalse(hasReusableStartImage(stepFour, session.copy(baselineUriByStep = emptyMap())))
    }

    @Test
    fun onlyDoneVerdictIsEligibleForManualAutoAdvance() {
        assertTrue(shouldAutoAdvanceManualVerdict(JudgmentVerdict.DONE))
        assertFalse(shouldAutoAdvanceManualVerdict(JudgmentVerdict.NOT_DONE))
        assertFalse(shouldAutoAdvanceManualVerdict(JudgmentVerdict.CANNOT_TELL))
    }

    @Test
    fun automaticNextButtonCanAdvanceWhileStepIsStarting() {
        assertFalse(
            blocksNextStepDuringStart(
                phase = CookingPhase.STEP_STARTING,
                allowDuringStepStarting = true
            )
        )
        assertTrue(
            blocksNextStepDuringStart(
                phase = CookingPhase.STEP_STARTING,
                allowDuringStepStarting = false
            )
        )
    }

    @Test
    fun galleryUriKeepsOriginalDisplayWhileAppCaptureUsesServerPreview() {
        assertTrue(isExternalGalleryImageUri("content://com.android.providers.media.documents/document/image%3A1"))
        assertFalse(isExternalGalleryImageUri("content://com.example.myapplication.fileprovider/shared_images/capture.png"))
        assertFalse(isExternalGalleryImageUri("file:///data/user/0/com.example.myapplication/cache/capture.png"))
    }

    @Test
    fun voiceCommandsAreParsedAndRestrictedByScreen() {
        assertEquals(VoiceCommand.VERIFY_INGREDIENT, parseVoiceCommand("이거 맞아?"))
        assertEquals(VoiceCommand.CURRENT_STEP, parseVoiceCommand("지금 몇 단계야"))
        assertTrue(isVoiceCommandAllowed(VoiceCommand.CHECK_NOW, AppScreen.S5_COOKING))
        assertFalse(isVoiceCommandAllowed(VoiceCommand.CHECK_NOW, AppScreen.S1_HOME))
    }

    @Test
    fun evaluationMetricsSeparateFalsePositiveAndMissedDone() {
        val session = CookingSession(
            id = "session",
            recipeId = "recipe",
            logs = listOf(
                SessionLogEntry(1, 1, "wrong done", verdict = JudgmentVerdict.DONE, groundTruth = JudgmentVerdict.NOT_DONE),
                SessionLogEntry(2, 1, "missed", verdict = JudgmentVerdict.NOT_DONE, groundTruth = JudgmentVerdict.DONE),
                SessionLogEntry(3, 1, "correct", verdict = JudgmentVerdict.DONE, groundTruth = JudgmentVerdict.DONE)
            )
        )

        val metrics = session.evaluationMetrics()
        assertEquals(3, metrics.labeledCount)
        assertEquals(1, metrics.correctCount)
        assertEquals(1, metrics.falsePositiveCount)
        assertEquals(1, metrics.missedDoneCount)
        assertEquals(33, metrics.accuracyPercent)
    }

    @Test
    fun statusColorsKeepContractMeaningsDistinct() {
        assertEquals(Flame, statusPalette(BannerTone.Progress).border)
        assertEquals(Herb, statusPalette(BannerTone.Success).border)
        assertEquals(Rim, statusPalette(BannerTone.Neutral).border)
        assertTrue(statusPalette(BannerTone.Caution).border !in setOf(Flame, Herb, Rim))
    }

    @Test
    fun structuredLogsCannotContainRawImageBytesOrDeviceIdentity() {
        val fields = SessionLogEntry::class.java.declaredFields
        assertFalse(fields.any { it.type == ByteArray::class.java })
        assertFalse(fields.any { it.name in setOf("deviceId", "userId", "personName") })
    }
}
