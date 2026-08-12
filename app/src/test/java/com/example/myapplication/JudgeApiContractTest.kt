package com.example.myapplication

import com.example.myapplication.judgment.JudgeDebugOptions
import com.example.myapplication.judgment.retryDirective
import com.example.myapplication.judgment.shouldSendStartImage
import com.example.myapplication.judgment.toHeaders
import com.example.myapplication.judgment.toReasonCode
import com.example.myapplication.judgment.toServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeApiContractTest {
    @Test
    fun countUsesContractServerValue() {
        assertEquals("COUNT", CheckType.COUNT.toServerType())
    }

    @Test
    fun onlyColorChangeSendsStartImage() {
        assertTrue(CheckType.COLOR_CHANGE.shouldSendStartImage())
        assertFalse(CheckType.STATE_TRANSITION.shouldSendStartImage())
        assertFalse(CheckType.COUNT.shouldSendStartImage())
    }

    @Test
    fun unknownReasonCodeFallsBackToOther() {
        assertEquals(ReasonCode.OTHER, "NEW_SERVER_REASON".toReasonCode())
    }

    @Test
    fun debugOptionsCreateAllContractHeaders() {
        val headers = JudgeDebugOptions(
            mockVerdict = JudgmentVerdict.CANNOT_TELL,
            mockStatus = 503,
            mockDelayMs = 250
        ).toHeaders()

        assertEquals("CANNOT_TELL", headers["X-Mock-Verdict"])
        assertEquals("503", headers["X-Mock-Status"])
        assertEquals("250", headers["X-Mock-Delay-Ms"])
    }

    @Test
    fun rateLimitBacksOffAndServerUnavailableRetriesImmediately() {
        assertTrue(requireNotNull(retryDirective(429, null)).delayMs > 0L)
        assertEquals(0L, requireNotNull(retryDirective(503, null)).delayMs)
        assertNull(retryDirective(500, null))
    }
}
