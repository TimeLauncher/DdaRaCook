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
        val cropped = oriented.centerCropWidth(MAX_SERVER_WIDTH)
        val output = ByteArrayOutputStream()
        check(cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG 정규화에 실패했습니다." }
        val bytes = output.toByteArray()
        val outputWidth = cropped.width
        val outputHeight = cropped.height
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
        const val MAX_SERVER_WIDTH = 1024
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

private fun Bitmap.centerCropWidth(maxWidth: Int): Bitmap {
    val crop = horizontalCenterCropBounds(width = width, height = height, maxWidth = maxWidth)
    if (crop.width == width) return this
    return Bitmap.createBitmap(this, crop.left, 0, crop.width, crop.height)
}

internal data class HorizontalCropBounds(
    val left: Int,
    val width: Int,
    val height: Int
)

internal fun horizontalCenterCropBounds(
    width: Int,
    height: Int,
    maxWidth: Int = 1024
): HorizontalCropBounds {
    require(width > 0 && height > 0) { "이미지 크기는 0보다 커야 합니다." }
    require(maxWidth > 0) { "최대 가로폭은 0보다 커야 합니다." }
    val croppedWidth = width.coerceAtMost(maxWidth)
    return HorizontalCropBounds(
        left = (width - croppedWidth) / 2,
        width = croppedWidth,
        height = height
    )
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

const val PIPELINE_VERSION = "center-x1024-jpeg80-exif-baked-v2"
