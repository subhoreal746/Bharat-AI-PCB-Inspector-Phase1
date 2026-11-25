package com.example.inference.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class OriginalPixelRect(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class LetterboxTransform(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val targetWidth: Int,
    val targetHeight: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val scaledWidth: Int = (originalWidth * scale).roundToInt().coerceAtLeast(1),
    val scaledHeight: Int = (originalHeight * scale).roundToInt().coerceAtLeast(1),
    val padLeft: Int = padX.roundToInt().coerceAtLeast(0),
    val padTop: Int = padY.roundToInt().coerceAtLeast(0),
    val padRight: Int = (targetWidth - scaledWidth - padLeft).coerceAtLeast(0),
    val padBottom: Int = (targetHeight - scaledHeight - padTop).coerceAtLeast(0)
) {
    /**
     * Maps bounding box coordinates from model input pixel space [0..targetWidth, 0..targetHeight]
     * back to clamped original image pixel coordinates [0..originalWidth, 0..originalHeight].
     */
    fun mapBoxToOriginalPixels(
        x1InputPx: Float,
        y1InputPx: Float,
        x2InputPx: Float,
        y2InputPx: Float
    ): OriginalPixelRect {
        val rawX1 = ((x1InputPx - padX) / scale).coerceIn(0f, originalWidth.toFloat())
        val rawY1 = ((y1InputPx - padY) / scale).coerceIn(0f, originalHeight.toFloat())
        val rawX2 = ((x2InputPx - padX) / scale).coerceIn(0f, originalWidth.toFloat())
        val rawY2 = ((y2InputPx - padY) / scale).coerceIn(0f, originalHeight.toFloat())
        return OriginalPixelRect(
            x1 = min(rawX1, rawX2),
            y1 = min(rawY1, rawY2),
            x2 = maxOf(rawX1, rawX2),
            y2 = maxOf(rawY1, rawY2)
        )
    }

    /**
     * Maps bounding box coordinates from model input pixel space [0..targetWidth, 0..targetHeight]
     * back to normalized original image coordinates [0..1].
     */
    fun mapBoxToNormalizedOriginal(
        x1InputPx: Float,
        y1InputPx: Float,
        x2InputPx: Float,
        y2InputPx: Float
    ): NormalizedRect {
        val orig = mapBoxToOriginalPixels(x1InputPx, y1InputPx, x2InputPx, y2InputPx)
        val normX = (orig.x1 / originalWidth.toFloat()).coerceIn(0f, 1f)
        val normY = (orig.y1 / originalHeight.toFloat()).coerceIn(0f, 1f)
        val normW = ((orig.x2 - orig.x1) / originalWidth.toFloat()).coerceIn(0f, 1f - normX)
        val normH = ((orig.y2 - orig.y1) / originalHeight.toFloat()).coerceIn(0f, 1f - normY)

        return NormalizedRect(normX, normY, normW, normH)
    }
}

data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class YoloTensorInput(
    val floatBuffer: FloatBuffer,
    val shape: LongArray,
    val transform: LetterboxTransform
)

object YoloPreprocessor {
    const val PREPROCESSING_VERSION = "yolo-letterbox-rgb-114-v2"
    const val TARGET_INPUT_SIZE = 640
    private const val PAD_VALUE = 114

    fun computeLetterboxTransform(
        originalWidth: Int,
        originalHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): LetterboxTransform {
        require(originalWidth > 0 && originalHeight > 0) { "Original image dimensions must be positive." }
        require(targetWidth > 0 && targetHeight > 0) { "Target model dimensions must be positive." }

        val scale = min(
            targetWidth.toFloat() / originalWidth.toFloat(),
            targetHeight.toFloat() / originalHeight.toFloat()
        )
        val scaledW = (originalWidth * scale).roundToInt().coerceIn(1, targetWidth)
        val scaledH = (originalHeight * scale).roundToInt().coerceAtLeast(1).coerceAtMost(targetHeight)
        val dw = (targetWidth - scaledW) / 2.0
        val dh = (targetHeight - scaledH) / 2.0

        val padLeft = (dw - 0.1).roundToInt().coerceAtLeast(0)
        val padRight = (dw + 0.1).roundToInt().coerceAtLeast(0)
        val padTop = (dh - 0.1).roundToInt().coerceAtLeast(0)
        val padBottom = (dh + 0.1).roundToInt().coerceAtLeast(0)

        return LetterboxTransform(
            scale = scale,
            padX = padLeft.toFloat(),
            padY = padTop.toFloat(),
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            scaledWidth = scaledW,
            scaledHeight = scaledH,
            padLeft = padLeft,
            padTop = padTop,
            padRight = padRight,
            padBottom = padBottom
        )
    }

    fun prepareNchwFloatInput(
        bitmap: Bitmap,
        targetWidth: Int = TARGET_INPUT_SIZE,
        targetHeight: Int = TARGET_INPUT_SIZE
    ): YoloTensorInput {
        val transform = computeLetterboxTransform(
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )

        var letterboxed: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        val pixelCount = targetWidth * targetHeight
        val pixels = IntArray(pixelCount)
        try {
            val lb = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            letterboxed = lb
            lb.eraseColor(Color.rgb(PAD_VALUE, PAD_VALUE, PAD_VALUE))
            val canvas = Canvas(lb)

            val contentBitmap = if (bitmap.width != transform.scaledWidth || bitmap.height != transform.scaledHeight) {
                Bitmap.createScaledBitmap(bitmap, transform.scaledWidth, transform.scaledHeight, true).also {
                    if (it !== bitmap) scaledBitmap = it
                }
            } else {
                bitmap
            }
            canvas.drawBitmap(
                contentBitmap,
                transform.padLeft.toFloat(),
                transform.padTop.toFloat(),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            // Release scaled intermediate bitmap immediately after compositing onto letterbox canvas
            scaledBitmap?.let { tmp ->
                if (tmp !== bitmap && !tmp.isRecycled) tmp.recycle()
                scaledBitmap = null
            }

            lb.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            if (!lb.isRecycled) lb.recycle()
            letterboxed = null
        } finally {
            scaledBitmap?.let { tmp ->
                if (tmp !== bitmap && !tmp.isRecycled) tmp.recycle()
            }
            letterboxed?.let { lb ->
                if (!lb.isRecycled) lb.recycle()
            }
        }

        val floatBuffer = FloatBuffer.allocate(3 * pixelCount)
        val channelSize = pixelCount
        for (i in 0 until pixelCount) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255.0f
            val g = ((px shr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f
            floatBuffer.put(i, r)
            floatBuffer.put(channelSize + i, g)
            floatBuffer.put(2 * channelSize + i, b)
        }
        floatBuffer.rewind()

        return YoloTensorInput(
            floatBuffer = floatBuffer,
            shape = longArrayOf(1L, 3L, targetHeight.toLong(), targetWidth.toLong()),
            transform = transform
        )
    }
}
