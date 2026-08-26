package com.example.myapplication

import com.example.myapplication.judgment.LocalRoiDetection
import com.example.myapplication.judgment.localAgnosticNms
import com.example.myapplication.judgment.localBottomSixtyWindow
import com.example.myapplication.judgment.localCropWindowForDetection
import com.example.myapplication.judgment.parseLocalRoiDetections
import com.example.myapplication.judgment.selectLocalActiveDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceRoiGeometryTest {
    @Test
    fun parsesLetterboxedServerOutputIntoNormalizedDetection() {
        val detections = parseLocalRoiDetections(
            values = floatArrayOf(64f, 160f, 576f, 480f, 0.8f, 0f),
            rowCount = 1,
            fieldCount = 6,
            sourceWidth = 1280,
            sourceHeight = 720,
            scale = 0.5f,
            padX = 0,
            padY = 140
        )

        assertEquals(1, detections.size)
        assertEquals(ImageCropTarget.CUTTING_BOARD_ROI, detections.single().target)
        assertEquals(0.1f, detections.single().x, 0.001f)
        assertEquals(0.8f, detections.single().width, 0.001f)
    }

    @Test
    fun selectsExpectedTargetNearestWearerGaze() {
        val farther = detection(ImageCropTarget.PAN_COOKING_ROI, 0.9f, 0.1f, 0.1f)
        val nearer = detection(ImageCropTarget.PAN_COOKING_ROI, 0.4f, 0.45f, 0.55f)
        val board = detection(ImageCropTarget.CUTTING_BOARD_ROI, 0.99f, 0.45f, 0.55f)

        val selected = selectLocalActiveDetection(
            listOf(farther, nearer, board),
            ImageCropTarget.PAN_COOKING_ROI
        )

        assertEquals(nearer, selected)
    }

    @Test
    fun autoRoiSelectsNearestSupportedTargetAcrossBoardAndPan() {
        val board = detection(ImageCropTarget.CUTTING_BOARD_ROI, 0.95f, 0.15f, 0.30f)
        val pan = detection(ImageCropTarget.PAN_COOKING_ROI, 0.60f, 0.45f, 0.55f)

        val selected = selectLocalActiveDetection(
            listOf(board, pan),
            ImageCropTarget.AUTO_ROI
        )

        assertEquals(pan, selected)
    }

    @Test
    fun suppressesSynonymBoxesAndCreatesExactBoardRatio() {
        val first = detection(ImageCropTarget.CUTTING_BOARD_ROI, 0.8f, 0.3f, 0.4f)
        val duplicate = first.copy(confidence = 0.7f, x = 0.31f)
        val kept = localAgnosticNms(listOf(first, duplicate))
        assertEquals(1, kept.size)

        val window = localCropWindowForDetection(
            imageWidth = 1024,
            imageHeight = 1365,
            detection = kept.single(),
            outputAspectRatio = 4f / 3f
        )
        assertNotNull(window)
        assertTrue(window.left >= 0 && window.top >= 0)
        assertEquals(4f / 3f, window.width.toFloat() / window.height, 0.01f)
        assertEquals(364, window.width)
        assertEquals(273, window.height)
    }

    @Test
    fun legacyFallbackRetainsBottomSixtyPercent() {
        val window = localBottomSixtyWindow(1024, 1365)

        assertEquals(546, window.top)
        assertEquals(819, window.height)
    }

    private fun detection(
        target: ImageCropTarget,
        confidence: Float,
        x: Float,
        y: Float
    ) = LocalRoiDetection(
        target = target,
        confidence = confidence,
        x = x,
        y = y,
        width = 0.2f,
        height = 0.2f
    )
}
