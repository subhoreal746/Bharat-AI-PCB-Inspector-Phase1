package com.example.inference.cv

import android.graphics.Bitmap
import com.example.domain.model.BoundingBox
import com.example.domain.model.DetectedDefect
import com.example.domain.policy.DefectTaxonomy
import java.util.UUID
import kotlin.math.max

sealed interface YoloDecodeResult {
    data class Success(val detections: List<DetectedDefect>) : YoloDecodeResult
    data class UnsupportedShape(val message: String) : YoloDecodeResult
}

object YoloOutputDecoder {
    const val DECODER_VERSION = "yolo-v8-640-fp32-v3"
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.33f
    const val DEFAULT_IOU_THRESHOLD = 0.45f
    const val BORDER_GUARD_MARGIN_PX = 8.0f
    const val BORDER_GUARD_MAX_CONF = 0.50f
    const val BORDER_GUARD_MAX_AREA_PCT = 2.5

    /**
     * Decodes a 3D [1, 4 + C, N] (e.g. [1, 15, 8400]) or [1, N, 4 + C] YOLOv8 ONNX output tensor,
     * reverses letterbox coordinates back to original frame space, applies global class-agnostic NMS,
     * and filters edge border-crop noise. Preserves original model class index and confidence directly
     * without heuristic HSV-based class remapping.
     */
    fun decode(
        flatData: FloatArray,
        shape: LongArray,
        classLabels: List<String>,
        transform: LetterboxTransform,
        sourceBitmap: Bitmap? = null,
        confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD
    ): YoloDecodeResult {
        if (classLabels.isEmpty()) {
            return YoloDecodeResult.UnsupportedShape("Model class labels are empty.")
        }

        val numClasses = classLabels.size
        val expectedChannels = 4 + numClasses

        if (shape.size != 3 || shape[0] != 1L) {
            return YoloDecodeResult.UnsupportedShape(
                "Unsupported ONNX output shape ${shape.contentToString()}; expected [1, $expectedChannels, N] or [1, N, $expectedChannels]."
            )
        }

        val d1 = shape[1].toInt()
        val d2 = shape[2].toInt()
        if (d1 <= 0 || d2 <= 0 || flatData.size < d1 * d2) {
            return YoloDecodeResult.UnsupportedShape(
                "Output buffer size ${flatData.size} inconsistent with shape ${shape.contentToString()}."
            )
        }

        val originalSpaceCandidates = mutableListOf<RawDetectionCandidate>()

        when {
            // Standard YOLOv8 [1, 4 + C, N] -> transposed to [N, 4 + C]
            d1 == expectedChannels -> {
                val numBoxes = d2
                for (b in 0 until numBoxes) {
                    var bestClass = -1
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (c in 0 until numClasses) {
                        val score = flatData[(4 + c) * numBoxes + b]
                        if (score.isFinite() && score > bestScore) {
                            bestScore = score
                            bestClass = c
                        }
                    }
                    if (bestScore >= confidenceThreshold && bestClass >= 0) {
                        val cx = flatData[0 * numBoxes + b]
                        val cy = flatData[1 * numBoxes + b]
                        val bw = flatData[2 * numBoxes + b]
                        val bh = flatData[3 * numBoxes + b]
                        addOriginalSpaceCandidate(
                            candidates = originalSpaceCandidates,
                            transform = transform,
                            classIndex = bestClass,
                            confidence = bestScore,
                            cx = cx,
                            cy = cy,
                            bw = bw,
                            bh = bh
                        )
                    }
                }
            }
            // Transposed YOLOv8 [1, N, 4 + C]
            d2 == expectedChannels -> {
                val numBoxes = d1
                val stride = expectedChannels
                for (b in 0 until numBoxes) {
                    val base = b * stride
                    var bestClass = -1
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (c in 0 until numClasses) {
                        val score = flatData[base + 4 + c]
                        if (score.isFinite() && score > bestScore) {
                            bestScore = score
                            bestClass = c
                        }
                    }
                    if (bestScore >= confidenceThreshold && bestClass >= 0) {
                        val cx = flatData[base]
                        val cy = flatData[base + 1]
                        val bw = flatData[base + 2]
                        val bh = flatData[base + 3]
                        addOriginalSpaceCandidate(
                            candidates = originalSpaceCandidates,
                            transform = transform,
                            classIndex = bestClass,
                            confidence = bestScore,
                            cx = cx,
                            cy = cy,
                            bw = bw,
                            bh = bh
                        )
                    }
                }
            }
            else -> {
                return YoloDecodeResult.UnsupportedShape(
                    "Output shape ${shape.contentToString()} does not match 4 + ${classLabels.size} = $expectedChannels channels."
                )
            }
        }

        if (originalSpaceCandidates.isEmpty()) {
            return YoloDecodeResult.Success(emptyList())
        }

        val kept = NonMaxSuppression.applyClassAgnosticNms(
            candidates = originalSpaceCandidates,
            iouThreshold = iouThreshold
        )

        val w0 = transform.originalWidth
        val h0 = transform.originalHeight
        val w0f = w0.toFloat()
        val h0f = h0.toFloat()
        val frameArea = max(1L, w0.toLong() * h0.toLong()).toDouble()

        val detections = mutableListOf<DetectedDefect>()
        for (cand in kept) {
            val boxWidthPx = (cand.x2Px - cand.x1Px).coerceAtLeast(0f).toDouble()
            val boxHeightPx = (cand.y2Px - cand.y1Px).coerceAtLeast(0f).toDouble()
            val areaPct = ((boxWidthPx * boxHeightPx) / frameArea) * 100.0
            val touchesBorder = (
                cand.x1Px <= BORDER_GUARD_MARGIN_PX ||
                    cand.y1Px <= BORDER_GUARD_MARGIN_PX ||
                    cand.x2Px >= (w0f - BORDER_GUARD_MARGIN_PX) ||
                    cand.y2Px >= (h0f - BORDER_GUARD_MARGIN_PX)
                )
            if (touchesBorder && cand.confidence < BORDER_GUARD_MAX_CONF && areaPct < BORDER_GUARD_MAX_AREA_PCT) {
                continue
            }

            val normalizedBox = BoundingBox(
                x = cand.x1Px / w0f,
                y = cand.y1Px / h0f,
                width = (cand.x2Px - cand.x1Px) / w0f,
                height = (cand.y2Px - cand.y1Px) / h0f
            ).clamped()

            detections.add(
                toDetectedDefect(
                    classIndex = cand.classIndex,
                    confidence = cand.confidence,
                    normalizedBox = normalizedBox,
                    classLabels = classLabels
                )
            )
        }

        return YoloDecodeResult.Success(detections)
    }

    /**
     * Converts clamped floating-point coordinates in [0..w0, 0..h0] to integer ROI bounds
     * by truncating toward zero (`Float.toInt()`), matching Python `int(max(0, min(dim, coord)))`
     * in `reference_python/vision_runtime.py`.
     */
    internal fun toTruncatedRoiPixelBounds(
        cand: RawDetectionCandidate,
        maxWidth: Int,
        maxHeight: Int
    ): IntArray {
        val x1 = cand.x1Px.coerceIn(0f, maxWidth.toFloat()).toInt()
        val y1 = cand.y1Px.coerceIn(0f, maxHeight.toFloat()).toInt()
        val x2 = cand.x2Px.coerceIn(0f, maxWidth.toFloat()).toInt()
        val y2 = cand.y2Px.coerceIn(0f, maxHeight.toFloat()).toInt()
        return intArrayOf(x1, y1, x2, y2)
    }

    private fun addOriginalSpaceCandidate(
        candidates: MutableList<RawDetectionCandidate>,
        transform: LetterboxTransform,
        classIndex: Int,
        confidence: Float,
        cx: Float,
        cy: Float,
        bw: Float,
        bh: Float
    ) {
        if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) return
        if (bw <= 0f || bh <= 0f) return

        val orig = transform.mapBoxToOriginalPixels(
            x1InputPx = cx - bw / 2f,
            y1InputPx = cy - bh / 2f,
            x2InputPx = cx + bw / 2f,
            y2InputPx = cy + bh / 2f
        )
        if (orig.x2 <= orig.x1 || orig.y2 <= orig.y1) return

        candidates.add(
            RawDetectionCandidate(
                classIndex = classIndex,
                confidence = confidence.coerceIn(0f, 1f),
                x1Px = orig.x1,
                y1Px = orig.y1,
                x2Px = orig.x2,
                y2Px = orig.y2
            )
        )
    }

    private fun toDetectedDefect(
        classIndex: Int,
        confidence: Float,
        normalizedBox: BoundingBox,
        classLabels: List<String>
    ): DetectedDefect {
        val rawLabel = classLabels.getOrElse(classIndex) { "unknown_class_$classIndex" }
        val taxonomy = DefectTaxonomy.resolve(rawLabel)
        return DetectedDefect(
            defectId = "det-${UUID.randomUUID().toString().take(8)}",
            classIndex = classIndex,
            rawClassName = rawLabel,
            canonicalClassName = taxonomy.canonicalLabel,
            confidence = confidence,
            severity = taxonomy.defaultSeverity,
            requiresManualReview = taxonomy.requiresManualReview || !taxonomy.isKnownClass,
            boundingBox = normalizedBox,
            code = taxonomy.code,
            description = taxonomy.description
        )
    }
}
