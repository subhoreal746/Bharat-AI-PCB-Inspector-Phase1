package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GenerationBudgetingTest {

    @Test
    fun `417-token prompt resolves to 384 new tokens and 801 total sequence length`() {
        val budget = GenerationBudgeter.calculate(
            promptTokens = 417,
            maxContextLen = 2048,
            maxNewTokens = 384
        )

        assertTrue(budget.isExecutable)
        assertEquals(417, budget.promptTokens)
        assertEquals(384, budget.newTokensBudget)
        assertEquals(801, budget.totalSeqLen)
        assertNull(budget.rejectionReason)

        val nativeResolved = minOf(budget.totalSeqLen, 2048) - 417
        assertEquals(384, nativeResolved)
        assertTrue("Native resolution must be strictly positive", nativeResolved > 0)
    }

    @Test
    fun `consecutive independent inspections calculate budget from fresh prompt token baseline`() {
        // Inspection 1
        val budget1 = GenerationBudgeter.calculate(
            promptTokens = 417,
            maxContextLen = 2048,
            maxNewTokens = 384
        )
        assertTrue(budget1.isExecutable)
        assertEquals(801, budget1.totalSeqLen)
        assertEquals(384, budget1.newTokensBudget)

        // Inspection 2 (independent request after context reset)
        val budget2 = GenerationBudgeter.calculate(
            promptTokens = 351,
            maxContextLen = 2048,
            maxNewTokens = 384
        )
        assertTrue(budget2.isExecutable)
        assertEquals(735, budget2.totalSeqLen)
        assertEquals(384, budget2.newTokensBudget)

        // Verify native resolution for both assuming pos_ was cleanly reset to 0 before prefill
        val nativeResolved1 = minOf(budget1.totalSeqLen, 2048) - 417
        val nativeResolved2 = minOf(budget2.totalSeqLen, 2048) - 351
        assertEquals(384, nativeResolved1)
        assertEquals(384, nativeResolved2)
    }

    @Test
    fun `near-limit 2000-token prompt resolves to remaining 48 new tokens clamped at 2048`() {
        val budget = GenerationBudgeter.calculate(
            promptTokens = 2000,
            maxContextLen = 2048,
            maxNewTokens = 384
        )

        assertTrue(budget.isExecutable)
        assertEquals(2000, budget.promptTokens)
        assertEquals(48, budget.newTokensBudget)
        assertEquals(2048, budget.totalSeqLen)
        assertNull(budget.rejectionReason)

        val nativeResolved = minOf(budget.totalSeqLen, 2048) - 2000
        assertEquals(48, nativeResolved)
        assertTrue(nativeResolved > 0)
    }

    @Test
    fun `2048-token prompt reaches context limit and is safely rejected`() {
        val budget = GenerationBudgeter.calculate(
            promptTokens = 2048,
            maxContextLen = 2048,
            maxNewTokens = 384
        )

        assertFalse(budget.isExecutable)
        assertEquals(0, budget.newTokensBudget)
        assertNotNull(budget.rejectionReason)
        assertTrue(budget.rejectionReason!!.contains("exceeds maximum model context capacity"))
    }

    @Test
    fun `2200-token prompt exceeds context limit and is safely rejected`() {
        val budget = GenerationBudgeter.calculate(
            promptTokens = 2200,
            maxContextLen = 2048,
            maxNewTokens = 384
        )

        assertFalse(budget.isExecutable)
        assertEquals(0, budget.newTokensBudget)
        assertNotNull(budget.rejectionReason)
    }

    @Test
    fun `zero and negative token counts are rejected`() {
        val zeroBudget = GenerationBudgeter.calculate(promptTokens = 0)
        assertFalse(zeroBudget.isExecutable)

        val negBudget = GenerationBudgeter.calculate(promptTokens = -10)
        assertFalse(negBudget.isExecutable)
    }

    @Test
    fun `memory profiler returns non-negative process RSS`() {
        val rssBytes = MemoryProfiler.getProcessRssBytes()
        val rssMb = MemoryProfiler.getProcessRssMb()
        assertTrue("RSS bytes must be non-negative", rssBytes >= 0L)
        assertTrue("RSS MB must be non-negative", rssMb >= 0.0)
    }

    @Test
    fun `tokenizer artifact loads and accurately encodes prompt with ChatML special tokens`() {
        val tokenizerFile = File("/home/celric/Documents/Bharat AI SoC/android_handoff_bundle/models/smollm2_360m/tokenizer.json")
        if (!tokenizerFile.exists()) return

        val tokenizer = SmolLm2Tokenizer.load(tokenizerFile)
        val samplePrompt = AdvisoryPromptBuilder.buildGroundedPrompt(
            AdvisoryRequest(
                inspectionId = "PCB-TEST-001",
                boardName = "Test Power Board",
                officialDisposition = InspectionDisposition.REJECT,
                policyRationale = "Critical short-circuit defect detected",
                defects = listOf(
                    DetectedDefect(
                        defectId = "d1",
                        classIndex = 0,
                        rawClassName = "short",
                        canonicalClassName = "Short Circuit",
                        confidence = 0.96f,
                        severity = DefectSeverity.CRITICAL,
                        requiresManualReview = false,
                        boundingBox = null
                    )
                ),
                imageWidth = 1920,
                imageHeight = 1080,
                lowClarityRisk = false,
                isDemonstration = false
            )
        )

        val tokens = tokenizer.encode(samplePrompt)
        assertTrue("Token count should be positive", tokens.isNotEmpty())
        val count = tokenizer.countTokens(samplePrompt)
        assertEquals(tokens.size, count)

        val budget = GenerationBudgeter.calculate(count)
        assertTrue(budget.isExecutable)
        assertTrue(budget.newTokensBudget > 0)
        assertEquals(count + budget.newTokensBudget, budget.totalSeqLen)
    }

    @Test
    fun `prompt builder for zero defects contains mandatory non-fabrication rule`() {
        val zeroDefectPrompt = AdvisoryPromptBuilder.buildGroundedPrompt(
            AdvisoryRequest(
                inspectionId = "PCB-CLEAN-001",
                boardName = "Clean Board",
                officialDisposition = InspectionDisposition.PASS,
                policyRationale = "Zero anomalies detected",
                defects = emptyList(),
                imageWidth = 1920,
                imageHeight = 1080,
                lowClarityRisk = false,
                isDemonstration = false
            )
        )

        assertTrue(zeroDefectPrompt.contains(AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE))
        assertTrue(zeroDefectPrompt.contains("0 defects were detected"))
    }

    @Test
    fun `PASS with zero defects produces exact required root cause and preserves PASS disposition`() {
        val passJson = """
            {
              "probable_root_cause": "${AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE}",
              "recommended_operator_action": "Route unit to next assembly stage.",
              "relevant_standard": "None verified",
              "limitations": "Advisory is informational only."
            }
        """.trimIndent()

        val passAdvisory = AdvisoryOutputParser.parseAndValidate(
            rawText = passJson,
            officialDisposition = InspectionDisposition.PASS,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 120L,
            provenanceSummary = "ExecuTorch 1.6.0 • test",
            hasZeroDefects = true
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, passAdvisory.availability)
        assertEquals(InspectionDisposition.PASS.name, passAdvisory.severityClassification)
        assertEquals(AdvisoryPromptBuilder.ZERO_DEFECT_ROOT_CAUSE, passAdvisory.probableRootCause)
    }

    @Test
    fun `PASS with fabricated defects is rejected as ungrounded`() {
        val fabricatedJson = """
            {
              "probable_root_cause": "Short circuit bridging detected on trace pad.",
              "recommended_operator_action": "Rework short circuit.",
              "relevant_standard": "None verified",
              "limitations": "Advisory only."
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = fabricatedJson,
            officialDisposition = InspectionDisposition.PASS,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 120L,
            provenanceSummary = "ExecuTorch 1.6.0 • test",
            hasZeroDefects = true
        )

        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
        assertTrue(advisory.unavailableReason!!.contains("fabricated defects on a clean board"))
    }

    @Test
    fun `REJECT with actual defects parses valid advisory and preserves REJECT disposition`() {
        val rejectJson = """
            {
              "probable_root_cause": "Excess solder paste volume caused thermal bridging.",
              "recommended_operator_action": "Isolate unit for rework using fine wick.",
              "relevant_standard": "None verified",
              "limitations": "Informational only."
            }
        """.trimIndent()

        val rejectAdvisory = AdvisoryOutputParser.parseAndValidate(
            rawText = rejectJson,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 150L,
            provenanceSummary = "ExecuTorch 1.6.0 • test",
            hasZeroDefects = false
        )

        assertEquals(AdvisoryAvailability.AVAILABLE, rejectAdvisory.availability)
        assertEquals(InspectionDisposition.REJECT.name, rejectAdvisory.severityClassification)
        assertEquals(InspectionDisposition.REJECT, rejectAdvisory.suggestedDisposition)
    }

    @Test
    fun `truncated JSON missing closing brace returns UNAVAILABLE safely`() {
        val truncatedJson = """
            {
              "probable_root_cause": "Excess solder paste volume caused thermal bridging.",
              "recommended_operator_action": "Isolate unit for rework.",
              "relevant_standard": "None verified",
              "limitations": "Advisory only and based on 2D optical detections. It does not replace full automated
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = truncatedJson,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 2500L,
            provenanceSummary = "ExecuTorch 1.6.0 • test"
        )

        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
        assertTrue(advisory.unavailableReason!!.contains("did not contain a valid JSON object"))
    }

    @Test
    fun `JSON with unpopulated template placeholders is rejected safely`() {
        val placeholderJson = """
            {
              "probable_root_cause": "<1 concise sentence, max 25 words>",
              "recommended_operator_action": "Perform rework.",
              "relevant_standard": "None verified",
              "limitations": "<explicit statement of confidence/advisory limits>"
            }
        """.trimIndent()

        val advisory = AdvisoryOutputParser.parseAndValidate(
            rawText = placeholderJson,
            officialDisposition = InspectionDisposition.REJECT,
            hasVerifiedReferences = false,
            isDemonstration = false,
            latencyMs = 500L,
            provenanceSummary = "ExecuTorch 1.6.0 • test"
        )

        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
        assertTrue(advisory.unavailableReason!!.contains("unpopulated placeholder templates"))
    }
}
