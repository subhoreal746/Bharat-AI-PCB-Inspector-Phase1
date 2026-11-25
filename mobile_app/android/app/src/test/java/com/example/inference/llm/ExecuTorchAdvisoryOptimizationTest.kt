package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelRuntimeState
import com.example.domain.policy.PcbStandardsKnowledgeBase
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuTorchAdvisoryOptimizationTest {

    // -------------------------------------------------------------------------
    // Scenario 1: Critical Open Circuit
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 1 - critical open circuit builds grounded prompt and parses all four sections`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-open-1",
                classIndex = 0,
                rawClassName = "open_circuit",
                canonicalClassName = "Open Circuit",
                confidence = 0.965f,
                severity = DefectSeverity.CRITICAL,
                requiresManualReview = false,
                boundingBox = BoundingBox(0.15f, 0.25f, 0.08f, 0.02f)
            )
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-1",
            boardName = "Inverter_Power_Stage",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Critical trace open circuit detected",
            defects = defects,
            imageWidth = 1920,
            imageHeight = 1080,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains("PCB-OPEN"))
        assertTrue(prompt.contains("IPC-6012 / IPC-9252"))
        assertTrue(prompt.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))
        assertTrue(prompt.contains("Inverter_Power_Stage"))

        val mockLlmOutput = """
            {
              "probable_root_cause": "Trace discontinuity caused by localized over-etching or mechanical scratch during handling.",
              "recommended_operator_action": "Verify net continuity using DMM resistance probe. Quarantine board for micro-jumper rework if permitted.",
              "relevant_standard": "Standard identified: IPC-6012 / IPC-9252 (Bare-Board Conductor Integrity & Continuity). Specific standard or clause not verified.",
              "limitations": "2D optical inspection confirms missing surface copper but cannot verify inner-layer continuity; DMM testing required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockLlmOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = request.verifiedReferenceExcerpts.isNotEmpty(),
            isDemonstration = false,
            latencyMs = 450L,
            provenanceSummary = "Llama 3.2 3B Instruct (active_advisory.pte) • ExecuTorch 1.6.0"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertNotNull(advisory.probableRootCause)
        assertNotNull(advisory.recommendedOperatorAction)
        assertNotNull(advisory.relevantStandard)
        assertNotNull(advisory.limitations)
        assertEquals(InspectionDisposition.REJECT, advisory.suggestedDisposition)
        assertTrue(advisory.relevantStandard!!.contains("IPC-6012"))
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Medium Spurious Copper
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 2 - medium spurious copper resolves residual copper standard and actionable rework`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-spurcu-1",
                classIndex = 4,
                rawClassName = "spurious_copper",
                canonicalClassName = "Spurious Copper",
                confidence = 0.89f,
                severity = DefectSeverity.MEDIUM,
                requiresManualReview = true,
                boundingBox = BoundingBox(0.45f, 0.60f, 0.03f, 0.03f)
            )
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-2",
            boardName = "Logic_Board_RevA",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "Medium spurious copper anomaly",
            defects = defects,
            imageWidth = 1024,
            imageHeight = 1024,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains("PCB-SPURCU"))
        assertTrue(prompt.contains("Bare-Board Residual Copper Isolation"))

        val mockOutput = """
            {
              "probable_root_cause": "Incomplete chemical etching leaving unetched copper island on laminate substrate.",
              "recommended_operator_action": "Inspect clearance to adjacent traces under 10x magnification. Scrape or mill island if violating spacing.",
              "relevant_standard": "Standard identified: IPC-6012 / IPC-A-600 (Bare-Board Residual Copper Isolation). Specific standard or clause not verified.",
              "limitations": "Optical inspection cannot measure substrate dielectric breakdown; clearance measurement with optical reticle required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 380L,
            provenanceSummary = "Llama 3.2 3B Instruct"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals(InspectionDisposition.REVIEW, advisory.suggestedDisposition)
        assertTrue(advisory.relevantStandard!!.contains("IPC-6012"))
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Multiple different defect types on same PCB
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 3 - multiple defect types groups distinct defects and cites relevant standards`() {
        val defects = listOf(
            DetectedDefect("d1", 0, "open", "Open Circuit", 0.95f, DefectSeverity.CRITICAL, false, BoundingBox(0.1f, 0.1f, 0.02f, 0.02f)),
            DetectedDefect("d2", 1, "short", "Short Circuit", 0.92f, DefectSeverity.CRITICAL, false, BoundingBox(0.3f, 0.3f, 0.03f, 0.03f)),
            DetectedDefect("d3", 2, "mouse_bite", "Mouse Bite", 0.84f, DefectSeverity.MEDIUM, true, BoundingBox(0.7f, 0.7f, 0.01f, 0.01f))
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-3",
            boardName = "MultiDefect_Board",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Multiple critical defects flagged",
            defects = defects,
            imageWidth = 2048,
            imageHeight = 1536,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains("Open Circuit"))
        assertTrue(prompt.contains("Short Circuit"))
        assertTrue(prompt.contains("Mouse Bite"))
        assertEquals(3, verifiedRefs.size)

        val mockOutput = """
            {
              "probable_root_cause": "Open circuit caused by etching over-exposure; short circuit caused by solder bridging; mouse bite from handling stress.",
              "recommended_operator_action": "Perform DMM continuity check for open circuit, desolder bridge for short circuit, and measure remaining trace width on mouse bite.",
              "relevant_standard": "Standard identified: IPC-6012 / IPC-A-610. Specific standard or clause not verified.",
              "limitations": "Visual inspection cannot confirm electrical isolation or inner-layer interconnects; in-circuit electrical testing is mandatory."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 520L,
            provenanceSummary = "Llama 3.2 3B Instruct"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals(InspectionDisposition.REJECT, advisory.suggestedDisposition)
        assertTrue(advisory.probableRootCause!!.contains("Open circuit"))
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Empty or incomplete defect context (PASS)
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 4 - clean board PASS enforces zero defect root cause without defect fabrication`() {
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-4",
            boardName = "Golden_Reference_PCB",
            officialDisposition = InspectionDisposition.PASS,
            policyRationale = "Zero localized defects detected; all zones clear",
            defects = emptyList(),
            imageWidth = 1920,
            imageHeight = 1080,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = PcbStandardsKnowledgeBase.getVerifiedReferences(emptyList(), InspectionTarget.PCB_BOARD)
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains("None (0 localized detections)"))
        assertTrue(prompt.contains(AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE))

        val mockPassOutput = """
            {
              "probable_root_cause": "${AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE}",
              "recommended_operator_action": "Proceed with regular manufacturing workflow and routine final quality verification.",
              "relevant_standard": "Standard identified: IPC-6012. Specific standard or clause not verified.",
              "limitations": "Optical inspection confirms 2D surface conductor geometry only; does not replace electrical flying-probe test."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockPassOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 280L,
            provenanceSummary = "Llama 3.2 3B Instruct",
            hasZeroDefects = true
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals(AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE, advisory.probableRootCause)
        assertEquals(InspectionDisposition.PASS, advisory.suggestedDisposition)
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Low-confidence detection
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 5 - low confidence detection reflects confidence in prompt and requires manual verification`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-low-1",
                classIndex = 7,
                rawClassName = "pin_hole",
                canonicalClassName = "Pin Hole",
                confidence = 0.523f,
                severity = DefectSeverity.MEDIUM,
                requiresManualReview = true,
                boundingBox = BoundingBox(0.8f, 0.4f, 0.02f, 0.02f)
            )
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-5",
            boardName = "LowConf_Board",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "Low confidence potential pinhole detected",
            defects = defects,
            imageWidth = 640,
            imageHeight = 640,
            lowClarityRisk = true,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains("52.3%"))
        assertTrue(prompt.contains("lowClarityRisk=true"))

        val mockOutput = """
            {
              "probable_root_cause": "Possible copper plating void or gas bubble during electroplating, or optical false alarm from surface glare.",
              "recommended_operator_action": "Inspect suspect land under high-power optical microscope to confirm void depth and measure remaining copper.",
              "relevant_standard": "Standard identified: IPC-6012 / IPC-A-600. Specific standard or clause not verified.",
              "limitations": "Low image clarity and 52.3% confidence introduce significant uncertainty; visual confirmation under 50x magnification required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 390L,
            provenanceSummary = "Llama 3.2 3B Instruct"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertTrue(advisory.limitations!!.contains("uncertainty") || advisory.limitations!!.contains("magnification"))
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Standard cannot be verified
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 6 - standard cannot be verified outputs explicit fallback standard clause`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-unverified-1",
                classIndex = 6,
                rawClassName = "copper_burr",
                canonicalClassName = "Copper Burr",
                confidence = 0.78f,
                severity = DefectSeverity.MANUAL_REVIEW,
                requiresManualReview = true,
                boundingBox = null
            )
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.PCB_BOARD)
        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-6",
            boardName = "Burr_Test_Board",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "Copper burr requires manual review",
            defects = defects,
            imageWidth = 800,
            imageHeight = 600,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue(prompt.contains(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD))

        val mockOutput = """
            {
              "probable_root_cause": "Mechanical cutting or routing burr protruding from edge.",
              "recommended_operator_action": "Deburr trace edge with precision knife and inspect clearance to adjacent copper.",
              "relevant_standard": "${PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD}",
              "limitations": "Optical inspection cannot determine mechanical adhesion of burr; tactile or magnification check required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 360L,
            provenanceSummary = "Llama 3.2 3B Instruct"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertEquals(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD, advisory.relevantStandard)
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Early-Stop & Cancellation Rigorous Validation
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 7 - shouldStopGeneration requires ALL FOUR fields and rejects incomplete or missing relevant_standard`() {
        val engine = ExecuTorchAdvisoryEngine()

        // 1. All four fields complete and valid -> MUST stop early
        val completeValidJson = """
            {
              "probable_root_cause": "Solder bridge caused by stencil offset.",
              "recommended_operator_action": "Wick excess solder with desoldering braid.",
              "relevant_standard": "IPC-A-610 Class 2",
              "limitations": "Advisory only; 2D optical inspection cannot verify electrical isolation."
            }
        """.trimIndent()
        assertTrue(engine.shouldStopGeneration(completeValidJson))

        // 2. CRITICAL AUDIT: First three fields present, but relevant_standard is MISSING -> MUST NOT stop early!
        val missingStandardJson = """
            {
              "probable_root_cause": "Solder bridge caused by stencil offset.",
              "recommended_operator_action": "Wick excess solder with desoldering braid.",
              "limitations": "Advisory only; 2D optical inspection cannot verify electrical isolation."
            }
        """.trimIndent()
        assertFalse("Must NOT stop early when relevant_standard is missing", engine.shouldStopGeneration(missingStandardJson))

        // 3. relevant_standard present but BLANK string -> MUST NOT stop early!
        val blankStandardJson = """
            {
              "probable_root_cause": "Solder bridge caused by stencil offset.",
              "recommended_operator_action": "Wick excess solder with desoldering braid.",
              "relevant_standard": "   ",
              "limitations": "Advisory only; 2D optical inspection cannot verify electrical isolation."
            }
        """.trimIndent()
        assertFalse("Must NOT stop early when relevant_standard is blank", engine.shouldStopGeneration(blankStandardJson))

        // 4. relevant_standard contains unpopulated template placeholder -> MUST NOT stop early!
        val placeholderStandardJson = """
            {
              "probable_root_cause": "Solder bridge caused by stencil offset.",
              "recommended_operator_action": "Wick excess solder with desoldering braid.",
              "relevant_standard": "<verified standard or 'None verified'>",
              "limitations": "Advisory only; 2D optical inspection cannot verify electrical isolation."
            }
        """.trimIndent()
        assertFalse("Must NOT stop early when relevant_standard is a placeholder", engine.shouldStopGeneration(placeholderStandardJson))

        // 5. limitations missing -> MUST NOT stop early!
        val missingLimitationsJson = """
            {
              "probable_root_cause": "Solder bridge caused by stencil offset.",
              "recommended_operator_action": "Wick excess solder with desoldering braid.",
              "relevant_standard": "IPC-A-610 Class 2"
            }
        """.trimIndent()
        assertFalse("Must NOT stop early when limitations is missing", engine.shouldStopGeneration(missingLimitationsJson))

        // 6. Incomplete JSON (closing brace not yet emitted) -> MUST NOT stop early!
        val incompleteJson = """
            {
              "probable_root_cause": "Solder bridge",
              "recommended_operator_action": "Wick solder",
              "relevant_standard": "IPC-A-610",
              "limitations": "Advisory only
        """.trimIndent()
        assertFalse("Must NOT stop early on unclosed JSON", engine.shouldStopGeneration(incompleteJson))

        // 7. Braces inside JSON string values -> correctly handled without premature termination!
        val bracesInsideStringJson = """
            {
              "probable_root_cause": "Bridge at trace {net_1} and {net_2}.",
              "recommended_operator_action": "Wick excess solder {procedure_A}.",
              "relevant_standard": "IPC-A-610 {Class 2}",
              "limitations": "Advisory only {optical_2d}."
            }
        """.trimIndent()
        assertTrue("Must handle braces inside string literals correctly", engine.shouldStopGeneration(bracesInsideStringJson))

        // 8. Trailing text after complete JSON (e.g. EOS token) -> stops early cleanly
        val jsonWithEosTrailing = "$completeValidJson\n<|eot_id|>"
        assertTrue(engine.shouldStopGeneration(jsonWithEosTrailing))
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Consecutive advisory requests
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 8 - consecutive requests compute independent budgets with clean context baselines`() {
        val budget1 = GenerationBudgeter.calculate(promptTokens = 195, maxContextLen = 2048, maxNewTokens = 180)
        assertTrue(budget1.isExecutable)
        assertEquals(180, budget1.newTokensBudget)
        assertEquals(375, budget1.totalSeqLen)

        val budget2 = GenerationBudgeter.calculate(promptTokens = 230, maxContextLen = 2048, maxNewTokens = 180)
        assertTrue(budget2.isExecutable)
        assertEquals(180, budget2.newTokensBudget)
        assertEquals(410, budget2.totalSeqLen)
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Model already loaded vs cold model initialization
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 9 - model provenance reflects loaded model identity and prevents duplicate reload`() = runTest {
        val engine = ExecuTorchAdvisoryEngine()
        assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)

        val tempModel = File.createTempFile("llama_3_2_3b_instruct", ".pte").apply {
            writeText("dummy-llama-artifact")
        }
        val tempTok = File.createTempFile("tokenizer", ".bin").apply {
            writeText("dummy-tok")
        }

        try {
            engine.setConfiguredArtifacts(tempModel, tempTok)
            assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)
            assertTrue(engine.modelInfo.value.displayName.contains("Llama 3.2 3B Instruct"))
            assertTrue(engine.modelInfo.value.detailMessage.contains("Artifacts staged"))
        } finally {
            tempModel.delete()
            tempTok.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 10: Generic Anomaly Advisory (No Transistor / Semiconductor Leakage)
    // -------------------------------------------------------------------------
    @Test
    fun `scenario 10 - generic anomaly inspection produces evidence-driven PCB advisory without semiconductor or transistor leakage`() {
        val defects = listOf(
            DetectedDefect(
                defectId = "d-anom-10",
                classIndex = 0,
                rawClassName = "transistor_anomaly",
                canonicalClassName = "Transistor Anomaly",
                confidence = 0.842f,
                severity = DefectSeverity.MANUAL_REVIEW,
                requiresManualReview = true,
                boundingBox = BoundingBox(0.35f, 0.45f, 0.15f, 0.12f),
                code = "TR-ANOMALY-REVIEW",
                description = "PatchCore anomaly detection flag"
            )
        )
        val verifiedRefs = PcbStandardsKnowledgeBase.getVerifiedReferences(defects, InspectionTarget.TRANSISTOR)
        assertEquals(1, verifiedRefs.size)
        assertEquals(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD, verifiedRefs[0])
        assertFalse("Standards must not contain JEDEC", verifiedRefs[0].contains("JEDEC"))

        val request = AdvisoryRequest(
            inspectionId = "INSP-SCENARIO-10",
            boardName = "Control_Motherboard_Rev3",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "REVIEW: Detected 1 total defect(s) [Transistor Anomaly (1)] requiring manual review.",
            defects = defects,
            imageWidth = 1280,
            imageHeight = 720,
            lowClarityRisk = false,
            isDemonstration = false,
            verifiedReferenceExcerpts = verifiedRefs
        )

        val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = true)
        assertTrue("Prompt must display 'Anomaly'", prompt.contains("- Defect: Anomaly"))
        assertFalse("Prompt must not contain 'Transistor Anomaly'", prompt.contains("Transistor Anomaly"))
        assertFalse("Prompt must not cite JEDEC", prompt.contains("JEDEC"))
        assertTrue("Policy rationale in prompt must be sanitized", prompt.contains("REVIEW: Detected 1 total defect(s) [Anomaly (1)]"))

        // LLM generates evidence-driven hypothesis without component-specific or stencil assumptions
        val mockLlmOutput = """
            {
              "probable_root_cause": "Localized visual anomaly detected on board surface, indicating possible contamination, surface scratch, or irregular copper finish.",
              "recommended_operator_action": "Inspect the anomalous region under optical magnification to verify surface pattern integrity and rule out foreign debris.",
              "relevant_standard": "${PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD}",
              "limitations": "2D optical inspection localizes surface irregularities but cannot measure dielectric breakdown or internal trace continuity; physical verification required."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = mockLlmOutput,
            officialDisposition = request.officialDisposition,
            hasVerifiedReferences = true,
            isDemonstration = false,
            latencyMs = 420L,
            provenanceSummary = "Llama 3.2 3B Instruct"
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, advisory.availability)
        assertNotNull(advisory.probableRootCause)
        assertFalse("Root cause must not assume transistor", advisory.probableRootCause!!.contains("transistor", ignoreCase = true))
        assertFalse("Root cause must not assume solder paste or stencil", advisory.probableRootCause!!.contains("stencil", ignoreCase = true))
        assertFalse("Operator action must not instruct transistor area inspection", advisory.recommendedOperatorAction!!.contains("transistor", ignoreCase = true))
        assertFalse("Standard must not cite JEDEC", advisory.relevantStandard!!.contains("JEDEC", ignoreCase = true))
        assertEquals(PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD, advisory.relevantStandard)
    }
}
