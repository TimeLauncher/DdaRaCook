package com.example.myapplication

import com.example.myapplication.judgment.bottomCropBounds
import com.example.myapplication.judgment.scaledDimensions
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageNormalizerContractTest {
    @Test
    fun portraitCaptureKeepsBottom60Percent() {
        val crop = bottomCropBounds(width = 3024, height = 4032)

        assertEquals(1613, crop.top)
        assertEquals(3024, crop.width)
        assertEquals(2419, crop.height)
    }

    @Test
    fun croppedDatCaptureScalesTo1024LongEdge() {
        val output = scaledDimensions(width = 3024, height = 2419)

        assertEquals(1024, output.width)
        assertEquals(819, output.height)
    }

    @Test
    fun smallCroppedImageIsNotUpscaled() {
        val output = scaledDimensions(width = 960, height = 768)

        assertEquals(960, output.width)
        assertEquals(768, output.height)
    }

    @Test
    fun onePixelHeightStillProducesValidBottomCrop() {
        val crop = bottomCropBounds(width = 10, height = 1)

        assertEquals(0, crop.top)
        assertEquals(1, crop.height)
    }
}
