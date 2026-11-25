package com.example.inference.llm

import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.policy.PcbStandardsKnowledgeBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisoryPromptBuilderTest {

    @Test
    fun `prompt version is pcb-advisory-json-v2`() {
        assertEquals("pcb-advisory-json-v2", AdvisoryPromptBuilder.PROMPT_VERSION)
    }

    @Test
    fun `prompt uses ChatML formatting for default and SmolLM2`() {
        val request = AdvisoryRequest(
            inspectionId = "INSP-101",
            boardName = "TestBoard_Rev2",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Critical short circuit detected",
            defects = listOf(
                DetectedDefect(
                    defectId = "d1",
                    classIndex = 1,
                    canonicalClassName = "short",
                    rawClassName = "short_circuit",
                    confidence = 0.945f,
                    severity = DefectSeverity.CRITICAL,
                    requiresManualReview = false,
                    boundingBox = BoundingBox(0.12f, 0.34f, 0.05f, 0.08f)
                )
            ),
            imageWidth = 640,
            imageHeight = 640,
            lowClarityRisk = false,
            verifiedReferenceExcerpts = listOf("IPC-A-610 Section 10.1: Conductor clearance must exceed 0.1mm."),
            isDemonstration = false
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = false)

        // Verify ChatML tokens
        assertTrue(prompt.contains("<|im_start|>system\n"))
        assertTrue(prompt.contains("<|im_end|>\n<|im_start|>user\n"))
        assertTrue(prompt.contains("<|im_end|>\n<|im_start|>assistant\n"))

        // Verify content grounding
        assertTrue(prompt.contains("TestBoard_Rev2"))
        assertTrue(prompt.contains("Official Policy Disposition: REJECT"))
        assertTrue(prompt.contains("Critical short circuit detected"))
        assertTrue(prompt.contains("Short Circuit"))
        assertTrue(prompt.contains("PCB-SHORT"))
        assertTrue(prompt.contains("94.5%"))
        assertTrue(prompt.contains("IPC-A-610 Section 10.1"))

        // Verify strict JSON schema instruction
        assertTrue(prompt.contains("\"probable_root_cause\""))
        assertTrue(prompt.contains("\"recommended_operator_action\""))
        assertTrue(prompt.contains("\"relevant_standard\""))
        assertTrue(prompt.contains("\"limitations\""))
    }

    @Test
    fun `prompt uses Llama 3 formatting when isLlama is true`() {
        val request = AdvisoryRequest(
            inspectionId = "INSP-103",
            boardName = "LlamaBoard_Rev3",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Trace open circuit",
            defects = listOf(
                DetectedDefect(
                    defectId = "d2",
                    classIndex = 0,
                    canonicalClassName = "open",
                    rawClassName = "open_circuit",
                    confidence = 0.982f,
                    severity = DefectSeverity.CRITICAL,
                    requiresManualReview = false,
                    boundingBox = BoundingBox(0.2f, 0.5f, 0.04f, 0.06f)
                )
            ),
            imageWidth = 1920,
            imageHeight = 1080,
            lowClarityRisk = false,
            verifiedReferenceExcerpts = listOf("IPC-6012 Class 2 Section 3.5.1: Conductor continuity required."),
            isDemonstration = false
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)

        // Verify Llama 3 special headers
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|eot_id|><|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.contains("<|eot_id|><|start_header_id|>assistant<|end_header_id|>"))

        // Verify grounding
        assertTrue(prompt.contains("LlamaBoard_Rev3"))
        assertTrue(prompt.contains("Open Circuit"))
        assertTrue(prompt.contains("PCB-OPEN"))
        assertTrue(prompt.contains("98.2%"))
    }

    @Test
    fun `prompt handles empty defects and empty references gracefully`() {
        val request = AdvisoryRequest(
            inspectionId = "INSP-102",
            boardName = "CleanBoard",
            officialDisposition = InspectionDisposition.PASS,
            policyRationale = "No defects detected",
            defects = emptyList(),
            imageWidth = 1024,
            imageHeight = 768,
            lowClarityRisk = false,
            verifiedReferenceExcerpts = emptyList(),
            isDemonstration = false
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request)

        assertTrue(prompt.contains("None (0 localized detections)."))
        assertTrue(prompt.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
        assertTrue(prompt.contains(AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE))
    }

    @Test
    fun `generic anomaly defect groups as Anomaly with ANOMALY-REVIEW code and no transistor leakage`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-anom-1",
                classIndex = 0,
                rawClassName = "transistor_anomaly",
                canonicalClassName = "Transistor Anomaly",
                confidence = 0.88f,
                severity = DefectSeverity.MANUAL_REVIEW,
                requiresManualReview = true,
                boundingBox = BoundingBox(0.4f, 0.4f, 0.2f, 0.2f),
                code = "TR-ANOMALY-REVIEW",
                description = "PatchCore anomaly detection flag"
            )
        )
        val request = AdvisoryRequest(
            inspectionId = "INSP-ANOM-1",
            boardName = "PCB_Rev_B",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "REVIEW: Detected 1 total defect(s) [Transistor Anomaly (1)] requiring manual review.",
            defects = defects,
            imageWidth = 1024,
            imageHeight = 1024,
            lowClarityRisk = false,
            verifiedReferenceExcerpts = listOf(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD),
            isDemonstration = false
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)

        // 1. Grouped defect name must be "Anomaly" only
        assertTrue("Must use 'Anomaly' for generic anomaly defect", prompt.contains("- Defect: Anomaly"))
        assertTrue("Must use ANOMALY-REVIEW code", prompt.contains("code=ANOMALY-REVIEW"))

        // 2. Policy rationale must be sanitized
        assertFalse("Prompt must not leak 'Transistor Anomaly' in policy rationale", prompt.contains("Transistor Anomaly"))
        assertTrue("Prompt policy rationale should refer to Anomaly", prompt.contains("REVIEW: Detected 1 total defect(s) [Anomaly (1)]"))

        // 3. System prompt instructions must be evidence-grounded without ungrounded assembly/stencil assumptions
        assertFalse("Prompt must not prompt solder paste volume as default example", prompt.contains("e.g. solder paste volume"))
        assertFalse("Prompt must not prompt stencil offset as default example", prompt.contains("stencil offset,"))
        assertFalse("Prompt must not cite JEDEC semiconductor standards", prompt.contains("JEDEC"))

        // 4. Verification constraints present
        assertTrue("Prompt must include terminology constraint", prompt.contains("For generic anomalies, use the term \"Anomaly\" only"))
        assertTrue("Prompt must instruct not to cite semiconductor standards", prompt.contains("semiconductor-package standards"))
    }
}
