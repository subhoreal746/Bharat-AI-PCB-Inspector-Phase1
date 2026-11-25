package com.example.inference.cv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.domain.model.PcbImageInput
import com.example.domain.model.PcbImageSource
import com.example.domain.model.TransistorAnomalyData
import com.example.domain.policy.InspectionPolicyEngine
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PatchCoreTransistorEngineTest {

    private lateinit var context: Context
    private lateinit var engine: PatchCoreTransistorEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = PatchCoreTransistorEngine(context, Dispatchers.Unconfined)
    }

    @Test
    fun `engine initial state is NOT_LOADED with TRANSISTOR_ANOMALY slot`() {
        val info = engine.modelInfo.value
        assertEquals(ModelSlot.TRANSISTOR_ANOMALY, info.slot)
        assertEquals(ModelRuntimeState.NOT_LOADED, info.state)
        assertFalse(info.isDemonstrationOnly)
        assertEquals("PatchCoreTransistorEngine (PatchCore / ONNX Runtime)", engine.engineLabel)
        assertTrue(engine.requiresBitmap)
        assertFalse(engine.isDemonstrationEngine)
        assertNull(info.artifactPath)
        assertNull(info.sha256)
    }

    @Test
    fun `detect on uninitialized engine returns MODEL_NOT_LOADED failure and never throws`() = runBlocking {
        val input = PcbImageInput(
            sourceUri = "file:///tmp/transistor_mock.png",
            sourceType = PcbImageSource.GALLERY,
            boardName = "Transistor Test",
            validation = ImageValidationResult(
                isValid = true,
                isProcessable = true,
                width = 1024,
                height = 1024,
                clarityScore = 200.0,
                lowClarityRisk = false
            )
        )

        val outcome = engine.detect(input)
        assertTrue(outcome is DetectionOutcome.Failure)
        val failure = outcome as DetectionOutcome.Failure
        assertEquals(DetectionErrorType.MODEL_NOT_LOADED, failure.errorType)
        assertTrue(failure.errorMessage.contains("NOT_LOADED"))
        assertEquals("PatchCoreTransistorEngine", failure.provenance.inferenceEngine)
        assertEquals("NONE", failure.provenance.modelSha256)
    }

    @Test
    fun `setConfiguredArtifact transitions state to UNLOADED when valid file exists`() = runBlocking {
        val tempFile = File(context.cacheDir, "test_dummy.onnx").apply {
            writeBytes(ByteArray(1024) { 1 })
        }

        engine.setConfiguredArtifact(tempFile)
        val info = engine.modelInfo.value
        assertEquals(ModelRuntimeState.UNLOADED, info.state)
        assertEquals(tempFile.absolutePath, info.artifactPath)
        assertTrue(info.detailMessage.contains("PatchCore model artifact stored"))

        val input = PcbImageInput(
            sourceUri = "file:///tmp/transistor_mock.png",
            sourceType = PcbImageSource.CAMERA,
            boardName = "Transistor Camera",
            validation = ImageValidationResult(
                isValid = true,
                isProcessable = true,
                width = 512,
                height = 512,
                clarityScore = 150.0,
                lowClarityRisk = false
            )
        )

        val outcome = engine.detect(input)
        assertTrue(outcome is DetectionOutcome.Failure)
        assertEquals(DetectionErrorType.MODEL_UNLOADED, (outcome as DetectionOutcome.Failure).errorType)
    }

    @Test
    fun `validateAndStageCandidate rejects missing or corrupted candidate files`() = runBlocking {
        val missingFile = File(context.cacheDir, "non_existent_patchcore.onnx")
        val resultMissing = engine.validateAndStageCandidate(missingFile)
        assertTrue(resultMissing.isFailure)
        assertTrue(resultMissing.exceptionOrNull()?.message?.contains("missing or unreadable") == true)

        val wrongShaFile = File(context.cacheDir, "wrong_sha.onnx").apply {
            writeBytes("corrupted onnx content".toByteArray())
        }
        val resultWrongSha = engine.validateAndStageCandidate(wrongShaFile)
        assertTrue(resultWrongSha.isFailure)
        assertTrue(resultWrongSha.exceptionOrNull()?.message?.contains("SHA-256 mismatch") == true)
    }

    @Test
    fun `metadata text formatting correctly displays metadata and slot info`() {
        val text = engine.inspectCurrentMetadataText()
        assertTrue(text.contains("Slot: Unsupervised Anomaly Detection (PatchCore)"))
        assertTrue(text.contains("State: NOT_LOADED"))
        assertTrue(text.contains("Runtime: ONNX Runtime Android 1.22.0"))
        assertTrue(text.contains("SHA-256: N/A"))
    }

    @Test
    fun `unload transitions ready engine to UNLOADED or NOT_LOADED without resource leaks`() = runBlocking {
        engine.unload()
        assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)

        val dummyFile = File(context.cacheDir, "dummy_model.onnx").apply { writeBytes(ByteArray(10)) }
        engine.setConfiguredArtifact(dummyFile)
        assertEquals(ModelRuntimeState.UNLOADED, engine.modelInfo.value.state)

        engine.unload()
        assertEquals(ModelRuntimeState.UNLOADED, engine.modelInfo.value.state)
    }

    @Test
    fun `preprocessor produces exact batch 2 NCHW 256x256 float tensor without recycling input bitmap`() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 100, 50))

        val tensor = PatchCorePreprocessor.prepareNchwFloatInput(bitmap)
        assertFalse(bitmap.isRecycled)
        assertEquals(800, tensor.originalWidth)
        assertEquals(600, tensor.originalHeight)
        assertEquals(listOf(2L, 3L, 256L, 256L), tensor.shape.toList())

        val planeSize = 256 * 256
        val batchStride = 3 * planeSize

        // Channel 0 (Red)
        assertEquals(200f / 255f, tensor.floatBuffer.get(0), 0.01f)
        assertEquals(200f / 255f, tensor.floatBuffer.get(batchStride), 0.01f)

        // Channel 1 (Green)
        assertEquals(100f / 255f, tensor.floatBuffer.get(planeSize), 0.01f)
        assertEquals(100f / 255f, tensor.floatBuffer.get(batchStride + planeSize), 0.01f)

        // Channel 2 (Blue)
        assertEquals(50f / 255f, tensor.floatBuffer.get(2 * planeSize), 0.01f)
        assertEquals(50f / 255f, tensor.floatBuffer.get(batchStride + 2 * planeSize), 0.01f)

        bitmap.recycle()
    }

    @Test
    fun `decoder classifies golden good sample as non-anomalous PASS with null bounding box`() {
        val rawScore = 36.4764f // trn_good_000.png reference score
        val normalMap = FloatArray(PatchCoreOutputDecoder.EXACT_MAP_SIZE) { 0.1f }

        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(rawScore, rawScore),
            anomalyMap = normalMap,
            originalWidth = 1024,
            originalHeight = 1024
        )

        assertTrue(result is PatchCoreDecodeResult.Success)
        val success = result as PatchCoreDecodeResult.Success
        assertFalse(success.isAnomalous)
        assertEquals(rawScore, success.rawAnomalyScore, 1e-4f)
        assertTrue(success.displayScore < 0.5f)
        assertNull(success.boundingBox)
        assertEquals(0.0f, success.areaPct, 0.001f)
    }

    @Test
    fun `decoder classifies golden bent lead sample as anomalous with expected coordinates`() {
        val rawScore = 54.6340f // trn_bent_lead_000.png reference score
        // Create an anomaly map with a localized high-intensity patch corresponding to the reference box [228, 788, 324, 972]
        // In 256x256 space with original 1024x1024, scale is 4x -> box in 256x256 is roughly [57, 197, 81, 243]
        val anomalyMap = FloatArray(PatchCoreOutputDecoder.EXACT_MAP_SIZE) { 0.0f }
        for (y in 197 until 243) {
            for (x in 57 until 81) {
                anomalyMap[y * 256 + x] = 100.0f
            }
        }

        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(rawScore, rawScore),
            anomalyMap = anomalyMap,
            originalWidth = 1024,
            originalHeight = 1024
        )

        assertTrue(result is PatchCoreDecodeResult.Success)
        val success = result as PatchCoreDecodeResult.Success
        assertTrue(success.isAnomalous)
        assertEquals(rawScore, success.rawAnomalyScore, 1e-4f)
        assertTrue(success.displayScore > 0.99f)
        assertNotNull(success.boundingBox)

        val box = success.boundingBox!!
        assertEquals(228, box.x1)
        assertEquals(788, box.y1)
        assertEquals(324, box.x2)
        assertEquals(972, box.y2)
    }

    @Test
    fun `policy engine routes anomalous transistor result to REVIEW and non-anomalous to PASS`() {
        val policyEngine = InspectionPolicyEngine()
        val config = InspectionPolicyConfig()
        val validImage = ImageValidationResult(
            isValid = true,
            isProcessable = true,
            width = 1024,
            height = 1024,
            clarityScore = 150.0,
            lowClarityRisk = false
        )

        // 1. Anomalous case -> REVIEW with Transistor Anomaly defect
        val anomalousOutcome = DetectionOutcome.Success(
            detections = listOf(
                com.example.domain.model.DetectedDefect(
                    defectId = "DEF-TR-001",
                    classIndex = 0,
                    rawClassName = "transistor_anomaly",
                    canonicalClassName = "Transistor Anomaly",
                    confidence = 0.999f,
                    severity = DefectSeverity.MANUAL_REVIEW,
                    requiresManualReview = true,
                    boundingBox = BoundingBox(0.22f, 0.77f, 0.10f, 0.18f),
                    code = "TR-ANOMALY-REVIEW"
                )
            ),
            latencyMetrics = com.example.domain.model.InspectionLatencyMetrics(patchCoreInferenceMs = 45L),
            provenance = com.example.domain.model.ModelExecutionProvenance(
                inferenceEngine = "PatchCoreTransistorEngine",
                modelIdentity = "active_patchcore.onnx",
                modelSha256 = "544af0939a22ce9cf2f64460f1e3e8323ec4be2c91d0d914f8c4e788c4f44e95",
                modelContractVersion = "patchcore-256-fp32-v1",
                preprocessingVersion = "patchcore-warp-rgb256-b2-v1",
                decoderVersion = "patchcore-anomaly-v1",
                classMappingVersion = "pcb-anomaly-v1",
                runtimeVersion = "ONNX Runtime Android 1.22.0"
            ),
            isDemonstration = false,
            transistorAnomaly = TransistorAnomalyData(
                rawAnomalyScore = 54.6340f,
                normalizedDisplayScore = 0.999f,
                isAnomalous = true,
                affectedAreaPercent = 1.8f,
                boundingBox = BoundingBox(0.22f, 0.77f, 0.10f, 0.18f)
            )
        )

        val reviewResult = policyEngine.evaluate(validImage, anomalousOutcome, config)
        assertEquals(InspectionDisposition.REVIEW, reviewResult.officialDisposition)
        assertTrue(reviewResult.rationale.contains("Transistor Anomaly"))
        assertEquals(1, reviewResult.evaluatedDefects.size)
        assertEquals("Transistor Anomaly", reviewResult.evaluatedDefects.first().canonicalClassName)
        assertEquals(DefectSeverity.MANUAL_REVIEW, reviewResult.evaluatedDefects.first().severity)

        // 2. Non-anomalous case -> PASS with 0 defects
        val passOutcome = DetectionOutcome.Success(
            detections = emptyList(),
            latencyMetrics = com.example.domain.model.InspectionLatencyMetrics(patchCoreInferenceMs = 42L),
            provenance = anomalousOutcome.provenance,
            isDemonstration = false,
            transistorAnomaly = TransistorAnomalyData(
                rawAnomalyScore = 36.4764f,
                normalizedDisplayScore = 0.140f,
                isAnomalous = false,
                affectedAreaPercent = null,
                boundingBox = null
            )
        )

        val passResult = policyEngine.evaluate(validImage, passOutcome, config)
        assertEquals(InspectionDisposition.PASS, passResult.officialDisposition)
        assertTrue(passResult.evaluatedDefects.isEmpty())
        assertTrue(passResult.rationale.contains("0 defects localized"))
    }

    @Test
    fun `golden validation all 5 reference scores yield expected heuristic scores and dispositions`() {
        // Test all 5 golden transistor reference samples from GOLDEN_VALIDATION.json / reference_python
        val goldenSamples = listOf(
            Triple("trn_good_000.png", 36.4764f, false),
            Triple("trn_bent_lead_000.png", 54.6340f, true),
            Triple("trn_cut_lead_000.png", 49.1274f, true),
            Triple("trn_damaged_case_000.png", 47.8302f, true),
            Triple("trn_misplaced_000.png", 43.2355f, true)
        )

        for ((name, rawScore, expectedAnomalous) in goldenSamples) {
            val normalMap = FloatArray(PatchCoreOutputDecoder.EXACT_MAP_SIZE) { 0.5f }
            val result = PatchCoreOutputDecoder.decode(
                predScores = floatArrayOf(rawScore, rawScore),
                anomalyMap = normalMap,
                originalWidth = 1024,
                originalHeight = 1024
            )

            assertTrue("Golden sample $name decode must succeed", result is PatchCoreDecodeResult.Success)
            val success = result as PatchCoreDecodeResult.Success
            assertEquals("Golden sample $name anomaly decision mismatch", expectedAnomalous, success.isAnomalous)
            assertEquals("Golden sample $name raw score mismatch", rawScore, success.rawAnomalyScore, 1e-4f)

            if (expectedAnomalous) {
                assertTrue("Golden anomalous sample $name must have display score >= 0.5", success.displayScore >= 0.5f)
            } else {
                assertTrue("Golden normal sample $name must have display score < 0.5", success.displayScore < 0.5f)
            }
        }
    }
}
