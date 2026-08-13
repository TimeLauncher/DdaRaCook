package com.example.myapplication

import com.example.myapplication.judgment.horizontalCenterCropBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageNormalizerContractTest {
    @Test
    fun widerImageIsCenterCroppedTo1024() {
        val crop = horizontalCenterCropBounds(width = 3024, height = 4032)

        assertEquals(1000, crop.left)
        assertEquals(1024, crop.width)
        assertEquals(4032, crop.height)
    }

    @Test
    fun narrowImageIsNotUpscaledOrCropped() {
        val crop = horizontalCenterCropBounds(width = 960, height = 1280)

        assertEquals(0, crop.left)
        assertEquals(960, crop.width)
        assertEquals(1280, crop.height)
    }

    @Test
    fun oddDifferenceKeepsCenterWithIntegerPixelBounds() {
        val crop = horizontalCenterCropBounds(width = 1025, height = 700)

        assertEquals(0, crop.left)
        assertEquals(1024, crop.width)
        assertEquals(700, crop.height)
    }
}
