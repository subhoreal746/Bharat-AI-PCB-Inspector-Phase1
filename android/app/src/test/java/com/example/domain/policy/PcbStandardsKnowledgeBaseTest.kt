package com.example.domain.policy

import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcbStandardsKnowledgeBaseTest {

    @Test
    fun `critical open circuit identifies IPC-6012 and IPC-9252 but acknowledges clause is not verified`() {
        val defects = listOf(
            createDefect("open", "Open Circuit", DefectSeverity.CRITICAL)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)

        assertEquals(1, refs.size)
        assertTrue(refs[0].contains("IPC-6012 / IPC-9252"))
        assertTrue(refs[0].contains("Bare-Board Conductor Integrity & Continuity"))
        assertTrue(refs[0].contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
        // Verify no fictitious clause numbers are asserted
        assertFalse(refs[0].contains("Section 3.5.1"))
    }

    @Test
    fun `critical short circuit distinguishes bare board IPC-6012 and assembly IPC-A-610 with unverified clause fallback`() {
        val defects = listOf(
            createDefect("short", "Short Circuit", DefectSeverity.CRITICAL)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)

        assertEquals(1, refs.size)
        assertTrue(refs[0].contains("IPC-6012 (Bare Board)"))
        assertTrue(refs[0].contains("IPC-A-610 (Assembly Solder Bridging)"))
        assertTrue(refs[0].contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
        assertFalse(refs[0].contains("Section 10.1"))
    }

    @Test
    fun `medium spurious copper identifies IPC-6012 and IPC-A-600 with unverified clause notice`() {
        val defects = listOf(
            createDefect("spurious_copper", "Spurious Copper", DefectSeverity.MEDIUM)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)

        assertEquals(1, refs.size)
        assertTrue(refs[0].contains("IPC-6012 / IPC-A-600"))
        assertTrue(refs[0].contains("Bare-Board Residual Copper Isolation"))
        assertTrue(refs[0].contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
        assertFalse(refs[0].contains("Section 3.5.2"))
    }

    @Test
    fun `medium mouse bite and spur identify IPC-6012 and IPC-A-600 domain without fabricated clause numbers`() {
        val defects = listOf(
            createDefect("mouse_bite", "Mouse Bite", DefectSeverity.MEDIUM),
            createDefect("spur", "Spur", DefectSeverity.MEDIUM)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)

        assertEquals(2, refs.size)
        assertTrue(refs.any { it.contains("Conductor Edge Imperfections") && it.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD) })
        assertTrue(refs.any { it.contains("Conductor Spacing & Protrusions") && it.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD) })
        assertFalse(refs.any { it.contains("Section 3.5.2") })
    }

    @Test
    fun `unverified defects return explicit unverified standard fallback with NO_RELIABLE_MAPPING status`() {
        val defects = listOf(
            createDefect("copper_burr", "Copper Burr", DefectSeverity.MANUAL_REVIEW),
            createDefect("scratch", "Scratch", DefectSeverity.MANUAL_REVIEW)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)

        assertEquals(2, refs.size)
        assertTrue(refs.all { it.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD) })

        val auditBurr = PcbStandardsKnowledgeBase.getAuditEntry("copper_burr", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, auditBurr.status)

        val auditScratch = PcbStandardsKnowledgeBase.getAuditEntry("scratch", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, auditScratch.status)
    }

    @Test
    fun `generic anomaly does not select semiconductor standards and returns unverified fallback`() {
        val defects = listOf(
            createDefect("transistor_anomaly", "Anomaly", DefectSeverity.MANUAL_REVIEW)
        )
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.TRANSISTOR)

        assertEquals(1, refs.size)
        assertFalse("Must not select JEDEC standards for generic anomaly", refs[0].contains("JEDEC"))
        assertFalse("Must not select semiconductor standards for generic anomaly", refs[0].contains("Semiconductor"))
        assertEquals(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD, refs[0])
    }

    @Test
    fun `empty defects return general IPC-6012 conformance baseline with unverified clause notice`() {
        val refs = PcbStandardsKnowledgeBase.getVerifiedReferences(emptyList(), InspectionTarget.PCB_BOARD)
        assertEquals(1, refs.size)
        assertTrue(refs[0].contains("IPC-6012"))
        assertTrue(refs[0].contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
    }

    @Test
    fun `audit entries accurately distinguish verification statuses across all 11 classes`() {
        // Category 2: Standard identified, clause not verified
        val openAudit = PcbStandardsKnowledgeBase.getAuditEntry("open", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, openAudit.status)

        val shortAudit = PcbStandardsKnowledgeBase.getAuditEntry("short", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, shortAudit.status)

        val mbiteAudit = PcbStandardsKnowledgeBase.getAuditEntry("mouse_bite", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, mbiteAudit.status)

        val spurAudit = PcbStandardsKnowledgeBase.getAuditEntry("spur", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, spurAudit.status)

        val spurcuAudit = PcbStandardsKnowledgeBase.getAuditEntry("spurious_copper", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, spurcuAudit.status)

        val misscuAudit = PcbStandardsKnowledgeBase.getAuditEntry("missing_copper", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, misscuAudit.status)

        val pinholeAudit = PcbStandardsKnowledgeBase.getAuditEntry("pin_hole", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED, pinholeAudit.status)

        // Category 3: No reliable mapping available
        val trAudit = PcbStandardsKnowledgeBase.getAuditEntry("transistor_anomaly", InspectionTarget.TRANSISTOR)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, trAudit.status)

        val burrAudit = PcbStandardsKnowledgeBase.getAuditEntry("copper_burr", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, burrAudit.status)

        val hbreakAudit = PcbStandardsKnowledgeBase.getAuditEntry("hole_breakout", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, hbreakAudit.status)

        val scratchAudit = PcbStandardsKnowledgeBase.getAuditEntry("scratch", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, scratchAudit.status)

        val copperAudit = PcbStandardsKnowledgeBase.getAuditEntry("copper", InspectionTarget.PCB_BOARD)
        assertEquals(PcbStandardsKnowledgeBase.VerificationStatus.NO_RELIABLE_MAPPING, copperAudit.status)
    }

    private fun createDefect(key: String, label: String, severity: DefectSeverity): DetectedDefect {
        return DetectedDefect(
            defectId = "d-$key",
            classIndex = 0,
            rawClassName = key,
            canonicalClassName = label,
            confidence = 0.95f,
            severity = severity,
            requiresManualReview = (severity != DefectSeverity.CRITICAL),
            boundingBox = BoundingBox(0.1f, 0.2f, 0.05f, 0.05f)
        )
    }
}
