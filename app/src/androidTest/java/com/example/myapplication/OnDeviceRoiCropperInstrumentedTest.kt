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
        val result = cropper.crop(sourceBytes, ImageCropTarget.AUTO_ROI)

        assertEquals("LOCAL_YOLO_ROI", result.mode)
        // 도마는 4:3 이므로 OnDeviceRoiCropper.ROI_OUTPUT_LONG_EDGE 와 그 3/4.
        // 상수를 바꾸면 이 두 줄도 같이 고쳐야 한다(상수가 private companion 이라
        // 여기서 참조할 수 없다). 서버 roi_cropper.ROI_OUTPUT_LONG_EDGE 와도 같아야 한다.
        assertEquals(768, result.width)
        assertEquals(576, result.height)
        assertTrue(result.detectionCount > 0)
        assertTrue("warm inference was ${result.timing.inferenceMs}ms", result.timing.inferenceMs < 2_000)
    }
}
