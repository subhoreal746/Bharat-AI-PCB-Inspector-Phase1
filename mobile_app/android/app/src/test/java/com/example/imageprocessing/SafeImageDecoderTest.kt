package com.example.imageprocessing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafeImageDecoderTest {

    @Test
    fun `orientationToDegrees maps exif constants accurately`() {
        assertEquals(0, SafeImageDecoder.orientationToDegrees(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(90, SafeImageDecoder.orientationToDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, SafeImageDecoder.orientationToDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, SafeImageDecoder.orientationToDegrees(ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun `computeInSampleSize keeps decoded edges bounded`() {
        assertEquals(1, SafeImageDecoder.computeInSampleSize(1200, 900, 1600))
        assertEquals(2, SafeImageDecoder.computeInSampleSize(3200, 2400, 1600))
        assertEquals(4, SafeImageDecoder.computeInSampleSize(6400, 4800, 1600))
    }

    @Test
    fun `laplacian heuristic distinguishes flat blurry image from high contrast edge image`() {
        val flatBitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(120, 120, 120))
        }
        val sharpBitmap = createCheckerboardBitmap(128, 128)

        val flatScore = SafeImageDecoder.computeLaplacianVarianceHeuristic(flatBitmap)
        val sharpScore = SafeImageDecoder.computeLaplacianVarianceHeuristic(sharpBitmap)

        assertTrue("Flat image should have near-zero variance, was $flatScore", flatScore < 1.0)
        assertTrue("Sharp image should have high variance, was $sharpScore", sharpScore > 500.0)
    }

    @Test
    fun `null stream produces unprocessable validation failure`() {
        val result = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 256,
            minHeight = 256,
            openStream = { null }
        )
        assertFalse(result.isValid)
        assertFalse(result.isProcessable)
    }

    @Test
    fun `valid encoded PNG succeeds validation with exact dimensions and processable clarity`() {
        val pngBytes = encodeBitmap(createCheckerboardBitmap(640, 480), Bitmap.CompressFormat.PNG)

        val result = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 256,
            minHeight = 256,
            openStream = { ByteArrayInputStream(pngBytes) }
        )

        assertTrue("Expected valid PNG, got error=${result.errorMessage}", result.isValid)
        assertTrue(result.isProcessable)
        assertEquals(640, result.width)
        assertEquals(480, result.height)
        assertNotNull(result.clarityScore)
        assertNull(result.errorMessage)
    }

    @Test
    fun `valid encoded JPEG succeeds validation with exact dimensions and processable clarity`() {
        val jpegBytes = encodeBitmap(createCheckerboardBitmap(800, 600), Bitmap.CompressFormat.JPEG)

        val result = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 256,
            minHeight = 256,
            openStream = { ByteArrayInputStream(jpegBytes) }
        )

        assertTrue("Expected valid JPEG, got error=${result.errorMessage}", result.isValid)
        assertTrue(result.isProcessable)
        assertEquals(800, result.width)
        assertEquals(600, result.height)
        assertNotNull(result.clarityScore)
        assertNull(result.errorMessage)
    }

    @Test
    @Config(shadows = [RejectCorruptBitmapFactoryShadow::class])
    fun `invalid image bytes fail validation with unreadable dimensions message`() {
        val corruptBytes = "not-a-valid-pcb-image-payload".toByteArray()

        val result = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 256,
            minHeight = 256,
            openStream = { ByteArrayInputStream(corruptBytes) }
        )

        assertFalse(result.isValid)
        assertFalse(result.isProcessable)
        assertEquals(0, result.width)
        assertEquals(0, result.height)
        assertEquals("Unable to decode readable PCB image dimensions.", result.errorMessage)
    }

    @Test
    fun `exif rotate 90 updates logical dimensions and orientation degrees`() {
        val tempJpeg = File.createTempFile("pcb_exif_rot90_", ".jpg")
        try {
            val jpegBytes = encodeBitmap(createCheckerboardBitmap(640, 480), Bitmap.CompressFormat.JPEG)
            FileOutputStream(tempJpeg).use { it.write(jpegBytes) }

            val exif = ExifInterface(tempJpeg.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            exif.saveAttributes()

            val frame = SafeImageDecoder.decodeBoundedOrientedBitmap(maxEdgePx = 1024) {
                FileInputStream(tempJpeg)
            }
            assertNotNull(frame)
            assertEquals(90, frame!!.orientationDegrees)
            assertEquals(480, frame.originalWidth)
            assertEquals(640, frame.originalHeight)

            val validation = SafeImageDecoder.validateFromStreamProvider(
                minWidth = 256,
                minHeight = 256,
                openStream = { FileInputStream(tempJpeg) }
            )
            assertTrue(validation.isValid)
            assertTrue(validation.isProcessable)
            assertEquals(90, validation.orientationDegrees)
            assertEquals(480, validation.width)
            assertEquals(640, validation.height)
        } finally {
            tempJpeg.delete()
        }
    }

    @Test
    @Config(shadows = [StrictBoundsNullBitmapFactoryShadow::class])
    fun `decodeBoundedOrientedBitmap and validation succeed when BitmapFactory returns null for inJustDecodeBounds`() {
        val pngBytes = encodeBitmap(createCheckerboardBitmap(640, 480), Bitmap.CompressFormat.PNG)

        // Prove the shadow strictly enforces real Android inJustDecodeBounds == true -> null return
        val probeOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probeReturn = ByteArrayInputStream(pngBytes).use {
            BitmapFactory.decodeStream(it, null, probeOptions)
        }
        assertNull(
            "Test precondition failed: BitmapFactory.decodeStream must return null when inJustDecodeBounds=true",
            probeReturn
        )
        assertEquals(640, probeOptions.outWidth)
        assertEquals(480, probeOptions.outHeight)

        val frame = SafeImageDecoder.decodeBoundedOrientedBitmap(maxEdgePx = 1024) {
            ByteArrayInputStream(pngBytes)
        }
        assertNotNull("decodeBoundedOrientedBitmap must not treat bounds-pass null return as failure", frame)
        assertEquals(640, frame!!.originalWidth)
        assertEquals(480, frame.originalHeight)

        val validation = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 256,
            minHeight = 256,
            openStream = { ByteArrayInputStream(pngBytes) }
        )
        assertTrue("Validation failed under real inJustDecodeBounds=true null semantics: ${validation.errorMessage}", validation.isValid)
        assertTrue(validation.isProcessable)
        assertEquals(640, validation.width)
        assertEquals(480, validation.height)
        assertNull(validation.errorMessage)
    }

    @Test
    fun `default robolectric ShadowBitmapFactory returns non-null for inJustDecodeBounds explaining why bug escaped JVM tests`() {
        val pngBytes = encodeBitmap(createCheckerboardBitmap(320, 240), Bitmap.CompressFormat.PNG)
        val probeOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val defaultShadowReturn = ByteArrayInputStream(pngBytes).use {
            BitmapFactory.decodeStream(it, null, probeOptions)
        }
        assertNotNull(
            "Documents Robolectric ShadowBitmapFactory divergence from real Android ART",
            defaultShadowReturn
        )
        assertEquals(320, probeOptions.outWidth)
        assertEquals(240, probeOptions.outHeight)
    }

    @Test
    fun `dimension matrix test for 224x224 policy minimum`() {
        val testCases = listOf(
            Triple(223, 223, false),
            Triple(224, 224, true),
            Triple(225, 225, true),
            Triple(226, 226, true),
            Triple(227, 227, true),
            Triple(256, 256, true),
            Triple(512, 512, true),
            Triple(300, 226, true),
            Triple(226, 300, true),
            Triple(32, 32, false),
            Triple(300, 220, false), // one dimension below minimum
            Triple(220, 300, false)  // other dimension below minimum
        )

        for ((w, h, expectedValid) in testCases) {
            val bmp = createCheckerboardBitmap(w, h)
            val bytes = encodeBitmap(bmp, Bitmap.CompressFormat.PNG)
            val result = SafeImageDecoder.validateFromStreamProvider(
                minWidth = 224,
                minHeight = 224,
                openStream = { ByteArrayInputStream(bytes) }
            )
            assertEquals("Case ${w}x${h} expected valid=$expectedValid", expectedValid, result.isValid)
            assertEquals("Case ${w}x${h} expected processable=$expectedValid", expectedValid, result.isProcessable)
            if (!expectedValid) {
                assertNotNull("Case ${w}x${h} should have error message", result.errorMessage)
                assertTrue(result.errorMessage!!.contains("below minimum"))
            } else {
                assertEquals(w, result.width)
                assertEquals(h, result.height)
            }
        }
    }

    @Test
    fun `blurry 226x226 image remains valid but indicates low clarity risk`() {
        val flatBmp = Bitmap.createBitmap(226, 226, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }
        val bytes = encodeBitmap(flatBmp, Bitmap.CompressFormat.PNG)
        val result = SafeImageDecoder.validateFromStreamProvider(
            minWidth = 224,
            minHeight = 224,
            openStream = { ByteArrayInputStream(bytes) }
        )

        assertTrue("Blurry 226x226 image should remain valid", result.isValid)
        assertTrue("Blurry 226x226 image should be processable", result.isProcessable)
        assertEquals(226, result.width)
        assertEquals(226, result.height)
        assertTrue("Blurry 226x226 image must flag lowClarityRisk", result.lowClarityRisk)
        assertNotNull(result.clarityScore)
        assertTrue(result.clarityScore!! < SafeImageDecoder.DEFAULT_LOW_CLARITY_VARIANCE_THRESHOLD)
    }

    @Test
    fun `images larger than max edge retain bounded decoding and correct original dimensions`() {
        val largeBmp = createCheckerboardBitmap(2400, 1800)
        val bytes = encodeBitmap(largeBmp, Bitmap.CompressFormat.JPEG)
        val frame = SafeImageDecoder.decodeBoundedOrientedBitmap(maxEdgePx = 1024) {
            ByteArrayInputStream(bytes)
        }
        assertNotNull(frame)
        assertEquals(2400, frame!!.originalWidth)
        assertEquals(1800, frame.originalHeight)
        assertTrue("Decoded bitmap width must be bounded", frame.bitmap.width <= 1200)
        assertTrue("Decoded bitmap height must be bounded", frame.bitmap.height <= 1200)
    }

    private fun createCheckerboardBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val color = if ((x / 8 + y / 8) % 2 == 0) Color.BLACK else Color.WHITE
                    setPixel(x, y, color)
                }
            }
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(format, 95, baos)
        return baos.toByteArray()
    }

    @Implements(BitmapFactory::class)
    class StrictBoundsNullBitmapFactoryShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun decodeStream(
                inputStream: InputStream?,
                outPadding: Rect?,
                opts: BitmapFactory.Options?
            ): Bitmap? {
                val method = ShadowBitmapFactory::class.java.getDeclaredMethod(
                    "decodeStream",
                    InputStream::class.java,
                    Rect::class.java,
                    BitmapFactory.Options::class.java
                ).apply { isAccessible = true }
                val decoded = method.invoke(null, inputStream, outPadding, opts) as Bitmap?
                if (opts?.inJustDecodeBounds == true) {
                    return null
                }
                return decoded
            }
        }
    }

    @Implements(BitmapFactory::class)
    class RejectCorruptBitmapFactoryShadow {
        companion object {
            @JvmStatic
            @Implementation
            fun decodeStream(
                inputStream: InputStream?,
                outPadding: Rect?,
                opts: BitmapFactory.Options?
            ): Bitmap? {
                opts?.outWidth = -1
                opts?.outHeight = -1
                return null
            }
        }
    }
}
