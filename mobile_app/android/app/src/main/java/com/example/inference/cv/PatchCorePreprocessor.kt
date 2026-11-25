package com.example.inference.cv

import android.graphics.Bitmap
import java.nio.FloatBuffer

data class PatchCoreTensorInput(
    val floatBuffer: FloatBuffer,
    val shape: LongArray,
    val originalWidth: Int,
    val originalHeight: Int
)

/**
 * Preprocessor for PatchCore Anomaly Detection.
 *
 * Implements the Python reference preprocessing contract:
 * 1. Direct warp resize to 256x256 without letterboxing or aspect preservation.
 * 2. Extraction of RGB channels from Android ARGB_8888 bitmap.
 * 3. Normalization to [0.0, 1.0] by dividing by 255.0f (no ImageNet mean/std subtraction).
 * 4. NCHW planar layout: [C, H, W] = [3, 256, 256].
 * 5. Batch duplication: duplicates the single 256x256 image into both batch slots [0] and [1].
 * 6. Final tensor shape: [2, 3, 256, 256].
 *
 * Note on resize parity:
 * Android's Bitmap.createScaledBitmap(..., 256, 256, true) uses the underlying Skia/platform
 * bilinear filter. Numerical equivalence with OpenCV cv2.resize(..., INTER_LINEAR) remains
 * unverified due to sub-pixel alignment and arithmetic precision differences between OpenCV
 * and Skia.
 *
 * Does not recycle the caller's original Bitmap.
 */
object PatchCorePreprocessor {
    const val PREPROCESSING_VERSION = "patchcore-warp-rgb256-b2-v1"
    const val TARGET_WIDTH = 256
    const val TARGET_HEIGHT = 256
    const val BATCH_SIZE = 2L
    const val CHANNELS = 3L

    /**
     * Preprocesses a Bitmap for PatchCore inference.
     *
     * @param bitmap Original source bitmap (must not be recycled by this function).
     * @return [PatchCoreTensorInput] holding the direct FloatBuffer of shape [2, 3, 256, 256].
     */
    fun prepareNchwFloatInput(bitmap: Bitmap): PatchCoreTensorInput {
        require(!bitmap.isRecycled) { "Source bitmap has been recycled." }
        require(bitmap.width > 0 && bitmap.height > 0) { "Bitmap dimensions must be positive." }

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        val pixelCount = TARGET_WIDTH * TARGET_HEIGHT
        val pixels = IntArray(pixelCount)

        var scaledBitmap: Bitmap? = null
        try {
            // Direct warp resize to 256x256 using bilinear filtering (note: numerical parity with OpenCV unverified)
            val intermediate = if (bitmap.width != TARGET_WIDTH || bitmap.height != TARGET_HEIGHT) {
                Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT, true).also {
                    if (it !== bitmap) scaledBitmap = it
                }
            } else {
                bitmap
            }

            intermediate.getPixels(pixels, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
        } finally {
            scaledBitmap?.let { tmp ->
                if (tmp !== bitmap && !tmp.isRecycled) {
                    tmp.recycle()
                }
            }
        }

        // FloatBuffer for batch size 2: 2 * 3 * 256 * 256 floats
        val totalFloats = (BATCH_SIZE * CHANNELS * pixelCount).toInt()
        val floatBuffer = FloatBuffer.allocate(totalFloats)

        val planeSize = pixelCount
        val batchStride = (CHANNELS * planeSize).toInt()

        // Populate single frame into batch 0: [R plane, G plane, B plane]
        for (i in 0 until pixelCount) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255.0f
            val g = ((px shr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f

            // Batch 0
            floatBuffer.put(i, r)
            floatBuffer.put(planeSize + i, g)
            floatBuffer.put(2 * planeSize + i, b)

            // Batch 1 (exact duplication as required by static batch 2 contract)
            floatBuffer.put(batchStride + i, r)
            floatBuffer.put(batchStride + planeSize + i, g)
            floatBuffer.put(batchStride + 2 * planeSize + i, b)
        }

        floatBuffer.rewind()

        return PatchCoreTensorInput(
            floatBuffer = floatBuffer,
            shape = longArrayOf(BATCH_SIZE, CHANNELS, TARGET_HEIGHT.toLong(), TARGET_WIDTH.toLong()),
            originalWidth = originalWidth,
            originalHeight = originalHeight
        )
    }
}
