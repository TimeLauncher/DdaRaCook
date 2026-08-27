package com.example.myapplication.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordControllerTest {
    @Test
    fun recognizesJoinedAndSpacedWakeWord() {
        assertTrue(isTtaraCookWakeWord("따라쿡"))
        assertTrue(isTtaraCookWakeWord("따라 쿡"))
        assertTrue(isTtaraCookWakeWord("  따라\n쿡  "))
    }

    @Test
    fun rejectsUnrelatedSpeechAndPartialName() {
        assertFalse(isTtaraCookWakeWord("다음 단계"))
        assertFalse(isTtaraCookWakeWord("따라"))
        assertFalse(isTtaraCookWakeWord("쿡"))
        assertFalse(isTtaraCookWakeWord("지금 따라쿡 불러줘"))
    }

    @Test
    fun stablePartialWakeWordTriggersWithoutWaitingForFinalResult() {
        val tracker = WakeWordCandidateTracker(
            requiredStablePartials = 2,
            minimumStableMs = 150L
        )

        assertFalse(tracker.observePartial("따라 쿡", nowMs = 1_000L))
        assertTrue(tracker.observePartial("따라쿡", nowMs = 1_200L))
    }

    @Test
    fun unstableOrEmbeddedPartialWakeWordIsRejected() {
        val tracker = WakeWordCandidateTracker(
            requiredStablePartials = 2,
            minimumStableMs = 150L
        )

        assertFalse(tracker.observePartial("따라쿡", nowMs = 1_000L))
        assertFalse(tracker.observePartial("다음 단계", nowMs = 1_100L))
        assertFalse(tracker.observePartial("따라쿡", nowMs = 1_300L))
        assertFalse(tracker.observePartial("지금 따라쿡 불러줘", nowMs = 1_500L))
    }

    @Test
    fun exactFinalWakeWordTriggersAndResetsPartialCandidate() {
        val tracker = WakeWordCandidateTracker()

        assertFalse(tracker.observePartial("따라쿡", nowMs = 1_000L))
        assertTrue(tracker.observeFinal("따라 쿡"))
        assertFalse(tracker.observePartial("따라쿡", nowMs = 2_000L))
        assertFalse(tracker.observeFinal("따라 콕"))
    }
}
