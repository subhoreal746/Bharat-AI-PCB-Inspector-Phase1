package com.example.inference.cv

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Green populated PCB defect refiner (no-op).
 *
 * Automatic HSV-based class remapping was removed from production inference to eliminate
 * false short-circuit (class 1) and missing-copper (class 5) classifications from background
 * textures, white cloth, metal connectors, and bright metallic ROIs.
 *
 * Preserves the original YOLO model classification directly.
 */
object GreenPcbDefectRefiner {
    const val TARGET_SCRATCH_CLASS_ID = 9
    const val REMAPPED_MISSING_COPPER_CLASS_ID = 5
    const val REMAPPED_SHORT_CLASS_ID = 1

    const val GREEN_MIN_H_CV = 35
    const val GREEN_MAX_H_CV = 95
    const val GREEN_MIN_S_CV = 40
    const val GREEN_BOARD_MIN_RATIO = 0.15f

    const val CHAR_MAX_V_CV_EXCLUSIVE = 65
    const val CHAR_MIN_RATIO = 0.16f

    const val SOLDER_MIN_V_CV_EXCLUSIVE = 165
    const val SOLDER_MAX_S_CV_EXCLUSIVE = 75
    const val SOLDER_MIN_RATIO = 0.25f

    private const val HSV_SHIFT = 12
    private const val HSV_HALF_SHIFT = 1 shl (HSV_SHIFT - 1) // 2048

    // OpenCV RGB2HSV_b fixed-point lookup tables (modules/imgproc/src/color_hsv.simd.hpp)
    private val sdivTable = IntArray(256) { i ->
        if (i == 0) 0 else Math.round((255 shl HSV_SHIFT) / i.toDouble()).toInt()
    }
    private val hdivTable180 = IntArray(256) { i ->
        if (i == 0) 0 else Math.round((180 shl HSV_SHIFT) / (6.0 * i)).toInt()
    }

    fun isGreenSolderMaskBoard(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return isGreenSolderMaskBoard(pixels)
    }

    fun isGreenSolderMaskBoard(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var greenCount = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val packedHsv = rgbToOpenCvHsvPacked(r, g, b)
            val hCv = (packedHsv ushr 16) and 0xFF
            val sCv = (packedHsv ushr 8) and 0xFF
            if (hCv in GREEN_MIN_H_CV..GREEN_MAX_H_CV && sCv >= GREEN_MIN_S_CV) {
                greenCount++
            }
        }
        return (greenCount.toFloat() / pixels.size.toFloat()) >= GREEN_BOARD_MIN_RATIO
    }

    /**
     * Preserves original YOLO class ID directly without HSV-based remapping.
     * Automatic remapping to class 1 (short) or class 5 (missing_copper) has been removed.
     */
    fun refineClassId(
        bitmap: Bitmap,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        classId: Int,
        knownGreenBoard: Boolean? = null
    ): Int = classId

    /**
     * Preserves original YOLO class ID directly without HSV-based remapping.
     */
    internal fun refineRoiPixels(roiPixels: IntArray, classId: Int): Int = classId

    /**
     * Converts 8-bit RGB (0..255) to packed OpenCV `RGB2HSV_b` 8-bit HSV using 12-bit fixed-point LUTs:
     * bits 16..23: H in [0, 179], bits 8..15: S in [0, 255], bits 0..7: V in [0, 255].
     */
    internal fun rgbToOpenCvHsvPacked(r: Int, g: Int, b: Int): Int {
        val rc = r.coerceIn(0, 255)
        val gc = g.coerceIn(0, 255)
        val bc = b.coerceIn(0, 255)
        val vmax = max(rc, max(gc, bc))
        val vmin = min(rc, min(gc, bc))
        val diff = vmax - vmin
        val v = vmax
        val s = if (vmax == 0) 0 else ((diff * sdivTable[vmax] + HSV_HALF_SHIFT) shr HSV_SHIFT).coerceIn(0, 255)
        var h = if (diff == 0) {
            0
        } else {
            val sectorNumerator = when (vmax) {
                rc -> gc - bc
                gc -> bc - rc + (diff shl 1)
                else -> rc - gc + (diff shl 2)
            }
            (sectorNumerator * hdivTable180[diff] + HSV_HALF_SHIFT) shr HSV_SHIFT
        }
        if (h < 0) h += 180
        if (h >= 180) h -= 180
        return (h shl 16) or (s shl 8) or v
    }
}
