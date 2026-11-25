package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType
import android.graphics.Bitmap
import android.graphics.Color
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
class PatchCorePipelineTest {

    // =========================================================================
    // 1. CONTRACT VALIDATOR TESTS
    // =========================================================================

    @Test
    fun `contract validator accepts verified PatchCore model signature`() {
        // Input: "input", FLOAT, [2, 3, 256, 256]
        PatchCoreContractValidator.validateInputSignature(
            inputName = "input",
            inputType = OnnxJavaType.FLOAT,
            inputShape = longArrayOf(2L, 3L, 256L, 256L)
        )

        // Outputs
        val types = mapOf(
            "pred_score" to OnnxJavaType.FLOAT,
            "pred_label" to OnnxJavaType.BOOL,
            "anomaly_map" to OnnxJavaType.FLOAT,
            "pred_mask" to OnnxJavaType.BOOL
        )
        val shapes = mapOf(
            "pred_score" to longArrayOf(2L),
            "pred_label" to longArrayOf(2L),
            "anomaly_map" to longArrayOf(2L, 1L, 256L, 256L),
            "pred_mask" to longArrayOf(2L, 1L, 256L, 256L)
        )

        val result = PatchCoreContractValidator.validateOutputSignatures(types, shapes)
        assertEquals("input", result.inputName)
        assertEquals(256, result.inputWidth)
        assertEquals(256, result.inputHeight)
        assertEquals(2L, result.inputBatchSize)
        assertEquals("pred_score", result.predScoreOutputName)
        assertEquals("anomaly_map", result.anomalyMapOutputName)
        assertEquals("patchcore-256-fp32-v1", result.contractVersion)
    }

    @Test
    fun `contract validator strictly rejects dynamic batch dimension -1`() {
        val inputFailure = runCatching {
            PatchCoreContractValidator.validateInputSignature(
                inputName = "input",
                inputType = OnnxJavaType.FLOAT,
                inputShape = longArrayOf(-1L, 3L, 256L, 256L)
            )
        }
        assertTrue("Dynamic batch input must be rejected", inputFailure.isFailure)

        val outputFailure = runCatching {
            PatchCoreContractValidator.validateOutputSignatures(
                outputTypes = mapOf(
                    "pred_score" to OnnxJavaType.FLOAT,
                    "anomaly_map" to OnnxJavaType.FLOAT
                ),
                outputShapes = mapOf(
                    "pred_score" to longArrayOf(-1L),
                    "anomaly_map" to longArrayOf(-1L, 1L, 256L, 256L)
                )
            )
        }
        assertTrue("Dynamic batch outputs must be rejected", outputFailure.isFailure)
    }

    @Test
    fun `contract validator rejects invalid input name, type or dimensions`() {
        assertTrue(runCatching {
            PatchCoreContractValidator.validateInputSignature("images", OnnxJavaType.FLOAT, longArrayOf(2L, 3L, 256L, 256L))
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateInputSignature("input", OnnxJavaType.INT8, longArrayOf(2L, 3L, 256L, 256L))
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateInputSignature("input", OnnxJavaType.FLOAT, longArrayOf(1L, 3L, 256L, 256L))
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateInputSignature("input", OnnxJavaType.FLOAT, longArrayOf(2L, 4L, 256L, 256L))
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateInputSignature("input", OnnxJavaType.FLOAT, longArrayOf(2L, 3L, 640L, 640L))
        }.isFailure)
    }

    @Test
    fun `contract validator rejects missing or invalid outputs`() {
        assertTrue(runCatching {
            PatchCoreContractValidator.validateOutputSignatures(
                outputTypes = mapOf("pred_score" to OnnxJavaType.FLOAT),
                outputShapes = mapOf("pred_score" to longArrayOf(2L))
            )
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateOutputSignatures(
                outputTypes = mapOf("anomaly_map" to OnnxJavaType.FLOAT),
                outputShapes = mapOf("anomaly_map" to longArrayOf(2L, 1L, 256L, 256L))
            )
        }.isFailure)

        assertTrue(runCatching {
            PatchCoreContractValidator.validateOutputSignatures(
                outputTypes = mapOf("pred_score" to OnnxJavaType.FLOAT, "anomaly_map" to OnnxJavaType.FLOAT),
                outputShapes = mapOf("pred_score" to longArrayOf(2L), "anomaly_map" to longArrayOf(2L, 1L, 128L, 128L))
            )
        }.isFailure)
    }

    // =========================================================================
    // 2. PREPROCESSOR TESTS
    // =========================================================================

    @Test
    fun `preprocessor produces NCHW layout and batch size 2 with identical duplicate slots`() {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 100, 50))
        }

        val tensor = PatchCorePreprocessor.prepareNchwFloatInput(bmp)

        assertEquals(listOf(2L, 3L, 256L, 256L), tensor.shape.toList())
        assertEquals(512, tensor.originalWidth)
        assertEquals(512, tensor.originalHeight)

        val planeSize = 256 * 256
        val batchStride = 3 * planeSize

        val expectedR = 200f / 255.0f
        val expectedG = 100f / 255.0f
        val expectedB = 50f / 255.0f

        assertEquals(expectedR, tensor.floatBuffer.get(0), 0.005f)
        assertEquals(expectedG, tensor.floatBuffer.get(planeSize), 0.005f)
        assertEquals(expectedB, tensor.floatBuffer.get(2 * planeSize), 0.005f)

        assertEquals(expectedR, tensor.floatBuffer.get(batchStride), 0.005f)
        assertEquals(expectedG, tensor.floatBuffer.get(batchStride + planeSize), 0.005f)
        assertEquals(expectedB, tensor.floatBuffer.get(batchStride + 2 * planeSize), 0.005f)

        assertFalse(bmp.isRecycled)
    }

    @Test
    fun `preprocessor preserves RGB channel ordering and normalized 0 to 1 range`() {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.rgb(255, 0, 0))
            setPixel(255, 255, Color.rgb(0, 0, 255))
        }

        val tensor = PatchCorePreprocessor.prepareNchwFloatInput(bmp)
        val plane = 256 * 256

        assertEquals(1.0f, tensor.floatBuffer.get(0), 0.001f)
        assertEquals(0.0f, tensor.floatBuffer.get(plane), 0.001f)
        assertEquals(0.0f, tensor.floatBuffer.get(2 * plane), 0.001f)

        val lastIdx = 255 * 256 + 255
        assertEquals(0.0f, tensor.floatBuffer.get(lastIdx), 0.001f)
        assertEquals(0.0f, tensor.floatBuffer.get(plane + lastIdx), 0.001f)
        assertEquals(1.0f, tensor.floatBuffer.get(2 * plane + lastIdx), 0.001f)
    }

    // =========================================================================
    // 3. PYTHON ROUNDING COMPATIBILITY TESTS (Ties-To-Even)
    // =========================================================================

    @Test
    fun `pythonRound reproduces Python ties-to-even rounding for half-integers and ordinary values`() {
        assertEquals(0, PatchCoreOutputDecoder.pythonRound(0.5))
        assertEquals(2, PatchCoreOutputDecoder.pythonRound(1.5))
        assertEquals(2, PatchCoreOutputDecoder.pythonRound(2.5))
        assertEquals(4, PatchCoreOutputDecoder.pythonRound(3.5))
        assertEquals(4, PatchCoreOutputDecoder.pythonRound(4.5))
        assertEquals(6, PatchCoreOutputDecoder.pythonRound(5.5))

        assertEquals(2, PatchCoreOutputDecoder.pythonRound(2.1))
        assertEquals(2, PatchCoreOutputDecoder.pythonRound(2.49))
        assertEquals(3, PatchCoreOutputDecoder.pythonRound(2.51))
        assertEquals(3, PatchCoreOutputDecoder.pythonRound(2.9))

        assertEquals(3, PatchCoreOutputDecoder.pythonRound(3.1))
        assertEquals(3, PatchCoreOutputDecoder.pythonRound(3.49))
        assertEquals(4, PatchCoreOutputDecoder.pythonRound(3.51))
        assertEquals(4, PatchCoreOutputDecoder.pythonRound(3.9))
    }

    // =========================================================================
    // 4. SCORE ARRAY SIZE VALIDATION TESTS
    // =========================================================================

    @Test
    fun `decoder rejects score arrays of length other than 2`() {
        val validMap = FloatArray(256 * 256) { 0.0f }

        // Length 0 (empty)
        val r0 = PatchCoreOutputDecoder.decode(floatArrayOf(), validMap, 1024, 1024)
        assertTrue(r0 is PatchCoreDecodeResult.InvalidInferenceOutput)
        assertTrue((r0 as PatchCoreDecodeResult.InvalidInferenceOutput).reason.contains("size 0"))

        // Length 1 (undersized)
        val r1 = PatchCoreOutputDecoder.decode(floatArrayOf(35.0f), validMap, 1024, 1024)
        assertTrue(r1 is PatchCoreDecodeResult.InvalidInferenceOutput)
        assertTrue((r1 as PatchCoreDecodeResult.InvalidInferenceOutput).reason.contains("size 1"))

        // Length 3 (oversized)
        val r3 = PatchCoreOutputDecoder.decode(floatArrayOf(35.0f, 35.0f, 35.0f), validMap, 1024, 1024)
        assertTrue(r3 is PatchCoreDecodeResult.InvalidInferenceOutput)
        assertTrue((r3 as PatchCoreDecodeResult.InvalidInferenceOutput).reason.contains("size 3"))
    }

    // =========================================================================
    // 5. NON-FINITE RAW SCORE HANDLING TESTS
    // =========================================================================

    @Test
    fun `decoder rejects NaN raw anomaly score and does not evaluate to PASS`() {
        val validMap = FloatArray(256 * 256)
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(Float.NaN, Float.NaN),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("NaN raw score must produce InvalidInferenceOutput", result is PatchCoreDecodeResult.InvalidInferenceOutput)
        val failure = result as PatchCoreDecodeResult.InvalidInferenceOutput
        assertTrue(failure.reason.contains("NaN"))
    }

    @Test
    fun `decoder rejects positive and negative infinity raw anomaly scores`() {
        val validMap = FloatArray(256 * 256)

        val posInfResult = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("+Inf must produce InvalidInferenceOutput", posInfResult is PatchCoreDecodeResult.InvalidInferenceOutput)

        val negInfResult = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("-Inf must produce InvalidInferenceOutput", negInfResult is PatchCoreDecodeResult.InvalidInferenceOutput)
    }

    // =========================================================================
    // 6. ANOMALY MAP INTEGRITY FOR ALL RESULTS (PASS & FAIL)
    // =========================================================================

    @Test
    fun `below-threshold score with NaN anomaly map returns InvalidInferenceOutput not PASS`() {
        // Raw score 35.0f is below threshold (normally PASS), but map has NaN
        val mapWithNan = FloatArray(256 * 256) { idx -> if (idx == 50) Float.NaN else 10.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(35.0f, 35.0f),
            anomalyMap = mapWithNan,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue(
            "Below-threshold score with NaN map must return InvalidInferenceOutput, never PASS",
            result is PatchCoreDecodeResult.InvalidInferenceOutput
        )
        val failure = result as PatchCoreDecodeResult.InvalidInferenceOutput
        assertTrue(failure.reason.contains("NaN"))
    }

    @Test
    fun `below-threshold score with incorrectly sized anomaly map returns InvalidInferenceOutput not PASS`() {
        // Raw score 35.0f (normally PASS), but truncated map (65535 floats)
        val truncatedMap = FloatArray(65535) { 10.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(35.0f, 35.0f),
            anomalyMap = truncatedMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue(
            "Below-threshold score with truncated map must return InvalidInferenceOutput, never PASS",
            result is PatchCoreDecodeResult.InvalidInferenceOutput
        )
    }

    @Test
    fun `below-threshold score with oversized anomaly map returns InvalidInferenceOutput not PASS`() {
        val oversizedMap = FloatArray(65537) { 10.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(35.0f, 35.0f),
            anomalyMap = oversizedMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue(
            "Below-threshold score with oversized map must return InvalidInferenceOutput, never PASS",
            result is PatchCoreDecodeResult.InvalidInferenceOutput
        )
    }

    @Test
    fun `below-threshold score with overflow range in anomaly map returns InvalidInferenceOutput not PASS`() {
        val overflowMap = FloatArray(256 * 256) { 0.0f }
        overflowMap[0] = Float.MAX_VALUE
        overflowMap[1] = -Float.MAX_VALUE

        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(35.0f, 35.0f),
            anomalyMap = overflowMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue(
            "Below-threshold score with overflow range map must return InvalidInferenceOutput, never PASS",
            result is PatchCoreDecodeResult.InvalidInferenceOutput
        )
    }

    @Test
    fun `valid below-threshold score with valid map returns successful PASS without bounding box`() {
        val validMap = FloatArray(256 * 256) { 10.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(36.4764f, 36.4764f),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("Valid below-threshold score must return Success", result is PatchCoreDecodeResult.Success)
        val success = result as PatchCoreDecodeResult.Success
        assertFalse("isAnomalous must be false", success.isAnomalous)
        assertNull("Bounding box must be null for non-anomalous result", success.boundingBox)
        assertEquals(0.0f, success.areaPct, 0.001f)
        assertEquals(36.4764f, success.rawAnomalyScore, 0.001f)
    }

    // =========================================================================
    // 7. ANOMALOUS-SCORE MAP REJECTION TESTS
    // =========================================================================

    @Test
    fun `anomalous score with non-finite map values returns InvalidInferenceOutput`() {
        val mapWithPosInf = FloatArray(256 * 256) { idx -> if (idx == 100) Float.POSITIVE_INFINITY else 10.0f }
        val posInfResult = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(45.0f, 45.0f),
            anomalyMap = mapWithPosInf,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("+Inf must produce InvalidInferenceOutput", posInfResult is PatchCoreDecodeResult.InvalidInferenceOutput)

        val mapWithNegInf = FloatArray(256 * 256) { idx -> if (idx == 100) Float.NEGATIVE_INFINITY else 10.0f }
        val negInfResult = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(45.0f, 45.0f),
            anomalyMap = mapWithNegInf,
            originalWidth = 1024,
            originalHeight = 1024
        )
        assertTrue("-Inf must produce InvalidInferenceOutput", negInfResult is PatchCoreDecodeResult.InvalidInferenceOutput)
    }

    // =========================================================================
    // 8. DIMENSION AND THRESHOLD BOUNDARY TESTS
    // =========================================================================

    @Test
    fun `decoder rejects zero and negative original image dimensions`() {
        val validMap = FloatArray(256 * 256) { 0.0f }

        val w0 = PatchCoreOutputDecoder.decode(floatArrayOf(45.0f, 45.0f), validMap, 0, 1024)
        assertTrue("Width 0 must return InvalidInferenceOutput", w0 is PatchCoreDecodeResult.InvalidInferenceOutput)

        val h0 = PatchCoreOutputDecoder.decode(floatArrayOf(45.0f, 45.0f), validMap, 1024, 0)
        assertTrue("Height 0 must return InvalidInferenceOutput", h0 is PatchCoreDecodeResult.InvalidInferenceOutput)

        val wNeg = PatchCoreOutputDecoder.decode(floatArrayOf(45.0f, 45.0f), validMap, -100, 1024)
        assertTrue("Negative width must return InvalidInferenceOutput", wNeg is PatchCoreDecodeResult.InvalidInferenceOutput)

        val locNeg = PatchCoreOutputDecoder.localize(validMap, -100, 1024)
        assertTrue("Direct localize with negative dimensions must return InvalidMap", locNeg is PatchCoreOutputDecoder.LocalizationResult.InvalidMap)
    }

    @Test
    fun `decoder respects exact threshold boundary at 39_5`() {
        val validMap = FloatArray(256 * 256) { 0.0f }

        // Below threshold: 39.499f -> PASS
        val subThreshold = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(39.499f, 39.499f),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        ) as PatchCoreDecodeResult.Success
        assertFalse(subThreshold.isAnomalous)
        assertNull(subThreshold.boundingBox)
        assertEquals(39.499f, subThreshold.rawAnomalyScore, 0.001f)

        // Exactly at threshold: 39.5f -> FAIL (anomalous)
        val atThreshold = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(39.5f, 39.5f),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        ) as PatchCoreDecodeResult.Success
        assertTrue(atThreshold.isAnomalous)
        assertEquals(39.5f, atThreshold.rawAnomalyScore, 0.001f)
        assertEquals(0.5f, atThreshold.displayScore, 0.001f)

        // Above threshold: 54.634f -> FAIL
        val aboveThreshold = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(54.634f, 54.634f),
            anomalyMap = validMap,
            originalWidth = 1024,
            originalHeight = 1024
        ) as PatchCoreDecodeResult.Success
        assertTrue(aboveThreshold.isAnomalous)
        assertTrue(aboveThreshold.displayScore > 0.99f)
    }

    @Test
    fun `decoder handles flat anomaly maps safely`() {
        val flatMap = FloatArray(256 * 256) { 10.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(45.0f, 45.0f),
            anomalyMap = flatMap,
            originalWidth = 1024,
            originalHeight = 1024
        ) as PatchCoreDecodeResult.Success
        assertTrue(result.isAnomalous)
        assertNull(result.boundingBox)
        assertEquals(0.0f, result.areaPct, 0.001f)
    }

    @Test
    fun `decoder strictly enforces threshold strictly greater than 195 with uint8 truncation`() {
        val map = FloatArray(256 * 256) { 0.0f }
        map[0] = 255.0f

        // 195.9f -> truncates to 195 uint8 -> NOT > 195
        map[10 * 256 + 10] = 195.9f

        val locResult1 = PatchCoreOutputDecoder.localize(map, 1024, 1024) as PatchCoreOutputDecoder.LocalizationResult.Success
        val box1 = locResult1.boundingBox
        assertNotNull(box1)
        assertEquals(0, box1!!.x1)
        assertEquals(0, box1.y1)

        // 196.0f -> uint8 196 -> strictly > 195
        val map2 = FloatArray(256 * 256) { 0.0f }
        map2[0] = 255.0f
        map2[100 * 256 + 100] = 196.0f
        val locResult2 = PatchCoreOutputDecoder.localize(map2, 256, 256) as PatchCoreOutputDecoder.LocalizationResult.Success
        val box2 = locResult2.boundingBox
        assertNotNull(box2)
    }

    @Test
    fun `decoder scales coordinates and clamps to original image dimensions`() {
        val map = FloatArray(256 * 256) { 0.0f }

        for (y in 197 until 243) {
            for (x in 57 until 81) {
                map[y * 256 + x] = 255.0f
            }
        }

        val locResult = PatchCoreOutputDecoder.localize(map, 1024, 1024) as PatchCoreOutputDecoder.LocalizationResult.Success
        val box = locResult.boundingBox
        assertNotNull(box)
        assertEquals(228, box!!.x1)
        assertEquals(788, box.y1)
        assertEquals(324, box.x2)
        assertEquals(972, box.y2)
    }

    @Test
    fun `empty localization produces null bounding box and zero area`() {
        val emptyMap = FloatArray(256 * 256) { 0.0f }
        val result = PatchCoreOutputDecoder.decode(
            predScores = floatArrayOf(45.0f, 45.0f),
            anomalyMap = emptyMap,
            originalWidth = 1000,
            originalHeight = 1000
        ) as PatchCoreDecodeResult.Success
        assertTrue(result.isAnomalous)
        assertNull(result.boundingBox)
        assertEquals(0.0f, result.areaPct, 0.001f)
    }

    // =========================================================================
    // 9. SYNTHETIC CONTOUR REGRESSION TEST: HOLLOW VS SOLID COUNTEREXAMPLE
    // =========================================================================

    @Test
    fun `synthetic regression test demonstrating divergence between contourArea and pixel count on hollow frame`() {
        val map = FloatArray(256 * 256) { 0.0f }

        // Region A (Hollow Frame): 30x30 outer frame at (10, 10) to (39, 39) with 1-pixel stroke.
        // Foreground pixel count = 116.
        // Mathematical OpenCV outer contour area (Green's theorem outer boundary) = 29 * 29 = 841.0.
        for (x in 10 until 40) {
            map[10 * 256 + x] = 255.0f
            map[39 * 256 + x] = 255.0f
        }
        for (y in 11 until 39) {
            map[y * 256 + 10] = 255.0f
            map[y * 256 + 39] = 255.0f
        }

        // Region B (Solid Block): 16x16 solid rectangle at (100, 100) to (115, 115).
        // Foreground pixel count = 16 * 16 = 256.
        // Mathematical OpenCV outer contour area = 15 * 15 = 225.0.
        for (y in 100 until 116) {
            for (x in 100 until 116) {
                map[y * 256 + x] = 255.0f
            }
        }

        val locResult = PatchCoreOutputDecoder.localize(map, 256, 256) as PatchCoreOutputDecoder.LocalizationResult.Success
        val box = locResult.boundingBox
        assertNotNull(box)

        // In Kotlin (pixel count):
        // Region B has 256 pixels > Region A has 116 pixels.
        // Kotlin selects Region B at [100, 100, 116, 116].
        assertEquals("Kotlin pixel-count algorithm selects solid 16x16 block", 100, box!!.x1)
        assertEquals(100, box.y1)
        assertEquals(116, box.x2)
        assertEquals(116, box.y2)

        // NOTE: This test serves as a synthetic algorithmic counterexample demonstrating that
        // Kotlin 8-connected BFS pixel count and OpenCV RETR_EXTERNAL contourArea can select
        // different regions on hollow or holed topologies. This test does not execute OpenCV.
    }
}
