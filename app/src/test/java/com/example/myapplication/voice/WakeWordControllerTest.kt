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
    }
}
