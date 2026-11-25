package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.ModelRuntimeState
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecuTorchAdvisoryTest {

    @Test
    fun `unloaded ExecuTorch engine reports NOT_LOADED and returns UNAVAILABLE advisory`() = runTest {
        val engine = ExecuTorchAdvisoryEngine()
        assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)

        val advisory = engine.generateAdvisory(
            AdvisoryRequest(
                inspectionId = "PCB-2001",
                boardName = "Main PCB",
                officialDisposition = InspectionDisposition.REVIEW,
                policyRationale = "Medium defect detected",
                defects = emptyList(),
                imageWidth = 1200,
                imageHeight = 900,
                lowClarityRisk = false,
                isDemonstration = false
            )
        )
        assertEquals(AdvisoryAvailability.UNAVAILABLE, advisory.availability)
    }

    @Test
    fun `engine label and initial model info reflect model-agnostic ExecuTorch advisory engine and 1_6_0`() {
        val engine = ExecuTorchAdvisoryEngine()
        assertEquals("ExecuTorch Advisory Engine", engine.engineLabel)
        assertEquals("ExecuTorch Advisory Model", engine.modelInfo.value.displayName)
        assertEquals("1.6.0", engine.modelInfo.value.runtimeVersion)
        assertEquals(ExecuTorchAdvisoryEngine.EXECUTORCH_RUNTIME_VERSION, "1.6.0")
        assertEquals(AdvisoryPromptBuilder.PROMPT_VERSION, engine.modelInfo.value.contractVersion)
        assertTrue(engine.inspectCurrentMetadataText().contains("Slot: AI Technical Advisory (ExecuTorch Advisory Model)"))
    }

    @Test
    fun `staging artifacts updates state and metadata without loading native module`() = runTest {
        val engine = ExecuTorchAdvisoryEngine()
        val tempModel = File.createTempFile("test_smollm2", ".pte").apply { writeText("dummy") }
        val tempTok = File.createTempFile("test_tokenizer", ".json").apply { writeText("{}") }

        try {
            engine.setConfiguredArtifacts(tempModel, tempTok)
            assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)
            assertTrue(engine.modelInfo.value.detailMessage.contains("Artifacts staged"))
            assertEquals(tempModel.absolutePath, engine.modelInfo.value.artifactPath)
            assertEquals(tempTok.absolutePath, engine.modelInfo.value.tokenizerPath)
        } finally {
            tempModel.delete()
            tempTok.delete()
        }
    }

    @Test
    fun `loadFromArtifacts with invalid extension returns failure safely`() = runTest {
        val engine = ExecuTorchAdvisoryEngine()
        val badModel = File.createTempFile("invalid_model", ".bin").apply { writeText("dummy") }
        val tempTok = File.createTempFile("test_tokenizer", ".json").apply { writeText("{}") }

        try {
            val result = engine.loadFromArtifacts(badModel, tempTok)
            assertEquals(false, result.success)
            assertTrue(result.message.contains("Expected a .pte"))
            assertEquals(ModelRuntimeState.ERROR, engine.modelInfo.value.state)
        } finally {
            badModel.delete()
            tempTok.delete()
        }
    }

    @Test
    fun `computeCharFrequencies handles quotes, newlines, percent signs, and unicode safely without throwing`() {
        val engine = ExecuTorchAdvisoryEngine()
        val problematicOutput = "\"\n\"\n\"\"\"\n\n100% compliant with %s and %d \"\""
        val frequencies = engine.computeCharFrequencies(problematicOutput)
        assertTrue(frequencies.contains("\\\" (quote)"))
        assertTrue(frequencies.contains("\\n (newline)"))
        assertTrue(frequencies.contains("U+0022"))
        assertTrue(frequencies.contains("U+000A"))
        assertTrue(frequencies.contains("%"))

        val emptyFreq = engine.computeCharFrequencies("")
        assertEquals("  (empty output buffer)", emptyFreq)

        val unicodePoints = engine.toUnicodeCodePoints("A\n\"")
        assertEquals("U+0041 U+000A U+0022", unicodePoints)
    }
}
