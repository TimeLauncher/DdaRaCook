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
        require(original.size >= 3 && original[0] == 0xFF.toByte() && original[1] == 0xD8.toByte()) {
            "JPEG 이미지만 판정에 사용할 수 있습니다."
        }
        val orientation = runCatching {
            open(uriValue).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(original, 0, original.size)) { "JPEG 이미지를 디코딩할 수 없습니다." }
        val oriented = decoded.applyOrientation(orientation)
        val scaled = oriented.scaleToLongEdge(MAX_LONG_EDGE)
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
        const val MAX_LONG_EDGE = 1280
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

private fun Bitmap.scaleToLongEdge(maxLongEdge: Int): Bitmap {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return this
    val scale = maxLongEdge.toFloat() / longEdge
    return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

const val PIPELINE_VERSION = "jpeg80-srgb-exif-baked-v1"
