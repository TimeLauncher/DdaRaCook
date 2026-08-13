package com.example.myapplication

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.judgment.ImageNormalizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageNormalizerInstrumentedTest {
    @Test
    fun pngCaptureIsCenterCroppedAndEncodedAsJpeg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "normalizer-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(1200, 200, Bitmap.Config.ARGB_8888)
        input.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        bitmap.recycle()

        try {
            val normalized = ImageNormalizer(context).normalize(Uri.fromFile(input).toString())

            assertEquals(1024, normalized.width)
            assertEquals(200, normalized.height)
            assertTrue(normalized.jpegBytes.size > 2)
            assertEquals(0xFF, normalized.jpegBytes[0].toInt() and 0xFF)
            assertEquals(0xD8, normalized.jpegBytes[1].toInt() and 0xFF)
        } finally {
            input.delete()
        }
    }
}
