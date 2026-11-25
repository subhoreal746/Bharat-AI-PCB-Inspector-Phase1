package com.example.imageprocessing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.domain.model.ImageValidationResult
import java.io.InputStream
import kotlin.math.max

/**
 * Bounded image decoder, EXIF orientation normalizer, and uncalibrated clarity heuristic.
 *
 * Ownership rule: never calls Bitmap.recycle() indiscriminately on externally visible bitmaps.
 * Intermediate pipeline buffers go out of scope cleanly for GC.
 */
object SafeImageDecoder {

    const val DEFAULT_LOW_CLARITY_VARIANCE_THRESHOLD = 45.0
    private const val MAX_DECODED_EDGE_PX = 1600

    data class DecodedFrame(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val orientationDegrees: Int
    )

    fun readExifOrientation(openStream: () -> InputStream?): Int {
        return try {
            openStream()?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun orientationToDegrees(exifOrientation: Int): Int {
        return when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }

    fun decodeBoundedOrientedBitmap(
        maxEdgePx: Int = MAX_DECODED_EDGE_PX,
        openStream: () -> InputStream?
    ): DecodedFrame? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openStream() ?: return null
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val exifOrientation = readExifOrientation(openStream)
        val degrees = orientationToDegrees(exifOrientation)

        val sampleSize = computeInSampleSize(rawW, rawH, maxEdgePx)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val sampledBitmap = openStream()?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        var orientedBitmap: Bitmap? = null
        val finalBitmap = try {
            applyExifTransform(sampledBitmap, exifOrientation).also { orientedBitmap = it }
        } finally {
            if (orientedBitmap !== sampledBitmap && !sampledBitmap.isRecycled) {
                sampledBitmap.recycle()
            }
        }
        val (logicalW, logicalH) = if (degrees == 90 || degrees == 270) {
            rawH to rawW
        } else {
            rawW to rawH
        }

        return DecodedFrame(
            bitmap = finalBitmap,
            originalWidth = logicalW,
            originalHeight = logicalH,
            orientationDegrees = degrees
        )
    }

    fun validateAndAssessClarity(
        contentResolver: ContentResolver,
        uri: Uri,
        minWidth: Int,
        minHeight: Int
    ): ImageValidationResult {
        return validateFromStreamProvider(
            minWidth = minWidth,
            minHeight = minHeight,
            openStream = { contentResolver.openInputStream(uri) }
        )
    }

    fun validateFromStreamProvider(
        minWidth: Int,
        minHeight: Int,
        openStream: () -> InputStream?
    ): ImageValidationResult {
        val frame = try {
            decodeBoundedOrientedBitmap(maxEdgePx = 1024, openStream = openStream)
        } catch (e: OutOfMemoryError) {
            return ImageValidationResult(
                isValid = false,
                isProcessable = false,
                width = 0,
                height = 0,
                clarityScore = null,
                lowClarityRisk = true,
                errorMessage = "Out of memory decoding PCB image: ${e.message}"
            )
        } catch (e: Exception) {
            return ImageValidationResult(
                isValid = false,
                isProcessable = false,
                width = 0,
                height = 0,
                clarityScore = null,
                lowClarityRisk = true,
                errorMessage = "Failed to decode image stream: ${e.message ?: "unknown error"}"
            )
        }

        if (frame == null) {
            return ImageValidationResult(
                isValid = false,
                isProcessable = false,
                width = 0,
                height = 0,
                clarityScore = null,
                lowClarityRisk = true,
                errorMessage = "Unable to decode readable PCB image dimensions."
            )
        }

        return try {
            val logicalW = frame.originalWidth
            val logicalH = frame.originalHeight
            if (logicalW < minWidth || logicalH < minHeight) {
                return ImageValidationResult(
                    isValid = false,
                    isProcessable = false,
                    width = logicalW,
                    height = logicalH,
                    clarityScore = null,
                    lowClarityRisk = true,
                    orientationDegrees = frame.orientationDegrees,
                    errorMessage = "Image resolution ${logicalW}x${logicalH} is below minimum ${minWidth}x${minHeight}."
                )
            }

            val clarityScore = computeLaplacianVarianceHeuristic(frame.bitmap)
            val lowClarityRisk = clarityScore < DEFAULT_LOW_CLARITY_VARIANCE_THRESHOLD

            ImageValidationResult(
                isValid = true,
                isProcessable = true,
                width = logicalW,
                height = logicalH,
                clarityScore = clarityScore,
                lowClarityRisk = lowClarityRisk,
                orientationDegrees = frame.orientationDegrees,
                errorMessage = null
            )
        } finally {
            if (!frame.bitmap.isRecycled) {
                frame.bitmap.recycle()
            }
        }
    }

    /**
     * Uncalibrated initial Laplacian variance heuristic on a downsampled luminance plane.
     * Used strictly as a low-clarity risk flag for manual review gating, never hard-rejection.
     */
    fun computeLaplacianVarianceHeuristic(bitmap: Bitmap): Double {
        val maxSide = 256
        val scale = max(bitmap.width, bitmap.height).toFloat() / maxSide.toFloat()
        val targetW = if (scale > 1f) (bitmap.width / scale).toInt().coerceAtLeast(16) else bitmap.width
        val targetH = if (scale > 1f) (bitmap.height / scale).toInt().coerceAtLeast(16) else bitmap.height

        var scaledWorking: Bitmap? = null
        val w: Int
        val h: Int
        val pixels: IntArray
        try {
            val working = if (targetW != bitmap.width || targetH != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                    if (it !== bitmap) scaledWorking = it
                }
            } else {
                bitmap
            }

            w = working.width
            h = working.height
            if (w < 3 || h < 3) return 0.0

            pixels = IntArray(w * h)
            working.getPixels(pixels, 0, w, 0, 0, w, h)
        } finally {
            scaledWorking?.let { tmp ->
                if (tmp !== bitmap && !tmp.isRecycled) tmp.recycle()
            }
        }

        val luma = DoubleArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until h - 1) {
            val rowOffset = y * w
            for (x in 1 until w - 1) {
                val idx = rowOffset + x
                val lap = luma[idx - w] + luma[idx + w] + luma[idx - 1] + luma[idx + 1] - 4.0 * luma[idx]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    fun computeInSampleSize(rawWidth: Int, rawHeight: Int, maxEdgePx: Int): Int {
        var sampleSize = 1
        var halfW = rawWidth
        var halfH = rawHeight
        while (max(halfW, halfH) > maxEdgePx) {
            sampleSize *= 2
            halfW = rawWidth / sampleSize
            halfH = rawHeight / sampleSize
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun applyExifTransform(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
