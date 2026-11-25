package com.example.presentation

import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.presentation.screens.formatDefectDisplayLabel
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsScreenDisplayLabelTest {

    private fun createDefect(
        raw: String,
        canonical: String,
        confidence: Float = 0.95f
    ) = DetectedDefect(
        defectId = "d-1",
        classIndex = 0,
        rawClassName = raw,
        canonicalClassName = canonical,
        confidence = confidence,
        severity = DefectSeverity.MANUAL_REVIEW,
        requiresManualReview = true,
        boundingBox = BoundingBox(0.1f, 0.1f, 0.2f, 0.2f),
        code = "TR-ANOMALY-REVIEW"
    )

    @Test
    fun `transistor anomaly raw and canonical classes map to Anomaly for UI presentation`() {
        val defectFromPatchCore = createDefect(
            raw = "transistor_anomaly",
            canonical = "Transistor Anomaly"
        )
        val displayLabel = formatDefectDisplayLabel(defectFromPatchCore)

        // 1. Heading display label must be "Anomaly"
        assertEquals("Anomaly", displayLabel)

        // 2. Bounding box overlay label must show "#1 Anomaly <confidence>%"
        val idx = 0
        val bboxLabel = "#${idx + 1} $displayLabel ${(defectFromPatchCore.confidence * 100).roundToInt()}%"
        assertEquals("#1 Anomaly 95%", bboxLabel)
        assertTrue(bboxLabel.startsWith("#1 Anomaly"))

        // 3. Raw class must remain transistor_anomaly and be displayed because raw != displayLabel
        assertEquals("transistor_anomaly", defectFromPatchCore.rawClassName)
        assertNotEquals(defectFromPatchCore.rawClassName, displayLabel)
    }

    @Test
    fun `transistor anomaly with legacy canonical name maps to Anomaly`() {
        val legacyDefect = createDefect(
            raw = "TR-ANOMALY-REVIEW",
            canonical = "Transistor Anomaly"
        )
        assertEquals("Anomaly", formatDefectDisplayLabel(legacyDefect))
    }

    @Test
    fun `standard PCB defect classes retain their canonical display labels`() {
        val openDefect = createDefect(raw = "open", canonical = "Open Circuit")
        assertEquals("Open Circuit", formatDefectDisplayLabel(openDefect))

        val shortDefect = createDefect(raw = "short", canonical = "Short Circuit")
        assertEquals("Short Circuit", formatDefectDisplayLabel(shortDefect))

        val mouseBiteDefect = createDefect(raw = "mouse_bite", canonical = "Mouse Bite")
        assertEquals("Mouse Bite", formatDefectDisplayLabel(mouseBiteDefect))

        val spurDefect = createDefect(raw = "spur", canonical = "Spur")
        assertEquals("Spur", formatDefectDisplayLabel(spurDefect))

        val spuriousCopperDefect = createDefect(raw = "spurious_copper", canonical = "Spurious Copper")
        assertEquals("Spurious Copper", formatDefectDisplayLabel(spuriousCopperDefect))

        val missingCopperDefect = createDefect(raw = "missing_copper", canonical = "Missing Copper")
        assertEquals("Missing Copper", formatDefectDisplayLabel(missingCopperDefect))
    }
}
