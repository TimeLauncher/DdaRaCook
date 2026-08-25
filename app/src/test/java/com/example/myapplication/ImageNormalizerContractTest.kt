package com.example.myapplication

import com.example.myapplication.judgment.scaledDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageNormalizerContractTest {
    @Test
    fun automaticPortraitPreservesFullFrameAt1365LongEdge() {
        val output = scaledDimensions(width = 3024, height = 4032, maxLongEdge = 1365)

        assertEquals(1024, output.width)
        assertEquals(1365, output.height)
    }

    @Test
    fun smallAutomaticImageIsNotUpscaled() {
        val output = scaledDimensions(width = 960, height = 768, maxLongEdge = 1365)

        assertEquals(960, output.width)
        assertEquals(768, output.height)
    }

    @Test
    fun uncroppedManualPortraitScalesTo1024LongEdge() {
        val output = scaledDimensions(width = 3024, height = 4032)

        assertEquals(768, output.width)
        assertEquals(1024, output.height)
    }
}
