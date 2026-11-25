package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.PcbImageInput
import com.example.domain.model.PcbImageSource
import com.example.domain.policy.DefectTaxonomy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YoloPipelineTest {

    @Test
    fun `letterbox computes OpenCV compatible integer dimensions and asymmetric rounding padding`() {
        // 1921x1080 -> r = 640/1921, new_unpad = (640, 360), top=140, bottom=140
        val evenTransform = YoloPreprocessor.computeLetterboxTransform(1921, 1080, 640, 640)
        assertEquals(640, evenTransform.scaledWidth)
        assertEquals(360, evenTransform.scaledHeight)
        assertEquals(0, evenTransform.padLeft)
        assertEquals(0, evenTransform.padRight)
        assertEquals(140, evenTransform.padTop)
        assertEquals(140, evenTransform.padBottom)

        // Odd height remainder: 1000x561 -> r = 0.64, new_unpad = (640, 359), dh = 140.5 -> top=140, bottom=141
        val oddTransform = YoloPreprocessor.computeLetterboxTransform(1000, 561, 640, 640)
        assertEquals(640, oddTransform.scaledWidth)
        assertEquals(359, oddTransform.scaledHeight)
        assertEquals(140, oddTransform.padTop)
        assertEquals(141, oddTransform.padBottom)
        assertEquals(640, oddTransform.padTop + oddTransform.scaledHeight + oddTransform.padBottom)
    }

    @Test
    fun `letterbox transform reverse maps model space coordinates accurately to original normalized space`() {
        val transform = YoloPreprocessor.computeLetterboxTransform(
            originalWidth = 1200,
            originalHeight = 900,
            targetWidth = 640,
            targetHeight = 640
        )
        assertEquals(0f, transform.padX, 0.01f)
        assertEquals(80f, transform.padY, 0.01f)

        val mapped = transform.mapBoxToNormalizedOriginal(0f, 80f, 640f, 560f)
        assertEquals(0f, mapped.x, 0.002f)
        assertEquals(0f, mapped.y, 0.002f)
        assertEquals(1f, mapped.width, 0.002f)
        assertEquals(1f, mapped.height, 0.002f)

        val quarter = transform.mapBoxToNormalizedOriginal(160f, 200f, 480f, 440f)
        assertEquals(0.25f, quarter.x, 0.005f)
        assertEquals(0.25f, quarter.y, 0.005f)
        assertEquals(0.50f, quarter.width, 0.005f)
        assertEquals(0.50f, quarter.height, 0.005f)
    }

    @Test
    fun `prepareNchwFloatInput pads with RGB 114 and writes normalized RGB planes in NCHW 640x640 order`() {
        val bmp = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(255, 128, 64))
        }
        val tensor = YoloPreprocessor.prepareNchwFloatInput(bmp, targetWidth = 640, targetHeight = 640)
        assertEquals(listOf(1L, 3L, 640L, 640L), tensor.shape.toList())

        val plane = 640 * 640
        val expectedPad = 114f / 255f
        // Top padding row (y = 0, x = 0)
        assertEquals(expectedPad, tensor.floatBuffer.get(0), 0.005f)
        assertEquals(expectedPad, tensor.floatBuffer.get(plane), 0.005f)
        assertEquals(expectedPad, tensor.floatBuffer.get(2 * plane), 0.005f)

        // Bottom padding row (y = 639, x = 100)
        val bottomIdx = 639 * 640 + 100
        assertEquals(expectedPad, tensor.floatBuffer.get(bottomIdx), 0.005f)

        // Center image pixel (y = 320, x = 320) -> R=1.0, G=128/255, B=64/255
        val centerIdx = 320 * 640 + 320
        assertEquals(1.0f, tensor.floatBuffer.get(centerIdx), 0.005f)
        assertEquals(128f / 255f, tensor.floatBuffer.get(plane + centerIdx), 0.005f)
        assertEquals(64f / 255f, tensor.floatBuffer.get(2 * plane + centerIdx), 0.005f)
    }

    @Test
    fun `global class agnostic NMS suppresses overlapping boxes across different classes`() {
        val highOpen = RawDetectionCandidate(classIndex = 0, confidence = 0.90f, x1Px = 100f, y1Px = 100f, x2Px = 200f, y2Px = 200f)
        val overlapSpur = RawDetectionCandidate(classIndex = 3, confidence = 0.78f, x1Px = 104f, y1Px = 104f, x2Px = 204f, y2Px = 204f)
        val distinctShort = RawDetectionCandidate(classIndex = 1, confidence = 0.70f, x1Px = 350f, y1Px = 350f, x2Px = 450f, y2Px = 450f)

        val kept = NonMaxSuppression.applyClassAgnosticNms(
            listOf(overlapSpur, highOpen, distinctShort),
            iouThreshold = 0.45f
        )
        assertEquals(2, kept.size)
        assertEquals(0, kept[0].classIndex)
        assertEquals(1, kept[1].classIndex)
    }

    @Test
    fun `decoder parses full 1x15x8400 tensor applies 0_33 confidence threshold and maps all 11 classes`() {
        val labels = DefectTaxonomy.expectedDatasetLabelsByIndex
        assertEquals(11, labels.size)
        val numBoxes = 8400
        val data = FloatArray(15 * numBoxes)

        // Anchor 10: below 0.33 threshold (0.32) -> must be filtered out
        data[0 * numBoxes + 10] = 200f
        data[1 * numBoxes + 10] = 200f
        data[2 * numBoxes + 10] = 60f
        data[3 * numBoxes + 10] = 60f
        data[(4 + 0) * numBoxes + 10] = 0.32f

        // Anchor 11..21: one valid box for each of the 11 classes (0..10), spaced apart to avoid NMS overlap
        for (cid in 0 until 11) {
            val anchor = 100 + cid
            val col = cid % 4
            val row = cid / 4
            val cx = 90f + col * 150f
            val cy = 110f + row * 170f
            data[0 * numBoxes + anchor] = cx
            data[1 * numBoxes + anchor] = cy
            data[2 * numBoxes + anchor] = 80f
            data[3 * numBoxes + anchor] = 80f
            data[(4 + cid) * numBoxes + anchor] = 0.60f + cid * 0.02f
        }

        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 8400L),
            classLabels = labels,
            transform = transform
        )

        assertTrue(result is YoloDecodeResult.Success)
        val detections = (result as YoloDecodeResult.Success).detections
        assertEquals(11, detections.size)
        val byIndex = detections.associateBy { it.classIndex }
        assertEquals("Open Circuit", byIndex.getValue(0).canonicalClassName)
        assertEquals("Short Circuit", byIndex.getValue(1).canonicalClassName)
        assertEquals("Mouse Bite", byIndex.getValue(2).canonicalClassName)
        assertEquals("Spur", byIndex.getValue(3).canonicalClassName)
        assertEquals("Spurious Copper", byIndex.getValue(4).canonicalClassName)
        assertEquals("Missing Copper", byIndex.getValue(5).canonicalClassName)
        assertEquals("Copper Burr", byIndex.getValue(6).canonicalClassName)
        assertEquals("Pin Hole", byIndex.getValue(7).canonicalClassName)
        assertEquals("Hole Breakout", byIndex.getValue(8).canonicalClassName)
        assertEquals("Scratch", byIndex.getValue(9).canonicalClassName)
        assertEquals("Copper", byIndex.getValue(10).canonicalClassName)
        assertEquals(DefectSeverity.REQUIRES_DATASET_VERIFICATION, byIndex.getValue(10).severity)
    }

    @Test
    fun `border crop guard ignores small low-conf edge boxes while keeping high-conf large or interior boxes`() {
        val labels = DefectTaxonomy.expectedDatasetLabelsByIndex
        val numBoxes = 4
        val data = FloatArray(15 * numBoxes)

        // Box 0: touches left edge (x1=4 <= 8), conf=0.42 (< 0.50), size 40x40 on 640x640 (0.39% < 2.5%) -> IGNORED
        data[0 * numBoxes + 0] = 24f
        data[1 * numBoxes + 0] = 200f
        data[2 * numBoxes + 0] = 40f
        data[3 * numBoxes + 0] = 40f
        data[(4 + 2) * numBoxes + 0] = 0.42f

        // Box 1: touches top edge (y1=4 <= 8), conf=0.55 (>= 0.50), small -> KEPT
        data[0 * numBoxes + 1] = 300f
        data[1 * numBoxes + 1] = 24f
        data[2 * numBoxes + 1] = 40f
        data[3 * numBoxes + 1] = 40f
        data[(4 + 3) * numBoxes + 1] = 0.55f

        // Box 2: touches right edge (x2=636 >= 632), conf=0.42 (< 0.50), large 150x150 (5.49% >= 2.5%) -> KEPT
        data[0 * numBoxes + 2] = 561f
        data[1 * numBoxes + 2] = 320f
        data[2 * numBoxes + 2] = 150f
        data[3 * numBoxes + 2] = 150f
        data[(4 + 4) * numBoxes + 2] = 0.42f

        // Box 3: interior (x1=120, y1=120), conf=0.40 (< 0.50), small -> KEPT
        data[0 * numBoxes + 3] = 140f
        data[1 * numBoxes + 3] = 140f
        data[2 * numBoxes + 3] = 40f
        data[3 * numBoxes + 3] = 40f
        data[(4 + 7) * numBoxes + 3] = 0.40f

        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 4L),
            classLabels = labels,
            transform = transform
        )

        assertTrue(result is YoloDecodeResult.Success)
        val keptClasses = (result as YoloDecodeResult.Success).detections.map { it.classIndex }.toSet()
        assertFalse(keptClasses.contains(2))
        assertEquals(setOf(3, 4, 7), keptClasses)
    }

    @Test
    fun `green PCB defect refinement preserves original class without HSV remapping`() {
        // 1) Green board with charred dark ROI (previously remapped to 5) -> must retain class 9
        val greenWithChar = createGreenBoardBitmap(200, 200).apply {
            paintRect(40, 40, 100, 100, Color.rgb(30, 30, 30))
        }
        assertEquals(9, GreenPcbDefectRefiner.refineClassId(greenWithChar, 40, 40, 100, 100, 9))

        // 2) Green board with bright metallic solder ROI (previously remapped to 1) -> must retain class 9
        val greenWithSolder = createGreenBoardBitmap(200, 200).apply {
            paintRect(40, 40, 100, 100, Color.rgb(210, 212, 215))
        }
        assertEquals(9, GreenPcbDefectRefiner.refineClassId(greenWithSolder, 40, 40, 100, 100, 9))

        // 3) Green board with green/mild abrasion ROI -> stays 9 (scratch)
        val greenScratch = createGreenBoardBitmap(200, 200)
        assertEquals(9, GreenPcbDefectRefiner.refineClassId(greenScratch, 40, 40, 100, 100, 9))

        // 4) Non-scratch class on green board -> never remapped
        assertEquals(2, GreenPcbDefectRefiner.refineClassId(greenWithChar, 40, 40, 100, 100, 2))

        // 5) Grayscale / amber non-green board with dark ROI -> stays 9
        val amberBoard = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 140, 40))
            paintRect(40, 40, 100, 100, Color.rgb(20, 20, 20))
        }
        assertEquals(9, GreenPcbDefectRefiner.refineClassId(amberBoard, 40, 40, 100, 100, 9))
    }

    @Test
    fun `decoder preserves class 9 when supplied with green bitmap containing bright low-saturation pixels`() {
        val labels = DefectTaxonomy.expectedDatasetLabelsByIndex
        val numBoxes = 1
        val data = FloatArray(15 * numBoxes)
        data[0] = 320f
        data[1] = 320f
        data[2] = 128f
        data[3] = 128f
        data[4 + 9] = 0.72f // raw prediction: class 9 ("scratch")

        // Green board with bright metallic solder ROI (V > 165, S < 75)
        val greenWithSolder = createGreenBoardBitmap(640, 640).apply {
            paintRect(256, 256, 384, 384, Color.rgb(210, 212, 215))
        }
        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 1L),
            classLabels = labels,
            transform = transform,
            sourceBitmap = greenWithSolder
        )

        assertTrue(result is YoloDecodeResult.Success)
        val detections = (result as YoloDecodeResult.Success).detections
        assertEquals(1, detections.size)
        assertEquals(9, detections[0].classIndex)
        assertEquals("Scratch", detections[0].canonicalClassName)
        assertEquals(0.72f, detections[0].confidence, 0.001f)
        assertEquals(DefectSeverity.MANUAL_REVIEW, detections[0].severity)
    }

    @Test
    fun `decoder preserves class 9 when supplied with green bitmap containing dark charred pixels`() {
        val labels = DefectTaxonomy.expectedDatasetLabelsByIndex
        val numBoxes = 1
        val data = FloatArray(15 * numBoxes)
        data[0] = 320f
        data[1] = 320f
        data[2] = 128f
        data[3] = 128f
        data[4 + 9] = 0.72f // raw prediction: class 9 ("scratch")

        val greenWithChar = createGreenBoardBitmap(640, 640).apply {
            paintRect(256, 256, 384, 384, Color.rgb(25, 25, 25))
        }
        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 1L),
            classLabels = labels,
            transform = transform,
            sourceBitmap = greenWithChar
        )

        assertTrue(result is YoloDecodeResult.Success)
        val detections = (result as YoloDecodeResult.Success).detections
        assertEquals(1, detections.size)
        assertEquals(9, detections[0].classIndex)
        assertEquals("Scratch", detections[0].canonicalClassName)
        assertEquals(0.72f, detections[0].confidence, 0.001f)
        assertEquals(DefectSeverity.MANUAL_REVIEW, detections[0].severity)
    }

    @Test
    fun `onnx contract validator accepts 640 FP32 11-class model and rejects incompatible contracts`() {
        // Valid 640 FP32 contract with Python dict metadata
        OnnxContractValidator.validateInputSignature("images", OnnxJavaType.FLOAT, longArrayOf(1L, 3L, 640L, 640L))
        val validNames = "{0: 'open', 1: 'short', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch', 10: 'copper'}"
        val validated = OnnxContractValidator.validateResolvedContract(
            outputName = "output0",
            outputType = OnnxJavaType.FLOAT,
            resolvedOutputShape = longArrayOf(1L, 15L, 8400L),
            customMetadata = mapOf("names" to validNames)
        )
        assertTrue(validated.hasEmbeddedClassMetadata)
        assertEquals(640, validated.inputWidth)
        assertEquals(11, validated.classLabels.size)

        // Reject 416 input model
        val wrongSize = runCatching {
            OnnxContractValidator.validateInputSignature("images", OnnxJavaType.FLOAT, longArrayOf(1L, 3L, 416L, 416L))
        }
        assertTrue(wrongSize.isFailure)

        // Reject batch=2 PatchCore input
        val wrongBatch = runCatching {
            OnnxContractValidator.validateInputSignature("input", OnnxJavaType.FLOAT, longArrayOf(2L, 3L, 256L, 256L))
        }
        assertTrue(wrongBatch.isFailure)

        // Reject INT8 quantized input
        val wrongDtype = runCatching {
            OnnxContractValidator.validateInputSignature("images", OnnxJavaType.UINT8, longArrayOf(1L, 3L, 640L, 640L))
        }
        assertTrue(wrongDtype.isFailure)

        // Reject 416 output anchor count (3549 != 8400) or 80-class COCO
        val wrongOutput = runCatching {
            OnnxContractValidator.validateResolvedContract(
                outputName = "output0",
                outputType = OnnxJavaType.FLOAT,
                resolvedOutputShape = longArrayOf(1L, 15L, 3549L),
                customMetadata = emptyMap()
            )
        }
        assertTrue(wrongOutput.isFailure)

        // Reject swapped class order in metadata
        val swappedNames = "{0: 'short', 1: 'open', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch', 10: 'copper'}"
        val wrongTaxonomy = runCatching {
            OnnxContractValidator.validateResolvedContract(
                outputName = "output0",
                outputType = OnnxJavaType.FLOAT,
                resolvedOutputShape = longArrayOf(1L, 15L, 8400L),
                customMetadata = mapOf("names" to swappedNames)
            )
        }
        assertTrue(wrongTaxonomy.isFailure)
    }

    @Test
    fun `decoder rejects unsupported output shape cleanly`() {
        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = FloatArray(100),
            shape = longArrayOf(1L, 10L, 10L),
            classLabels = DefectTaxonomy.expectedDatasetLabelsByIndex,
            transform = transform
        )
        assertTrue(result is YoloDecodeResult.UnsupportedShape)
    }

    @Test
    fun `unloaded OnnxPcbDetectionEngine reports NOT_LOADED and fails real inspection cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = OnnxPcbDetectionEngine(context)
        assertEquals(ModelRuntimeState.NOT_LOADED, engine.modelInfo.value.state)

        val outcome = engine.detect(
            PcbImageInput(
                sourceUri = "file:///tmp/board.jpg",
                sourceType = PcbImageSource.CAMERA,
                boardName = "Real Camera Board",
                validation = ImageValidationResult(
                    isValid = true,
                    isProcessable = true,
                    width = 1200,
                    height = 900,
                    clarityScore = 180.0,
                    lowClarityRisk = false
                )
            )
        )
        assertTrue(outcome is DetectionOutcome.Failure)
        assertEquals(DetectionErrorType.MODEL_NOT_LOADED, (outcome as DetectionOutcome.Failure).errorType)
    }

    @Test
    fun `onnx input validation accepts dynamic batch (-1, 3, 640, 640)`() {
        OnnxContractValidator.validateInputSignature(
            "images",
            OnnxJavaType.FLOAT,
            longArrayOf(-1L, 3L, 640L, 640L)
        )
    }

    @Test
    fun `onnx input validation rejects dynamic channel dimension (1, -1, 640, 640)`() {
        val result = runCatching {
            OnnxContractValidator.validateInputSignature(
                "images",
                OnnxJavaType.FLOAT,
                longArrayOf(1L, -1L, 640L, 640L)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `onnx input validation rejects dynamic height dimension (1, 3, -1, 640)`() {
        val result = runCatching {
            OnnxContractValidator.validateInputSignature(
                "images",
                OnnxJavaType.FLOAT,
                longArrayOf(1L, 3L, -1L, 640L)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `onnx input validation rejects dynamic width dimension (1, 3, 640, -1)`() {
        val result = runCatching {
            OnnxContractValidator.validateInputSignature(
                "images",
                OnnxJavaType.FLOAT,
                longArrayOf(1L, 3L, 640L, -1L)
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `border guard uses floating-point thresholds at 7_9 vs 8_0 vs 8_1 px, 2_49 vs 2_50 vs 2_51 pct, and 0_499 vs 0_500 vs 0_501 conf`() {
        // 8.0px border threshold on 640x640 with small 40x40 (~0.39%) low-conf (0.42) box
        assertFalse(decodeSingleCandidate(x1 = 7.9f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))
        assertFalse(decodeSingleCandidate(x1 = 8.0f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))
        assertTrue(decodeSingleCandidate(x1 = 8.1f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))

        // Right-edge check (w0 - 8 = 632): x2=632.1 (>=632) ignored, x2=632.0 ignored, x2=631.9 (<632) kept
        assertFalse(decodeSingleCandidate(x1 = 592.1f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))
        assertFalse(decodeSingleCandidate(x1 = 592.0f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))
        assertTrue(decodeSingleCandidate(x1 = 591.9f, y1 = 100f, width = 40f, height = 40f, conf = 0.42f))

        // 2.50% area threshold on 640x640 (frameArea = 409600; 100 * 102.4 = 10240 = exact 2.50%)
        // Border-touching x1=4f, conf=0.45f (<0.50): <2.50% ignored, >=2.50% kept
        assertFalse(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 101.9904f, height = 100f, conf = 0.45f)) // 2.49%
        assertTrue(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 102.4f, height = 100f, conf = 0.45f))    // 2.50%
        assertTrue(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 102.8096f, height = 100f, conf = 0.45f)) // 2.51%

        // 0.500 confidence threshold on border-touching small box (x1=4f, 40x40 = 0.39%)
        assertFalse(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 40f, height = 40f, conf = 0.499f))
        assertTrue(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 40f, height = 40f, conf = 0.500f))
        assertTrue(decodeSingleCandidate(x1 = 4f, y1 = 100f, width = 40f, height = 40f, conf = 0.501f))
    }

    @Test
    fun `toTruncatedRoiPixelBounds matches Python int() truncation toward zero`() {
        val cand = RawDetectionCandidate(
            classIndex = 9,
            confidence = 0.85f,
            x1Px = 8.9f,
            y1Px = 15.99f,
            x2Px = 100.8f,
            y2Px = 200.4f
        )
        val roi = YoloOutputDecoder.toTruncatedRoiPixelBounds(cand, maxWidth = 640, maxHeight = 640)
        assertEquals(listOf(8, 15, 100, 200), roi.toList())
    }

    @Test
    fun `opencv RGB2HSV_b LUT matches exact reference vectors and refinement decision boundaries`() {
        // Exact OpenCV RGB2HSV_b reference vectors (H in 0..179, S in 0..255, V in 0..255)
        val vectors = listOf(
            intArrayOf(0, 0, 0) to Triple(0, 0, 0),
            intArrayOf(255, 255, 255) to Triple(0, 0, 255),
            intArrayOf(22, 110, 52) to Triple(70, 204, 110),
            // Fixed-point LUT edge case: continuous hue = 95.45 (float->95), OpenCV RGB2HSV_b = 96
            intArrayOf(0, 162, 198) to Triple(96, 255, 198),
            intArrayOf(221, 255, 0) to Triple(34, 255, 255),
            intArrayOf(213, 255, 0) to Triple(35, 255, 255),
            intArrayOf(0, 213, 255) to Triple(95, 255, 255),
            intArrayOf(0, 204, 255) to Triple(96, 255, 255),
            intArrayOf(216, 255, 216) to Triple(60, 39, 255),
            intArrayOf(215, 255, 215) to Triple(60, 40, 255),
            intArrayOf(64, 64, 64) to Triple(0, 0, 64),
            intArrayOf(65, 65, 65) to Triple(0, 0, 65),
            intArrayOf(165, 165, 165) to Triple(0, 0, 165),
            intArrayOf(166, 166, 166) to Triple(0, 0, 166),
            intArrayOf(142, 200, 142) to Triple(60, 74, 200),
            intArrayOf(141, 200, 141) to Triple(60, 75, 200),
            intArrayOf(255, 0, 128) to Triple(165, 255, 255)
        )

        for ((rgb, expected) in vectors) {
            val packed = GreenPcbDefectRefiner.rgbToOpenCvHsvPacked(rgb[0], rgb[1], rgb[2])
            val h = (packed ushr 16) and 0xFF
            val s = (packed ushr 8) and 0xFF
            val v = packed and 0xFF
            assertEquals("RGB(${rgb.joinToString()}) H/S/V mismatch", expected, Triple(h, s, v))
        }

        // Green board 15% cutoff (100 pixels: 14 green -> false, 15 green -> true)
        val greenPx = Color.rgb(213, 255, 0)   // H=35, S=255
        val nonGreenPx = Color.rgb(221, 255, 0) // H=34, S=255
        assertFalse(GreenPcbDefectRefiner.isGreenSolderMaskBoard(IntArray(100) { if (it < 14) greenPx else nonGreenPx }))
        assertTrue(GreenPcbDefectRefiner.isGreenSolderMaskBoard(IntArray(100) { if (it < 15) greenPx else nonGreenPx }))

        // HSV remapping disabled: ROI pixel refinement retains class 9 across all pixel distributions
        val v64 = Color.rgb(64, 64, 64)
        val v65 = Color.rgb(65, 65, 65)
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { if (it < 15) v64 else v65 }, 9))
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { if (it < 16) v64 else v65 }, 9))
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { v65 }, 9))

        val solderOk = Color.rgb(142, 200, 142)     // V=200, S=74 (<75)
        val solderTooSat = Color.rgb(141, 200, 141) // V=200, S=75 (not <75)
        val solderTooDark = Color.rgb(165, 165, 165) // V=165 (not >165)
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { if (it < 24) solderOk else solderTooSat }, 9))
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { if (it < 25) solderOk else solderTooSat }, 9))
        assertEquals(9, GreenPcbDefectRefiner.refineRoiPixels(IntArray(100) { solderTooDark }, 9))
    }

    @Test
    fun `preprocessing matches Python reference on unscaled padded image and stays within tight bounds on scaled image`() {
        // 1) Unscaled 640x320 gradient -> zero resize error vs Python reference
        val unscaled = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 320) {
                for (x in 0 until 640) {
                    setPixel(x, y, Color.rgb((x * 255) / 639, (y * 255) / 319, ((x + y) % 256)))
                }
            }
        }
        val unscaledTensor = YoloPreprocessor.prepareNchwFloatInput(unscaled, 640, 640)
        assertFalse("prepareNchwFloatInput must not recycle caller bitmap", unscaled.isRecycled)
        val plane = 640 * 640
        var maxTensorErr = 0f
        var sumTensorErr = 0.0
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val idx = y * 640 + x
                val (er, eg, eb) = if (y < 160 || y >= 480) {
                    Triple(114f / 255f, 114f / 255f, 114f / 255f)
                } else {
                    val sy = y - 160
                    Triple(((x * 255) / 639) / 255f, ((sy * 255) / 319) / 255f, ((x + sy) % 256) / 255f)
                }
                val dr = kotlin.math.abs(unscaledTensor.floatBuffer.get(idx) - er)
                val dg = kotlin.math.abs(unscaledTensor.floatBuffer.get(plane + idx) - eg)
                val db = kotlin.math.abs(unscaledTensor.floatBuffer.get(2 * plane + idx) - eb)
                maxTensorErr = maxOf(maxTensorErr, dr, dg, db)
                sumTensorErr += dr + dg + db
            }
        }
        val unscaledMeanErr = sumTensorErr / (3.0 * plane)
        assertEquals(0f, maxTensorErr, 1e-6f)
        assertEquals(0.0, unscaledMeanErr, 1e-7)
        println("PREPROC_UNSCALED maxTensorErr=$maxTensorErr maxPixelErr=${maxTensorErr * 255f} meanTensorErr=$unscaledMeanErr meanPixelErr=${unscaledMeanErr * 255.0}")

        // 2) Scaled 1000x561 smooth gradient -> check dimensions, padding RGB 114, and INTER_LINEAR comparison
        val srcW = 1000
        val srcH = 561
        val scaledSrc = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888).apply {
            val row = IntArray(srcW)
            for (y in 0 until srcH) {
                val g = (y * 255) / (srcH - 1)
                for (x in 0 until srcW) {
                    row[x] = Color.rgb((x * 255) / (srcW - 1), g, ((x + y) * 127) / (srcW + srcH))
                }
                setPixels(row, 0, srcW, 0, y, srcW, 1)
            }
        }
        val scaledTensor = YoloPreprocessor.prepareNchwFloatInput(scaledSrc, 640, 640)
        assertFalse("prepareNchwFloatInput must not recycle scaled caller bitmap", scaledSrc.isRecycled)
        assertEquals(640, scaledTensor.transform.scaledWidth)
        assertEquals(359, scaledTensor.transform.scaledHeight)
        assertEquals(140, scaledTensor.transform.padTop)
        assertEquals(141, scaledTensor.transform.padBottom)

        // Exact padding checks (top rows 0..139 and bottom rows 499..639 == 114/255)
        val padF = 114f / 255f
        assertEquals(padF, scaledTensor.floatBuffer.get(139 * 640 + 123), 1e-6f)
        assertEquals(padF, scaledTensor.floatBuffer.get(plane + 499 * 640 + 456), 1e-6f)
        assertEquals(padF, scaledTensor.floatBuffer.get(2 * plane + 639 * 640 + 320), 1e-6f)

        val (maxGradErr, meanGradErr) = measureOpenCvInterLinearDiff(scaledSrc, scaledTensor, srcW, srcH, 640, 359, 140)
        println("PREPROC_SCALED_GRADIENT maxTensorErr=$maxGradErr maxPixelErr=${maxGradErr * 255f} meanTensorErr=$meanGradErr meanPixelErr=${meanGradErr * 255.0}")
        assertTrue("Expected tight smooth-gradient mean error < 0.01, was $meanGradErr", meanGradErr < 0.01)
        assertTrue("Expected bounded max smooth-gradient error < 0.02, was $maxGradErr", maxGradErr < 0.02f)

        // 3) Scaled 1000x561 sharp 2px checkerboard PCB-edge pattern -> quantify worst-case high-frequency edge divergence
        val edgeSrc = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888).apply {
            val row = IntArray(srcW)
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    val on = ((x / 2 + y / 2) % 2 == 0)
                    row[x] = if (on) Color.rgb(22, 110, 52) else Color.rgb(210, 185, 110)
                }
                setPixels(row, 0, srcW, 0, y, srcW, 1)
            }
        }
        val edgeTensor = YoloPreprocessor.prepareNchwFloatInput(edgeSrc, 640, 640)
        val (maxEdgeErr, meanEdgeErr) = measureOpenCvInterLinearDiff(edgeSrc, edgeTensor, srcW, srcH, 640, 359, 140)
        println("PREPROC_SCALED_EDGE maxTensorErr=$maxEdgeErr maxPixelErr=${maxEdgeErr * 255f} meanTensorErr=$meanEdgeErr meanPixelErr=${meanEdgeErr * 255.0}")
        assertTrue("Expected finite bounded edge error", maxEdgeErr <= 1.0f && meanEdgeErr < 0.5)
    }

    private fun measureOpenCvInterLinearDiff(
        srcBitmap: Bitmap,
        tensor: YoloTensorInput,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        padTop: Int
    ): Pair<Float, Double> {
        val plane = 640 * 640
        val fx = srcW.toDouble() / dstW.toDouble()
        val fy = srcH.toDouble() / dstH.toDouble()
        var maxErr = 0f
        var sumErr = 0.0
        var count = 0
        for (dy in 0 until dstH) {
            val sy = ((dy + 0.5) * fy - 0.5).coerceIn(0.0, (srcH - 1).toDouble())
            val y0 = kotlin.math.floor(sy).toInt()
            val y1 = minOf(y0 + 1, srcH - 1)
            val wy = sy - y0
            for (dx in 0 until dstW) {
                val sx = ((dx + 0.5) * fx - 0.5).coerceIn(0.0, (srcW - 1).toDouble())
                val x0 = kotlin.math.floor(sx).toInt()
                val x1 = minOf(x0 + 1, srcW - 1)
                val wx = sx - x0

                val p00 = srcBitmap.getPixel(x0, y0)
                val p10 = srcBitmap.getPixel(x1, y0)
                val p01 = srcBitmap.getPixel(x0, y1)
                val p11 = srcBitmap.getPixel(x1, y1)

                val outY = padTop + dy
                val idx = outY * 640 + dx
                for (ch in 0..2) {
                    val shift = (2 - ch) * 8
                    val c00 = (p00 ushr shift) and 0xFF
                    val c10 = (p10 ushr shift) and 0xFF
                    val c01 = (p01 ushr shift) and 0xFF
                    val c11 = (p11 ushr shift) and 0xFF
                    val interp = ((c00 * (1 - wx) + c10 * wx) * (1 - wy) +
                        (c01 * (1 - wx) + c11 * wx) * wy) / 255.0
                    val actual = tensor.floatBuffer.get(ch * plane + idx)
                    val err = kotlin.math.abs(actual - interp.toFloat())
                    if (err > maxErr) maxErr = err
                    sumErr += err
                    count++
                }
            }
        }
        return maxErr to (sumErr / count)
    }

    @Test
    fun `bitmap lifecycle keeps source bitmap unrecycled through preprocessing and decoding`() {
        val board = createGreenBoardBitmap(800, 600).apply {
            paintRect(200, 150, 400, 350, Color.rgb(20, 20, 20))
        }
        val tensorInput = YoloPreprocessor.prepareNchwFloatInput(board, 640, 640)
        assertFalse("Preprocessing must never recycle source bitmap", board.isRecycled)

        val numBoxes = 1
        val data = FloatArray(15 * numBoxes).apply {
            this[0] = 240f
            this[1] = 200f
            this[2] = 120f
            this[3] = 120f
            this[4 + 9] = 0.78f
        }
        val decoded = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 1L),
            classLabels = DefectTaxonomy.expectedDatasetLabelsByIndex,
            transform = tensorInput.transform,
            sourceBitmap = board
        )
        assertTrue(decoded is YoloDecodeResult.Success)
        val singleDet = (decoded as YoloDecodeResult.Success).detections.single()
        assertEquals(9, singleDet.classIndex)
        assertEquals("Scratch", singleDet.canonicalClassName)
        assertFalse("Decoder must not recycle caller-owned source bitmap", board.isRecycled)
    }

    @Test
    fun `metadata parser handles valid Python dict and JSON array and rejects duplicate, missing, extra, misordered, or malformed metadata`() {
        val validPyDict = "{0: 'open', 1: 'short', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch', 10: 'copper'}"
        val validJsonArray = """["open", "short", "mouse_bite", "spur", "spurious_copper", "missing_copper", "copper_burr", "pin_hole", "hole_breakout", "scratch", "copper"]"""

        assertEquals(DefectTaxonomy.expectedDatasetLabelsByIndex, OnnxContractValidator.parseMetadataClassNames(validPyDict))
        assertEquals(DefectTaxonomy.expectedDatasetLabelsByIndex, OnnxContractValidator.parseMetadataClassNames(validJsonArray))
        assertTrue(
            runCatching {
                OnnxContractValidator.validateResolvedContract("output0", OnnxJavaType.FLOAT, longArrayOf(1L, 15L, 8400L), mapOf("names" to validJsonArray))
            }.isSuccess
        )

        // Duplicate class indices
        val duplicateIndices = "{0: 'open', 0: 'short', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch', 10: 'copper'}"
        assertTrue(OnnxContractValidator.parseMetadataClassNames(duplicateIndices) == null)
        assertTrue(rejectMetadata(duplicateIndices))

        // Missing index (gap at 1) and truncated list (only 10 classes)
        val gappedIndices = "{0: 'open', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch', 10: 'copper'}"
        assertTrue(OnnxContractValidator.parseMetadataClassNames(gappedIndices) == null)
        assertTrue(rejectMetadata(gappedIndices))

        val missingLastIndex = "{0: 'open', 1: 'short', 2: 'mouse_bite', 3: 'spur', 4: 'spurious_copper', " +
            "5: 'missing_copper', 6: 'copper_burr', 7: 'pin_hole', 8: 'hole_breakout', 9: 'scratch'}"
        assertTrue(rejectMetadata(missingLastIndex))

        // Extra index (12 classes)
        val extraIndices = """["open", "short", "mouse_bite", "spur", "spurious_copper", "missing_copper", "copper_burr", "pin_hole", "hole_breakout", "scratch", "copper", "extra_defect"]"""
        assertTrue(rejectMetadata(extraIndices))

        // Incorrect ordering
        val misordered = """["open", "mouse_bite", "short", "spur", "spurious_copper", "missing_copper", "copper_burr", "pin_hole", "hole_breakout", "scratch", "copper"]"""
        assertTrue(rejectMetadata(misordered))

        // Malformed metadata strings
        val malformedSamples = listOf(
            "{}",
            "[]",
            "open, short, mouse_bite",
            "{0: 'open', bad_token}",
            "['open', 123, 'short']",
            "{0: ''}",
            "[\"open\", \"\"]",
            "[0: 'open']"
        )
        for (sample in malformedSamples) {
            assertTrue("Malformed sample '$sample' must fail parsing", OnnxContractValidator.parseMetadataClassNames(sample) == null)
            assertTrue("Malformed sample '$sample' must be rejected", rejectMetadata(sample))
        }
    }

    private fun decodeSingleCandidate(
        x1: Float,
        y1: Float,
        width: Float,
        height: Float,
        conf: Float
    ): Boolean {
        val labels = DefectTaxonomy.expectedDatasetLabelsByIndex
        val data = FloatArray(15).apply {
            this[0] = x1 + width / 2f
            this[1] = y1 + height / 2f
            this[2] = width
            this[3] = height
            this[4 + 2] = conf
        }
        val transform = YoloPreprocessor.computeLetterboxTransform(640, 640, 640, 640)
        val result = YoloOutputDecoder.decode(
            flatData = data,
            shape = longArrayOf(1L, 15L, 1L),
            classLabels = labels,
            transform = transform
        ) as YoloDecodeResult.Success
        return result.detections.isNotEmpty()
    }

    private fun rejectMetadata(rawNames: String): Boolean {
        return runCatching {
            OnnxContractValidator.validateResolvedContract(
                outputName = "output0",
                outputType = OnnxJavaType.FLOAT,
                resolvedOutputShape = longArrayOf(1L, 15L, 8400L),
                customMetadata = mapOf("names" to rawNames)
            )
        }.isFailure
    }

    private fun createGreenBoardBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(22, 110, 52))
        }
    }

    private fun Bitmap.paintRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val pixels = IntArray((x2 - x1) * (y2 - y1)) { color }
        setPixels(pixels, 0, x2 - x1, x1, y1, x2 - x1, y2 - y1)
    }
}
