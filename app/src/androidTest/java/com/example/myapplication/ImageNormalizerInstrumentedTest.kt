package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.judgment.ImageNormalizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageNormalizerInstrumentedTest {
    @Test
    fun pngCaptureKeepsBottom60PercentAndScalesToJpeg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "normalizer-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        Canvas(bitmap).drawRect(0f, 0f, 1200f, 640f, Paint().apply { color = Color.RED })
        input.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        try {
            val normalized = ImageNormalizer(context).normalize(Uri.fromFile(input).toString())

            assertEquals(1024, normalized.width)
            assertEquals(819, normalized.height)
            assertTrue(normalized.jpegBytes.size > 2)
            assertEquals(0xFF, normalized.jpegBytes[0].toInt() and 0xFF)
            assertEquals(0xD8, normalized.jpegBytes[1].toInt() and 0xFF)
            val decoded = requireNotNull(
                BitmapFactory.decodeByteArray(normalized.jpegBytes, 0, normalized.jpegBytes.size)
            )
            try {
                val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                assertTrue(Color.blue(center) > Color.red(center))
            } finally {
                decoded.recycle()
            }
        } finally {
            input.delete()
        }
    }
}
