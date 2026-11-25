package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.InspectionDisposition
import com.example.domain.policy.PcbStandardsKnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisoryParserTest {

    @Test
    fun `valid structured JSON parses cleanly and suppresses unverified standard citations`() {
        val raw = """
            Here is the report:
            {
              "probable_root_cause": "Excess solder paste volume bridged adjacent QFP leads.",
              "recommended_operator_action": "Inspect leads under magnification and rework bridge with flux.",
              "relevant_standard": "IPC-A-610 Section 8.3.2",
              "limitations": "Advisory generated from localized bounding box metadata only."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = raw,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 420L,
            provenanceSummary = "ExecuTorch"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD, advisory.relevantStandard)
        assertEquals(InspectionDisposition.REJECT, advisory.suggestedDisposition)
    }

    @Test
    fun `valid structured JSON preserves verified standard when verified references are present`() {
        val raw = """
            {
              "probable_root_cause": "Excess solder paste volume bridged adjacent traces.",
              "recommended_operator_action": "Inspect under 10x magnification and rework bridge with flux and wick.",
              "relevant_standard": "Standard identified: IPC-6012 / IPC-A-610. Specific standard or clause not verified.",
              "limitations": "2D optical inspection cannot verify electrical isolation; DMM isolation test required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = raw,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 380L,
            provenanceSummary = "Llama 3.2 3B Instruct • ExecuTorch 1.6.0"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals("Standard identified: IPC-6012 / IPC-A-610. Specific standard or clause not verified.", advisory.relevantStandard)
        assertEquals(InspectionDisposition.REJECT, advisory.suggestedDisposition)
    }

    @Test
    fun `omitted relevant_standard field returns UNAVAILABLE safely`() {
        val missingStandardJson = """
            {
              "probable_root_cause": "Trace discontinuity caused by etching void.",
              "recommended_operator_action": "Inspect with DMM.",
              "limitations": "Optical analysis only."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = missingStandardJson,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 200L,
            provenanceSummary = "ExecuTorch"
        )

        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
        assertTrue(advisory.unavailableReason!!.contains("omitted required advisory fields"))
    }

    @Test
    fun `blank relevant_standard field returns UNAVAILABLE safely`() {
        val blankStandardJson = """
            {
              "probable_root_cause": "Trace discontinuity caused by etching void.",
              "recommended_operator_action": "Inspect with DMM.",
              "relevant_standard": "   ",
              "limitations": "Optical analysis only."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = blankStandardJson,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 200L,
            provenanceSummary = "ExecuTorch"
        )

        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
        assertTrue(advisory.unavailableReason!!.contains("omitted required advisory fields"))
    }

    @Test
    fun `invalid JSON or missing required fields returns UNAVAILABLE safely`() {
        val malformed = AdvisoryOutputParser.parseAndValidate(
            rawText = "Not json at all",
            officialDisposition = InspectionDisposition.REVIEW,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 100L,
            provenanceSummary = "ExecuTorch"
        )
        assertEquals(AdvisoryAvailability.UNAVAILABLE, malformed.availability)

        val missingAction = AdvisoryOutputParser.parseAndValidate(
            rawText = """{"probable_root_cause": "Short", "recommended_operator_action": "", "relevant_standard": "IPC", "limitations": "None"}""",
            officialDisposition = InspectionDisposition.REVIEW,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 100L,
            provenanceSummary = "ExecuTorch"
        )
        assertEquals(AdvisoryAvailability.UNAVAILABLE, missingAction.availability)
    }

    @Test
    fun `certification claims are rejected safely`() {
        val raw = """
            {
              "probable_root_cause": "Board is certified compliant with all classes.",
              "recommended_operator_action": "Ship immediately.",
              "relevant_standard": "None",
              "limitations": "None"
            }
        """.trimIndent()

        val result = AdvisoryOutputParser.parseAndValidate(
            rawText = raw,
            officialDisposition = InspectionDisposition.PASS,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 50L,
            provenanceSummary = "ExecuTorch"
        )
        assertEquals(AdvisoryAvailability.UNAVAILABLE, result.availability)
    }

    @Test
    fun `ChatML EOS tag and markdown code fences are stripped cleanly`() {
        val raw = """
            ```json
            {
              "probable_root_cause": "Copper spur on trace edge.",
              "recommended_operator_action": "Trim protrusion per rework SOP.",
              "relevant_standard": "Standard identified: IPC-6012. Specific standard or clause not verified.",
              "limitations": "Local defect analysis only; requires optical and electrical check."
            }
            ```
            <|im_end|>
        """.trimIndent()

        val result = AdvisoryOutputParser.parseAndValidate(
            rawText = raw,
            officialDisposition = InspectionDisposition.REVIEW,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 250L,
            provenanceSummary = "ExecuTorch 1.6.0"
        )
        assertEquals(AdvisoryAvailability.AVAILABLE, result.availability)
        assertEquals("Copper spur on trace edge.", result.probableRootCause)
        assertEquals("Trim protrusion per rework SOP.", result.recommendedOperatorAction)
        assertEquals("Standard identified: IPC-6012. Specific standard or clause not verified.", result.relevantStandard)
        assertEquals("Local defect analysis only; requires optical and electrical check.", result.limitations)
    }
}
