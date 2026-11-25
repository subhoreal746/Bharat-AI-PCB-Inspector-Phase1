package com.example.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Prompt3DomainIntegrationTest {

    @Test
    fun `inspection targets distinguish PCB board and transistor pipelines`() {
        assertEquals("PCB_BOARD", InspectionTarget.PCB_BOARD.name)
        assertEquals("TRANSISTOR", InspectionTarget.TRANSISTOR.name)
    }

    @Test
    fun `model slot includes TRANSISTOR_ANOMALY while preserving existing slots`() {
        val slots = ModelSlot.values().map { it.name }
        assertTrue(slots.contains("PCB_DETECTION"))
        assertTrue(slots.contains("AI_ADVISORY"))
        assertTrue(slots.contains("TRANSISTOR_ANOMALY"))
    }

    @Test
    fun `detected defect supports both valid and null bounding boxes`() {
        val defectWithBox = DetectedDefect(
            defectId = "d-box",
            classIndex = 1,
            rawClassName = "short",
            canonicalClassName = "Short Circuit",
            confidence = 0.95f,
            severity = DefectSeverity.CRITICAL,
            requiresManualReview = true,
            boundingBox = BoundingBox(0.1f, 0.2f, 0.3f, 0.4f)
        )
        assertNotNull(defectWithBox.boundingBox)
        assertEquals(0.1f, defectWithBox.boundingBox!!.x, 0.0001f)

        val defectWithoutBox = DetectedDefect(
            defectId = "d-nobox",
            classIndex = 0,
            rawClassName = "transistor_anomaly",
            canonicalClassName = "Transistor Anomaly",
            confidence = 0.88f,
            severity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            boundingBox = null
        )
        assertNull(defectWithoutBox.boundingBox)
    }

    @Test
    fun `transistor anomaly data preserves raw score display score and anomaly flag`() {
        val anomalyData = TransistorAnomalyData(
            rawAnomalyScore = 42.5f,
            normalizedDisplayScore = 0.895f,
            isAnomalous = true,
            affectedAreaPercent = 4.2f,
            boundingBox = BoundingBox(0.2f, 0.2f, 0.1f, 0.1f)
        )
        assertEquals(42.5f, anomalyData.rawAnomalyScore, 0.001f)
        assertEquals(0.895f, anomalyData.normalizedDisplayScore, 0.001f)
        assertTrue(anomalyData.isAnomalous)
        assertEquals(4.2f, anomalyData.affectedAreaPercent!!, 0.001f)
        assertNotNull(anomalyData.boundingBox)

        val nonAnomalousData = TransistorAnomalyData(
            rawAnomalyScore = 21.0f,
            normalizedDisplayScore = 0.045f,
            isAnomalous = false,
            affectedAreaPercent = null,
            boundingBox = null
        )
        assertEquals(21.0f, nonAnomalousData.rawAnomalyScore, 0.001f)
        assertEquals(0.045f, nonAnomalousData.normalizedDisplayScore, 0.001f)
        assertFalse(nonAnomalousData.isAnomalous)
        assertNull(nonAnomalousData.affectedAreaPercent)
        assertNull(nonAnomalousData.boundingBox)
    }

    @Test
    fun `inspection record defaults target to PCB_BOARD and supports transistor target`() {
        val cfg = InspectionPolicyConfig()
        val pcbRecord = InspectionRecord(
            inspectionId = "PCB-DEF",
            timestampEpochMs = 1000L,
            boardName = "Board",
            sourceType = PcbImageSource.CAMERA,
            imageUri = "file:///img",
            imageWidth = 800,
            imageHeight = 600,
            lowClarityRisk = false,
            clarityScore = 100.0,
            officialDisposition = InspectionDisposition.PASS,
            policyProfile = cfg.profileName,
            policyVersion = cfg.policyVersion,
            policyConfigSnapshot = cfg,
            effectiveThresholdsSummary = "summary",
            policyRationale = "rationale",
            defects = emptyList(),
            totalDefects = 0,
            isDemonstration = false,
            provenance = ModelExecutionProvenance.unavailableProduction("ort"),
            advisory = AiAdvisory(AdvisoryAvailability.UNAVAILABLE, false),
            latencyMetrics = InspectionLatencyMetrics()
        )
        assertEquals(InspectionTarget.PCB_BOARD, pcbRecord.target)
        assertNull(pcbRecord.transistorAnomaly)
        assertNull(pcbRecord.completedEpochMs)

        val transistorRecord = pcbRecord.copy(
            inspectionId = "TR-101",
            target = InspectionTarget.TRANSISTOR,
            transistorAnomaly = TransistorAnomalyData(
                rawAnomalyScore = 45.0f,
                normalizedDisplayScore = 0.92f,
                isAnomalous = true
            ),
            completedEpochMs = 1500L
        )
        assertEquals(InspectionTarget.TRANSISTOR, transistorRecord.target)
        assertNotNull(transistorRecord.transistorAnomaly)
        assertTrue(transistorRecord.transistorAnomaly!!.isAnomalous)
        assertEquals(1500L, transistorRecord.completedEpochMs)
    }

    @Test
    fun `target-aware modelInferenceLatencyMs on InspectionRecord resolves strictly per target`() {
        val cfg = InspectionPolicyConfig()
        val base = InspectionRecord(
            inspectionId = "REC-1",
            timestampEpochMs = 1000L,
            boardName = "Board",
            sourceType = PcbImageSource.CAMERA,
            imageUri = "file:///img",
            imageWidth = 800,
            imageHeight = 600,
            lowClarityRisk = false,
            clarityScore = 100.0,
            officialDisposition = InspectionDisposition.PASS,
            policyProfile = cfg.profileName,
            policyVersion = cfg.policyVersion,
            policyConfigSnapshot = cfg,
            effectiveThresholdsSummary = "summary",
            policyRationale = "rationale",
            defects = emptyList(),
            totalDefects = 0,
            isDemonstration = false,
            provenance = ModelExecutionProvenance.unavailableProduction("ort"),
            advisory = AiAdvisory(AdvisoryAvailability.UNAVAILABLE, false),
            latencyMetrics = InspectionLatencyMetrics(
                yoloInferenceMs = 35L,
                patchCoreInferenceMs = 20L
            ),
            target = InspectionTarget.PCB_BOARD
        )

        // Conflicting measurements present: PCB_BOARD strictly selects yoloInferenceMs
        assertEquals(35L, base.modelInferenceLatencyMs)

        // TRANSISTOR with both measurements strictly selects patchCoreInferenceMs
        val transistorWithBoth = base.copy(target = InspectionTarget.TRANSISTOR)
        assertEquals(20L, transistorWithBoth.modelInferenceLatencyMs)

        // PCB_BOARD with missing yolo timing returns null, NEVER falling back to patchCore timing
        val pcbMissingYolo = base.copy(
            latencyMetrics = InspectionLatencyMetrics(yoloInferenceMs = null, patchCoreInferenceMs = 20L)
        )
        assertNull(pcbMissingYolo.modelInferenceLatencyMs)

        // TRANSISTOR with missing patchcore timing returns null, NEVER falling back to yolo timing
        val transistorMissingPatchCore = base.copy(
            target = InspectionTarget.TRANSISTOR,
            latencyMetrics = InspectionLatencyMetrics(yoloInferenceMs = 35L, patchCoreInferenceMs = null)
        )
        assertNull(transistorMissingPatchCore.modelInferenceLatencyMs)
    }

    @Test
    fun `completedEpochMs preserves null and explicit timestamps without fallback calculation`() {
        val cfg = InspectionPolicyConfig()
        val recordWithNull = InspectionRecord(
            inspectionId = "REC-NULL-TIME",
            timestampEpochMs = 1000L,
            boardName = "Board",
            sourceType = PcbImageSource.CAMERA,
            imageUri = "file:///img",
            imageWidth = 800,
            imageHeight = 600,
            lowClarityRisk = false,
            clarityScore = 100.0,
            officialDisposition = InspectionDisposition.PASS,
            policyProfile = cfg.profileName,
            policyVersion = cfg.policyVersion,
            policyConfigSnapshot = cfg,
            effectiveThresholdsSummary = "summary",
            policyRationale = "rationale",
            defects = emptyList(),
            totalDefects = 0,
            isDemonstration = false,
            provenance = ModelExecutionProvenance.unavailableProduction("ort"),
            advisory = AiAdvisory(AdvisoryAvailability.UNAVAILABLE, false),
            latencyMetrics = InspectionLatencyMetrics(totalDurationMs = 50L),
            completedEpochMs = null
        )
        // Must remain null, never inferred as timestampEpochMs + totalDurationMs (1050L)
        assertNull(recordWithNull.completedEpochMs)

        val recordWithExplicit = recordWithNull.copy(completedEpochMs = 1060L)
        assertEquals(1060L, recordWithExplicit.completedEpochMs)
    }
}
