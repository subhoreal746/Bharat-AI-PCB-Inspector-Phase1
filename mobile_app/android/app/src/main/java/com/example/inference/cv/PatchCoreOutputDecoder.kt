package com.example.inference.cv

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

sealed interface PatchCoreDecodeResult {
    data class Success(
        val rawAnomalyScore: Float,
        val displayScore: Float,
        val isAnomalous: Boolean,
        val boundingBox: PatchCorePixelBoundingBox?,
        val areaPct: Float
    ) : PatchCoreDecodeResult

    data class InvalidInferenceOutput(
        val reason: String
    ) : PatchCoreDecodeResult
}

data class PatchCorePixelBoundingBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int
) {
    val width: Int get() = max(0, x2 - x1)
    val height: Int get() = max(0, y2 - y1)
}

/**
 * Output decoder for PatchCore unsupervised anomaly detection.
 *
 * Implements the following semantics:
 * - Read raw score from pred_score[0].
 * - Validates score array: requires exactly 2 float elements matching ONNX output shape [2].
 * - Validates raw anomaly score: rejects NaN, positive infinity, and negative infinity explicitly.
 * - Validates anomaly map integrity on every inference (regardless of whether score is PASS or FAIL):
 *   1. Requires positive original image dimensions (width > 0, height > 0).
 *   2. Requires exactly 65,536 float values (256x256).
 *   3. Rejects non-finite values (NaN, +Inf, -Inf).
 *   4. Rejects floating-point range overflow (maxVal - minVal).
 * - Raw anomaly decision threshold: 39.5f.
 * - Display-score formula: 1.0 / (1.0 + exp(-(rawScore - 39.5) * 0.6))
 *   (Heuristic normalized display score; not a calibrated probability).
 * - Localization (performed only when isAnomalous == true):
 *   1. If finite range < 1e-8, map is flat -> returns null bounding box.
 *   2. Normalize to [0, 255.0f]: (val - min) / range * 255.0f, validating intermediate finite status.
 *   3. Truncate to uint8 (matching Python `.astype(np.uint8)`).
 *   4. Strict threshold > 195 (matching OpenCV cv2.threshold(..., 195, 255, cv2.THRESH_BINARY)).
 *   5. Largest-component selection currently uses 8-connected BFS pixel count.
 *      (Note: BFS pixel-count selection is not generally equivalent to OpenCV contourArea()).
 *   6. Coordinate scaling uses Python ties-to-even rounding:
 *      x1 = pythonRound(x * scaleX)
 *      y1 = pythonRound(y * scaleY)
 *      x2 = pythonRound((x + w) * scaleX)
 *      y2 = pythonRound((y + h) * scaleY)
 *   7. If anomalous but no region exceeds threshold, returns null bounding box.
 */
object PatchCoreOutputDecoder {
    const val DECODER_VERSION = "patchcore-anomaly-v1"
    const val RAW_THRESHOLD = 39.5f
    const val MASK_THRESHOLD = 195
    const val EPSILON = 1e-8f
    const val MAP_SIZE = 256
    const val EXACT_MAP_SIZE = MAP_SIZE * MAP_SIZE // 65,536
    const val EXACT_SCORE_SIZE = 2

    /**
     * Python-compatible round() implementation using ties-to-even rounding for non-negative values.
     * For x.5, rounds to the nearest even integer:
     * - 0.5 -> 0
     * - 1.5 -> 2
     * - 2.5 -> 2
     * - 3.5 -> 4
     * - 4.5 -> 4
     */
    fun pythonRound(value: Double): Int {
        if (!value.isFinite()) return 0
        if (value <= 0.0) return 0
        val floorVal = floor(value)
        val diff = value - floorVal
        return if (diff < 0.5) {
            floorVal.toInt()
        } else if (diff > 0.5) {
            (floorVal + 1.0).toInt()
        } else {
            // Exact half: round to nearest even
            val floorInt = floorVal.toLong()
            if (floorInt % 2L == 0L) {
                floorInt.toInt()
            } else {
                (floorInt + 1L).toInt()
            }
        }
    }

    sealed interface MapValidationResult {
        data class Valid(val minVal: Float, val maxVal: Float, val range: Float) : MapValidationResult
        data class Invalid(val reason: String) : MapValidationResult
    }

    /**
     * Validates the anomaly map integrity for size, non-finite values, and range overflow.
     * Must be evaluated on EVERY inference result regardless of raw score.
     */
    fun validateAnomalyMapIntegrity(anomalyMap: FloatArray): MapValidationResult {
        if (anomalyMap.size != EXACT_MAP_SIZE) {
            return MapValidationResult.Invalid(
                "Invalid anomaly_map size ${anomalyMap.size}; expected exact $EXACT_MAP_SIZE float elements."
            )
        }

        var minVal = Float.POSITIVE_INFINITY
        var maxVal = Float.NEGATIVE_INFINITY

        for (i in 0 until EXACT_MAP_SIZE) {
            val v = anomalyMap[i]
            if (v.isNaN()) {
                return MapValidationResult.Invalid("NaN pixel encountered at index $i.")
            }
            if (v.isInfinite()) {
                return MapValidationResult.Invalid(
                    "${if (v > 0) "+Inf" else "-Inf"} pixel encountered at index $i."
                )
            }
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }

        val range = maxVal - minVal
        if (range.isNaN() || range.isInfinite()) {
            return MapValidationResult.Invalid(
                "Floating-point overflow calculating anomaly map range (min=$minVal, max=$maxVal, range=$range)."
            )
        }

        return MapValidationResult.Valid(minVal, maxVal, range)
    }

    fun decode(
        predScores: FloatArray,
        anomalyMap: FloatArray,
        originalWidth: Int,
        originalHeight: Int
    ): PatchCoreDecodeResult {
        // Enforce exact score array size 2
        if (predScores.size != EXACT_SCORE_SIZE) {
            return PatchCoreDecodeResult.InvalidInferenceOutput(
                "Invalid pred_score size ${predScores.size}; expected exact $EXACT_SCORE_SIZE elements."
            )
        }

        val rawScore = predScores[0]

        // Reject non-finite raw anomaly score
        if (rawScore.isNaN()) {
            return PatchCoreDecodeResult.InvalidInferenceOutput("pred_score is NaN; invalid inference output.")
        }
        if (rawScore.isInfinite()) {
            return PatchCoreDecodeResult.InvalidInferenceOutput(
                "pred_score is infinite (${if (rawScore > 0) "+Inf" else "-Inf"}); invalid inference output."
            )
        }

        // Validate original image dimensions
        if (originalWidth <= 0 || originalHeight <= 0) {
            return PatchCoreDecodeResult.InvalidInferenceOutput(
                "Invalid original image dimensions ${originalWidth}x$originalHeight; expected positive width and height."
            )
        }

        // Validate anomaly-map integrity on EVERY result (both anomalous and non-anomalous)
        val mapValidation = validateAnomalyMapIntegrity(anomalyMap)
        if (mapValidation is MapValidationResult.Invalid) {
            return PatchCoreDecodeResult.InvalidInferenceOutput(
                "anomaly_map validation failed: ${mapValidation.reason}"
            )
        }
        val validatedMap = mapValidation as MapValidationResult.Valid

        val isAnomalous = rawScore >= RAW_THRESHOLD

        // Heuristic normalized display score; not a calibrated probability.
        val displayScore = 1.0f / (1.0f + exp(-(rawScore - RAW_THRESHOLD) * 0.6f))

        var boundingBox: PatchCorePixelBoundingBox? = null
        var areaPct = 0.0f

        // Bounding-box localization remains conditional on isAnomalous
        if (isAnomalous) {
            val localizationResult = localizeValidMap(
                anomalyMap = anomalyMap,
                minVal = validatedMap.minVal,
                range = validatedMap.range,
                originalWidth = originalWidth,
                originalHeight = originalHeight
            )
            when (localizationResult) {
                is LocalizationResult.Success -> {
                    boundingBox = localizationResult.boundingBox
                    if (boundingBox != null) {
                        val frameArea = (max(1, originalWidth) * max(1, originalHeight)).toFloat()
                        val boxArea = (boundingBox.width * boundingBox.height).toFloat()
                        areaPct = (boxArea / frameArea) * 100.0f
                    }
                }
                is LocalizationResult.InvalidMap -> {
                    return PatchCoreDecodeResult.InvalidInferenceOutput(
                        "anomaly_map localization failed: ${localizationResult.reason}"
                    )
                }
            }
        }

        return PatchCoreDecodeResult.Success(
            rawAnomalyScore = rawScore,
            displayScore = displayScore,
            isAnomalous = isAnomalous,
            boundingBox = boundingBox,
            areaPct = areaPct
        )
    }

    sealed interface LocalizationResult {
        data class Success(val boundingBox: PatchCorePixelBoundingBox?) : LocalizationResult
        data class InvalidMap(val reason: String) : LocalizationResult
    }

    /**
     * Localizes the anomaly on the validated 256x256 anomaly heatmap.
     */
    fun localize(
        anomalyMap: FloatArray,
        originalWidth: Int,
        originalHeight: Int
    ): LocalizationResult {
        if (originalWidth <= 0 || originalHeight <= 0) {
            return LocalizationResult.InvalidMap(
                "Invalid original image dimensions ${originalWidth}x$originalHeight; must be positive."
            )
        }

        val mapValidation = validateAnomalyMapIntegrity(anomalyMap)
        if (mapValidation is MapValidationResult.Invalid) {
            return LocalizationResult.InvalidMap(mapValidation.reason)
        }
        val valid = mapValidation as MapValidationResult.Valid

        return localizeValidMap(
            anomalyMap = anomalyMap,
            minVal = valid.minVal,
            range = valid.range,
            originalWidth = originalWidth,
            originalHeight = originalHeight
        )
    }

    private fun localizeValidMap(
        anomalyMap: FloatArray,
        minVal: Float,
        range: Float,
        originalWidth: Int,
        originalHeight: Int
    ): LocalizationResult {
        // Flat map check: finite range < EPSILON
        if (range < EPSILON) {
            return LocalizationResult.Success(null)
        }

        // Normalize to [0, 255.0] and check intermediate finite status before uint8 truncation
        // Apply strict threshold > 195 (matching OpenCV cv2.threshold with THRESH_BINARY)
        val binary = BooleanArray(EXACT_MAP_SIZE)
        var anyPositive = false
        for (i in 0 until EXACT_MAP_SIZE) {
            val normFloat = ((anomalyMap[i] - minVal) / range) * 255.0f
            if (!normFloat.isFinite()) {
                return LocalizationResult.InvalidMap(
                    "Non-finite intermediate normalized value at index $i: $normFloat."
                )
            }
            // Truncate to uint8 (float to Int)
            val u8 = normFloat.toInt().coerceIn(0, 255)
            // Strict inequality > 195
            if (u8 > MASK_THRESHOLD) {
                binary[i] = true
                anyPositive = true
            }
        }

        if (!anyPositive) {
            return LocalizationResult.Success(null)
        }

        // Connected components labeling (8-connectivity) to extract external regions
        val rect = findLargestComponentBoundingBox(binary, MAP_SIZE, MAP_SIZE)
            ?: return LocalizationResult.Success(null)

        // Scale bounding box coordinates using Python reference ties-to-even round()
        val scaleX = originalWidth.toDouble() / MAP_SIZE.toDouble()
        val scaleY = originalHeight.toDouble() / MAP_SIZE.toDouble()

        val x1 = pythonRound(rect.x1 * scaleX)
        val y1 = pythonRound(rect.y1 * scaleY)
        val x2 = pythonRound(rect.x2 * scaleX)
        val y2 = pythonRound(rect.y2 * scaleY)

        val box = PatchCorePixelBoundingBox(
            x1 = x1.coerceIn(0, originalWidth),
            y1 = y1.coerceIn(0, originalHeight),
            x2 = x2.coerceIn(0, originalWidth),
            y2 = y2.coerceIn(0, originalHeight)
        )
        return LocalizationResult.Success(box)
    }

    private data class GridRect(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

    /**
     * Finds the largest connected component (8-connectivity) by pixel count and returns
     * its bounding box [x1, y1, x2, y2] where x2 = x1 + w and y2 = y1 + h.
     */
    private fun findLargestComponentBoundingBox(
        binary: BooleanArray,
        width: Int,
        height: Int
    ): GridRect? {
        val visited = BooleanArray(width * height)
        var maxArea = 0
        var bestRect: GridRect? = null

        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!binary[idx] || visited[idx]) continue

                // BFS 8-way flood fill
                var head = 0
                var tail = 0
                queueX[tail] = x
                queueY[tail] = y
                tail++
                visited[idx] = true

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var componentArea = 0

                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++
                    componentArea++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    // 8-neighbor exploration
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIdx = ny * width + nx
                                if (binary[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }
                }

                if (componentArea > maxArea) {
                    maxArea = componentArea
                    // Bounding rectangle: [minX, minY, maxX + 1, maxY + 1]
                    bestRect = GridRect(minX, minY, maxX + 1, maxY + 1)
                }
            }
        }

        return bestRect
    }
}
