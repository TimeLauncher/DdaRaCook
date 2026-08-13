package com.example.myapplication.judgment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

data class NormalizedImage(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val originalByteSize: Int,
    val originalOrientation: Int,
    val sha256: String,
    val pipelineVersion: String = PIPELINE_VERSION
)

class ImageNormalizer(private val context: Context) {
    fun normalize(uriValue: String): NormalizedImage {
        val original = open(uriValue).use(InputStream::readBytes)
        val orientation = runCatching {
            open(uriValue).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size)) {
            "판정 이미지를 디코딩할 수 없습니다."
        }
        val oriented = decoded.applyOrientation(orientation)
        val cropped = oriented.keepBottomPercent(BOTTOM_KEEP_PERCENT)
        val scaled = cropped.scaleToMaxLongEdge(MAX_SERVER_LONG_EDGE)
        val output = ByteArrayOutputStream()
        check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG 정규화에 실패했습니다." }
        val bytes = output.toByteArray()
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== oriented) cropped.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return NormalizedImage(
            jpegBytes = bytes,
            width = outputWidth,
            height = outputHeight,
            originalByteSize = original.size,
            originalOrientation = orientation,
            sha256 = bytes.sha256()
        )
    }

    private fun open(uriValue: String): InputStream {
        val uri = Uri.parse(uriValue)
        return when (uri.scheme) {
            "file" -> FileInputStream(File(requireNotNull(uri.path)))
            else -> requireNotNull(context.contentResolver.openInputStream(uri)) { "판정 이미지를 열 수 없습니다." }
        }
    }

    private companion object {
        const val BOTTOM_KEEP_PERCENT = 60
        const val MAX_SERVER_LONG_EDGE = 1024
        const val JPEG_QUALITY = 80
    }
}

private fun Bitmap.applyOrientation(orientation: Int): Bitmap {
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    return if (matrix.isIdentity) this else Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.keepBottomPercent(keepPercent: Int): Bitmap {
    val crop = bottomCropBounds(width = width, height = height, keepPercent = keepPercent)
    if (crop.top == 0 && crop.height == height) return this
    return Bitmap.createBitmap(this, 0, crop.top, crop.width, crop.height)
}

private fun Bitmap.scaleToMaxLongEdge(maxLongEdge: Int): Bitmap {
    val output = scaledDimensions(width = width, height = height, maxLongEdge = maxLongEdge)
    if (output.width == width && output.height == height) return this
    return Bitmap.createScaledBitmap(this, output.width, output.height, true)
}

internal data class BottomCropBounds(
    val top: Int,
    val width: Int,
    val height: Int
)

internal data class ImageDimensions(
    val width: Int,
    val height: Int
)

internal fun bottomCropBounds(
    width: Int,
    height: Int,
    keepPercent: Int = 60
): BottomCropBounds {
    require(width > 0 && height > 0) { "이미지 크기는 0보다 커야 합니다." }
    require(keepPercent in 1..100) { "유지 비율은 1~100이어야 합니다." }
    val retainedHeight = ((height.toLong() * keepPercent) / 100L)
        .toInt()
        .coerceAtLeast(1)
    return BottomCropBounds(
        top = height - retainedHeight,
        width = width,
        height = retainedHeight
    )
}

internal fun scaledDimensions(
    width: Int,
    height: Int,
    maxLongEdge: Int = 1024
): ImageDimensions {
    require(width > 0 && height > 0) { "이미지 크기는 0보다 커야 합니다." }
    require(maxLongEdge > 0) { "최대 긴 변은 0보다 커야 합니다." }
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return ImageDimensions(width, height)

    fun scaled(value: Int): Int = ((value.toLong() * maxLongEdge + longEdge / 2L) / longEdge)
        .toInt()
        .coerceAtLeast(1)

    return ImageDimensions(
        width = scaled(width),
        height = scaled(height)
    )
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

const val PIPELINE_VERSION = "bottom60-long1024-jpeg80-exif-baked-v3"
