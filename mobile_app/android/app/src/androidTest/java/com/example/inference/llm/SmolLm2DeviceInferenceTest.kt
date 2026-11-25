package com.example.inference.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.ModelRuntimeState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Physical on-device instrumented test harness for SmolLM2 360M inference on Motorola Moto G54 5G.
 *
 * VALIDATION STATUS: DEVICE_PENDING
 * Per project security and verification guidelines, the AI agent executes no ADB commands.
 * All probes (A through E) and lifecycle checks are staged for user execution on physical hardware.
 * None of the tests below use mocks; all execute against real ExecuTorch 1.6.0 native binaries.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmolLm2DeviceInferenceTest {

    companion object {
        private var sharedEngine: ExecuTorchAdvisoryEngine? = null
    }

    private fun getModelAndTokenizerFiles(): Pair<File, File> {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val modelsDir = File(targetContext.filesDir, "models").apply { mkdirs() }
        val modelFile = File(modelsDir, "active_smollm2_advisory.pte")
        val tokFile = File(modelsDir, "active_tokenizer.json")

        if (!modelFile.exists() || modelFile.length() == 0L) {
            val fallbackModel = File("/data/local/tmp/smollm2_360m/model.pte")
            if (fallbackModel.exists()) {
                fallbackModel.copyTo(modelFile, overwrite = true)
            }
        }
        if (!tokFile.exists() || tokFile.length() == 0L) {
            val fallbackTok = File("/data/local/tmp/smollm2_360m/tokenizer.json")
            if (fallbackTok.exists()) {
                fallbackTok.copyTo(tokFile, overwrite = true)
            }
        }

        // Auxiliary files required by native hf_tokenizer discovery
        val specialTokensFile = File(modelsDir, "special_tokens_map.json")
        val tokenizerConfigFile = File(modelsDir, "tokenizer_config.json")
        val fallbackSpecialTokens = File("/data/local/tmp/smollm2_360m/special_tokens_map.json")
        if (fallbackSpecialTokens.exists() && (!specialTokensFile.exists() || specialTokensFile.length() == 0L)) {
            fallbackSpecialTokens.copyTo(specialTokensFile, overwrite = true)
        }
        val fallbackTokenizerConfig = File("/data/local/tmp/smollm2_360m/tokenizer_config.json")
        if (fallbackTokenizerConfig.exists() && (!tokenizerConfigFile.exists() || tokenizerConfigFile.length() == 0L)) {
            fallbackTokenizerConfig.copyTo(tokenizerConfigFile, overwrite = true)
        }

        assertTrue("Model file must exist at ${modelFile.absolutePath}", modelFile.exists() && modelFile.length() > 0L)
        assertTrue("Tokenizer file must exist at ${tokFile.absolutePath}", tokFile.exists() && tokFile.length() > 0L)
        return Pair(modelFile, tokFile)
    }

    private suspend fun getOrInitEngine(): ExecuTorchAdvisoryEngine {
        val (modelFile, tokFile) = getModelAndTokenizerFiles()
        return sharedEngine ?: ExecuTorchAdvisoryEngine().also {
            it.debugOutputDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
            val res = it.loadFromArtifacts(modelFile, tokFile)
            assertTrue("Engine load failed: ${res.message}", res.success)
            sharedEngine = it
        }
    }

    @Test
    fun test1_loadSmolLm2OnDevice() = runBlocking {
        val engine = getOrInitEngine()
        assertEquals("Runtime state must be READY", ModelRuntimeState.READY, engine.modelInfo.value.state)
    }

    @Test
    fun test2a_probeA_plainText() = runBlocking {
        val engine = getOrInitEngine()
        val prompt = "<|im_start|>user\nReply with the word hello.<|im_end|>\n<|im_start|>assistant\n"
        val output = engine.generateRaw(prompt, maxNewTokens = 32)
        val callbacks = engine.latestCallbackCount ?: 0

        assertTrue("Probe A must produce callbacks (callbacks=$callbacks)", callbacks > 0)
        assertTrue("Probe A output must be non-empty", output.isNotBlank())
        assertFalse(
            "Probe A must not loop in quotes/newlines: [$output]",
            output.trim().all { it == '\"' || it == '\n' || it == ' ' || it == '\r' }
        )
    }

    @Test
    fun test2b_probeB_shortFactual() = runBlocking {
        val engine = getOrInitEngine()
        val prompt = "<|im_start|>user\nWhat is 2 + 2? Answer briefly.<|im_end|>\n<|im_start|>assistant\n"
        val output = engine.generateRaw(prompt, maxNewTokens = 32)
        val callbacks = engine.latestCallbackCount ?: 0

        assertTrue("Probe B must produce callbacks (callbacks=$callbacks)", callbacks > 0)
        assertTrue("Probe B output must be non-empty", output.isNotBlank())
        assertFalse(
            "Probe B must not loop in quotes/newlines: [$output]",
            output.trim().all { it == '\"' || it == '\n' || it == ' ' || it == '\r' }
        )
        assertTrue("Probe B should answer 4 or four: [$output]", output.contains("4") || output.contains("four", ignoreCase = true))
    }

    @Test
    fun test2c_probeC_jsonGeneration() = runBlocking {
        val engine = getOrInitEngine()
        val prompt = "<|im_start|>user\nReturn a JSON object with one key named result and value ok. Output only JSON.<|im_end|>\n<|im_start|>assistant\n"
        val output = engine.generateRaw(prompt, maxNewTokens = 64)
        val callbacks = engine.latestCallbackCount ?: 0

        assertTrue("Probe C must produce callbacks (callbacks=$callbacks)", callbacks > 0)
        assertTrue("Probe C output must be non-empty", output.isNotBlank())
    }

    @Test
    fun test2d_probeD_chatMlOneShot() = runBlocking {
        val engine = getOrInitEngine()
        val prompt = "<|im_start|>user\nReturn a JSON object with status.<|im_end|>\n" +
            "<|im_start|>assistant\n{\"status\": \"ok\"}<|im_end|>\n" +
            "<|im_start|>user\nReturn a JSON object with status.<|im_end|>\n" +
            "<|im_start|>assistant\n"
        val output = engine.generateRaw(prompt, maxNewTokens = 64)
        val callbacks = engine.latestCallbackCount ?: 0

        assertTrue("Probe D must produce callbacks (callbacks=$callbacks)", callbacks > 0)
        assertTrue("Probe D output must be non-empty", output.isNotBlank())
        assertFalse(
            "Probe D must not loop in quotes/newlines: [$output]",
            output.trim().all { it == '\"' || it == '\n' || it == ' ' || it == '\r' }
        )
        assertTrue("Probe D 1-shot should produce status JSON: [$output]", output.contains("\"status\""))
    }

    @Test
    fun test2e_probeE_productionAdvisoryWithDefect() = runBlocking {
        val engine = getOrInitEngine()
        val request = AdvisoryRequest(
            inspectionId = "device-test-reject-001",
            boardName = "FlightControl-RevC",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Critical trace bridge short detected between 3V3 rail and ground.",
            defects = listOf(
                DetectedDefect(
                    defectId = "d-short-01",
                    classIndex = 0,
                    rawClassName = "short",
                    canonicalClassName = "Short Circuit",
                    confidence = 0.94f,
                    severity = DefectSeverity.CRITICAL,
                    requiresManualReview = true,
                    boundingBox = BoundingBox(0.2f, 0.3f, 0.1f, 0.1f)
                )
            ),
            imageWidth = 640,
            imageHeight = 640,
            lowClarityRisk = false,
            isDemonstration = false
        )

        val advisory = engine.generateAdvisory(request)
        if (advisory.availability != AdvisoryAvailability.AVAILABLE) {
            org.junit.Assert.fail("Advisory unavailable: ${advisory.unavailableReason}\nRAW OUTPUT: [${engine.latestRawOutput}]\nCALLBACKS: ${engine.latestCallbackCount}")
        }
        assertFalse("Must not be demonstration mode", advisory.isDemonstration)

        val rootCause = checkNotNull(advisory.probableRootCause) { "Root cause must not be null" }
        val action = checkNotNull(advisory.recommendedOperatorAction) { "Operator action must not be null" }
        val limitations = checkNotNull(advisory.limitations) { "Limitations must not be null" }

        assertTrue("Root cause must be non-blank", rootCause.isNotBlank())
        assertTrue("Operator action must be non-blank", action.isNotBlank())
        assertTrue("Limitations must be non-blank", limitations.isNotBlank())
        assertEquals("Suggested disposition must align with policy", InspectionDisposition.REJECT, advisory.suggestedDisposition)
        assertTrue("Latency must be positive", (advisory.advisoryLatencyMs ?: 0L) > 0L)
        assertNotNull("Provenance summary must be populated", advisory.provenanceSummary)
    }

    @Test
    fun test3_secondGeneration_zeroDefects_noAccumulatedKvCache_returnsValidJson() = runBlocking {
        val engine = getOrInitEngine()
        val request = AdvisoryRequest(
            inspectionId = "device-test-pass-002",
            boardName = "FlightControl-RevC-Clean",
            officialDisposition = InspectionDisposition.PASS,
            policyRationale = "All visual inspection zones clear. No defect anomalies detected.",
            defects = emptyList(),
            imageWidth = 640,
            imageHeight = 640,
            lowClarityRisk = false,
            isDemonstration = false
        )

        val advisory = engine.generateAdvisory(request)
        if (advisory.availability != AdvisoryAvailability.AVAILABLE) {
            org.junit.Assert.fail("Advisory unavailable: ${advisory.unavailableReason}\nRAW OUTPUT: [${engine.latestRawOutput}]\nCALLBACKS: ${engine.latestCallbackCount}")
        }

        val rootCause = checkNotNull(advisory.probableRootCause) { "Root cause must not be null" }
        assertEquals(
            "Zero defect root cause must exactly match required phrasing",
            "No localized defects detected; no defect-specific root cause can be established.",
            rootCause
        )
        assertFalse("Must not contain fabricated defect claims", rootCause.contains("short", ignoreCase = true))
        assertEquals("Suggested disposition must align with policy", InspectionDisposition.PASS, advisory.suggestedDisposition)
    }

    @Test
    fun test4_thirdGeneration_consecutiveRun_stabilityCheck() = runBlocking {
        val engine = getOrInitEngine()
        val request = AdvisoryRequest(
            inspectionId = "device-test-reject-003",
            boardName = "FlightControl-RevC-Mousebite",
            officialDisposition = InspectionDisposition.REJECT,
            policyRationale = "Border mousebite defect observed on substrate edge.",
            defects = listOf(
                DetectedDefect(
                    defectId = "d-mb-01",
                    classIndex = 1,
                    rawClassName = "mousebite",
                    canonicalClassName = "Mousebite Defect",
                    confidence = 0.88f,
                    severity = DefectSeverity.CRITICAL,
                    requiresManualReview = true,
                    boundingBox = BoundingBox(0.01f, 0.4f, 0.05f, 0.05f)
                )
            ),
            imageWidth = 640,
            imageHeight = 640,
            lowClarityRisk = false,
            isDemonstration = false
        )

        val advisory = engine.generateAdvisory(request)
        assertEquals(
            "Advisory must be AVAILABLE on 3rd consecutive run (Reason: ${advisory.unavailableReason})",
            AdvisoryAvailability.AVAILABLE,
            advisory.availability
        )
        assertNotNull("Root cause must not be null", advisory.probableRootCause)
    }

    @Test
    fun test5_unloadSmolLm2Cleanly() = runBlocking {
        val engine = sharedEngine ?: return@runBlocking
        engine.unload()
        assertEquals("Runtime state must be UNLOADED", ModelRuntimeState.UNLOADED, engine.modelInfo.value.state)
        sharedEngine = null
    }
}
