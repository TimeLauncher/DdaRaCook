package com.example.myapplication.judgment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.os.Build
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
    val pipelineVersion: String
)

class ImageNormalizer(private val context: Context) {
    fun normalize(
        uriValue: String,
        policy: JudgmentImagePolicy = JudgmentImagePolicy.AUTOMATIC_CAMERA
    ): NormalizedImage {
        val original = open(uriValue).use(InputStream::readBytes)
        val orientation = runCatching {
            open(uriValue).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val decodeOptions = BitmapFactory.Options().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size, decodeOptions)) {
            "판정 이미지를 디코딩할 수 없습니다."
        }
        val oriented = decoded.applyOrientation(orientation)
        val maxLongEdge = when (policy) {
            JudgmentImagePolicy.AUTOMATIC_CAMERA -> MAX_AUTOMATIC_SOURCE_LONG_EDGE
            JudgmentImagePolicy.MANUAL_MODE -> MAX_MANUAL_LONG_EDGE
        }
        val scaled = oriented.scaleToMaxLongEdge(maxLongEdge)
        val output = ByteArrayOutputStream()
        check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG 정규화에 실패했습니다." }
        val bytes = output.toByteArray()
        val outputWidth = scaled.width
        val outputHeight = scaled.height
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return NormalizedImage(
            jpegBytes = bytes,
            width = outputWidth,
            height = outputHeight,
            originalByteSize = original.size,
            originalOrientation = orientation,
            sha256 = bytes.sha256(),
            pipelineVersion = policy.pipelineVersion
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
        const val MAX_AUTOMATIC_SOURCE_LONG_EDGE = 1365
        const val MAX_MANUAL_LONG_EDGE = 1024
        const val JPEG_QUALITY = 80
    }
}

private val JudgmentImagePolicy.pipelineVersion: String
    get() = when (this) {
        JudgmentImagePolicy.AUTOMATIC_CAMERA -> AUTOMATIC_CAMERA_PIPELINE_VERSION
        JudgmentImagePolicy.MANUAL_MODE -> MANUAL_MODE_PIPELINE_VERSION
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

private fun Bitmap.scaleToMaxLongEdge(maxLongEdge: Int): Bitmap {
    val output = scaledDimensions(width = width, height = height, maxLongEdge = maxLongEdge)
    if (output.width == width && output.height == height) return this
    return Bitmap.createScaledBitmap(this, output.width, output.height, true)
}

internal data class ImageDimensions(
    val width: Int,
    val height: Int
)

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

const val AUTOMATIC_CAMERA_PIPELINE_VERSION = "full-long1365-jpeg80-exif-baked-srgb-v5"
const val MANUAL_MODE_PIPELINE_VERSION = "no-crop-long1024-jpeg80-exif-baked-srgb-v1"
