package com.example.myapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.judgment.OnDeviceRoiCropper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceRoiCropperInstrumentedTest {
    @Test
    fun runsReferenceBoardCropOnPhysicalPhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sourceBytes = instrumentation.context.assets
            .open("soya_chop_progress_01.jpg")
            .use { it.readBytes() }
        val cropper = OnDeviceRoiCropper(instrumentation.targetContext)

        cropper.warmUp()
        val result = cropper.crop(sourceBytes, ImageCropTarget.CUTTING_BOARD_ROI)

        assertEquals("LOCAL_YOLO_ROI", result.mode)
        assertEquals(1024, result.width)
        assertEquals(768, result.height)
        assertTrue(result.detectionCount > 0)
        assertTrue("warm inference was ${result.timing.inferenceMs}ms", result.timing.inferenceMs < 2_000)
    }
}
