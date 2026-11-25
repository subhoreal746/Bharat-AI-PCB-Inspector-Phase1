package com.example.inference.cv

import com.example.domain.model.BoundingBox
import kotlin.math.max
import kotlin.math.min

data class RawDetectionCandidate(
    val classIndex: Int,
    val confidence: Float,
    val x1Px: Float,
    val y1Px: Float,
    val x2Px: Float,
    val y2Px: Float
)

object NonMaxSuppression {

    /**
     * Global class-agnostic NMS matching cv2.dnn.NMSBoxes in vision_runtime.py:
     * candidate boxes are sorted by descending confidence and any lower-scoring box
     * with IoU > iouThreshold against an already-kept box is suppressed regardless of classId.
     */
    fun applyClassAgnosticNms(
        candidates: List<RawDetectionCandidate>,
        iouThreshold: Float = 0.45f,
        maxDetections: Int = 100
    ): List<RawDetectionCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val sorted = candidates.sortedByDescending { it.confidence }
        val selected = mutableListOf<RawDetectionCandidate>()

        for (candidate in sorted) {
            var suppressed = false
            for (kept in selected) {
                if (iou(kept, candidate) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                selected.add(candidate)
                if (selected.size >= maxDetections) break
            }
        }
        return selected
    }

    fun iou(a: RawDetectionCandidate, b: RawDetectionCandidate): Float {
        val interX1 = max(a.x1Px, b.x1Px)
        val interY1 = max(a.y1Px, b.y1Px)
        val interX2 = min(a.x2Px, b.x2Px)
        val interY2 = min(a.y2Px, b.y2Px)

        val interArea = max(0f, interX2 - interX1) * max(0f, interY2 - interY1)
        if (interArea <= 0f) return 0f

        val areaA = max(0f, a.x2Px - a.x1Px) * max(0f, a.y2Px - a.y1Px)
        val areaB = max(0f, b.x2Px - b.x1Px) * max(0f, b.y2Px - b.y1Px)
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }

    fun toNormalizedBoundingBox(
        candidate: RawDetectionCandidate,
        transform: LetterboxTransform
    ): BoundingBox {
        val mapped = transform.mapBoxToNormalizedOriginal(
            x1InputPx = candidate.x1Px,
            y1InputPx = candidate.y1Px,
            x2InputPx = candidate.x2Px,
            y2InputPx = candidate.y2Px
        )
        return BoundingBox(mapped.x, mapped.y, mapped.width, mapped.height).clamped()
    }
}
