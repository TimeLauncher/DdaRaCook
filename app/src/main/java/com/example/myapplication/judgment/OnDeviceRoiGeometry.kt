package com.example.myapplication.judgment

import com.example.myapplication.ImageCropTarget
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LocalRoiDetection(
    val target: ImageCropTarget,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

internal data class LocalCropWindow(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun parseLocalRoiDetections(
    values: FloatArray,
    rowCount: Int,
    fieldCount: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    scale: Float,
    padX: Int,
    padY: Int,
    minimumConfidence: Float = LOCAL_MINIMUM_CONFIDENCE
): List<LocalRoiDetection> {
    require(rowCount >= 0 && fieldCount >= 6)
    require(values.size >= rowCount * fieldCount)
    require(sourceWidth > 0 && sourceHeight > 0 && scale > 0f)

    return buildList {
        for (rowIndex in 0 until rowCount) {
            val offset = rowIndex * fieldCount
            val confidence = values[offset + 4]
            if (confidence < minimumConfidence) continue
            val target = promptClassToTarget(values[offset + 5].roundToInt()) ?: continue
            val left = ((values[offset] - padX) / scale).coerceIn(0f, sourceWidth.toFloat())
            val top = ((values[offset + 1] - padY) / scale).coerceIn(0f, sourceHeight.toFloat())
            val right = ((values[offset + 2] - padX) / scale).coerceIn(0f, sourceWidth.toFloat())
            val bottom = ((values[offset + 3] - padY) / scale).coerceIn(0f, sourceHeight.toFloat())
            if (right <= left || bottom <= top) continue
            add(
                LocalRoiDetection(
                    target = target,
                    confidence = confidence,
                    x = left / sourceWidth,
                    y = top / sourceHeight,
                    width = (right - left) / sourceWidth,
                    height = (bottom - top) / sourceHeight
                )
            )
        }
    }
}

internal fun localAgnosticNms(
    detections: List<LocalRoiDetection>,
    iouThreshold: Float = 0.70f
): List<LocalRoiDetection> {
    val kept = mutableListOf<LocalRoiDetection>()
    detections.sortedByDescending(LocalRoiDetection::confidence).forEach { candidate ->
        if (kept.all { localIntersectionOverUnion(candidate, it) <= iouThreshold }) {
            kept += candidate
        }
    }
    return kept
}

internal fun selectLocalActiveDetection(
    detections: List<LocalRoiDetection>,
    expectedTarget: ImageCropTarget,
    anchorX: Float = 0.5f,
    anchorY: Float = 0.65f,
    minimumConfidence: Float = LOCAL_MINIMUM_CONFIDENCE,
    maximumGazeDistance: Float = 0.30f
): LocalRoiDetection? = detections
    .asSequence()
    .filter { detection ->
        detection.target == expectedTarget &&
            detection.confidence >= minimumConfidence &&
            hypot(detection.centerX - anchorX, detection.centerY - anchorY) <= maximumGazeDistance
    }
    .minWithOrNull(
        compareBy<LocalRoiDetection> {
            hypot(it.centerX - anchorX, it.centerY - anchorY)
        }.thenByDescending(LocalRoiDetection::confidence)
    )

internal fun localCropWindowForDetection(
    imageWidth: Int,
    imageHeight: Int,
    detection: LocalRoiDetection,
    outputAspectRatio: Float,
    contextPadding: Float = 0.22f
): LocalCropWindow {
    require(imageWidth > 0 && imageHeight > 0)
    require(outputAspectRatio > 0f && contextPadding >= 0f)
    require(
        detection.x >= 0f && detection.y >= 0f &&
            detection.width > 0f && detection.height > 0f &&
            detection.x + detection.width <= 1.0001f &&
            detection.y + detection.height <= 1.0001f
    )

    val boxWidth = detection.width * imageWidth
    val boxHeight = detection.height * imageHeight
    val centerX = detection.centerX * imageWidth
    val centerY = detection.centerY * imageHeight
    var cropWidth = boxWidth * (1f + 2f * contextPadding)
    var cropHeight = boxHeight * (1f + 2f * contextPadding)
    if (cropWidth / cropHeight < outputAspectRatio) {
        cropWidth = cropHeight * outputAspectRatio
    } else {
        cropHeight = cropWidth / outputAspectRatio
    }

    val fitScale = min(1f, min(imageWidth / cropWidth, imageHeight / cropHeight))
    cropWidth *= fitScale
    cropHeight *= fitScale
    val left = (centerX - cropWidth / 2f).coerceIn(0f, imageWidth - cropWidth)
    val top = (centerY - cropHeight / 2f).coerceIn(0f, imageHeight - cropHeight)
    val integerWidth = max(1, cropWidth.roundToInt())
    val integerHeight = max(1, cropHeight.roundToInt())
    val integerLeft = left.roundToInt().coerceIn(0, imageWidth - integerWidth)
    val integerTop = top.roundToInt().coerceIn(0, imageHeight - integerHeight)
    return LocalCropWindow(
        left = integerLeft,
        top = integerTop,
        right = integerLeft + integerWidth,
        bottom = integerTop + integerHeight
    )
}

internal fun localBottomSixtyWindow(imageWidth: Int, imageHeight: Int): LocalCropWindow {
    require(imageWidth > 0 && imageHeight > 0)
    val retainedHeight = max(1, imageHeight * 60 / 100)
    return LocalCropWindow(0, imageHeight - retainedHeight, imageWidth, imageHeight)
}

private fun localIntersectionOverUnion(
    first: LocalRoiDetection,
    second: LocalRoiDetection
): Float {
    val left = max(first.x, second.x)
    val top = max(first.y, second.y)
    val right = min(first.x + first.width, second.x + second.width)
    val bottom = min(first.y + first.height, second.y + second.height)
    val intersection = max(0f, right - left) * max(0f, bottom - top)
    val union = first.width * first.height + second.width * second.height - intersection
    return if (union > 0f) intersection / union else 0f
}

private fun promptClassToTarget(classId: Int): ImageCropTarget? = when (classId) {
    0, 1 -> ImageCropTarget.CUTTING_BOARD_ROI
    2, 3, 4, 5 -> ImageCropTarget.PAN_COOKING_ROI
    else -> null
}

internal const val LOCAL_MINIMUM_CONFIDENCE = 0.05f
