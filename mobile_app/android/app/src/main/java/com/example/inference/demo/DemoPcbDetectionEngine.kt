package com.example.inference.demo

import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.domain.model.PcbImageInput
import com.example.domain.model.PcbImageSource
import com.example.domain.policy.DefectTaxonomy
import com.example.inference.cv.ModelOperationResult
import com.example.inference.cv.PcbDetectionEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit demonstration detector used EXCLUSIVELY for prototype sample presets.
 *
 * Refuses to execute on CAMERA or GALLERY inputs so production inference can never
 * silently fall back to demonstration results.
 */
class DemoPcbDetectionEngine : PcbDetectionEngine {

    override val engineLabel: String = "DemoPcbDetectionEngine (Prototype Samples Only)"
    override val requiresBitmap: Boolean = false
    override val isDemonstrationEngine: Boolean = true

    private val _modelInfo = MutableStateFlow(
        ModelInfo(
            slot = ModelSlot.PCB_DETECTION,
            displayName = "Prototype Sample Simulator",
            runtimeLabel = "Simulated Sample Pipeline",
            state = ModelRuntimeState.READY,
            sha256 = "DEMO-SAMPLE-ONLY",
            contractVersion = "demo-sample-v1",
            runtimeVersion = "demo-1.0",
            detailMessage = "Active for bundled prototype sample boards only; real camera/gallery frames require an imported ONNX model.",
            isDemonstrationOnly = true
        )
    )
    override val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    private val demoProvenance = ModelExecutionProvenance(
        inferenceEngine = "DemoPcbDetectionEngine",
        modelIdentity = "prototype-sample-simulator",
        modelSha256 = "DEMO-SAMPLE-ONLY",
        modelContractVersion = "demo-sample-v1",
        preprocessingVersion = "demo-noop-v1",
        decoderVersion = "demo-preset-v1",
        classMappingVersion = DefectTaxonomy.CLASS_MAPPING_VERSION,
        runtimeVersion = "demo-1.0"
    )

    override suspend fun initialize(): ModelOperationResult {
        return ModelOperationResult(true, "Demonstration sample simulator ready.")
    }

    override suspend fun detect(input: PcbImageInput): DetectionOutcome {
        if (input.sourceType != PcbImageSource.SAMPLE) {
            return DetectionOutcome.Failure(
                errorType = DetectionErrorType.UNSUPPORTED_REAL_IMAGE_FALLBACK,
                errorMessage = "Refused demonstration fallback on ${input.sourceType.name} image. Import and load a production YOLOv8n ONNX model in Settings.",
                latencyMetrics = InspectionLatencyMetrics(),
                provenance = demoProvenance
            )
        }

        val startMs = System.currentTimeMillis()
        delay(120)

        val detections = when (input.sampleScenarioHint) {
            SamplePcbCatalog.SCENARIO_MULTI_DEFECT -> listOf(
                demoDefect(
                    id = "demo-qfp-1",
                    classIndex = 1,
                    rawName = "short",
                    confidence = 0.92f,
                    box = BoundingBox(0.34f, 0.34f, 0.06f, 0.08f)
                ),
                demoDefect(
                    id = "demo-qfp-2",
                    classIndex = 5,
                    rawName = "missing_copper",
                    confidence = 0.88f,
                    box = BoundingBox(0.83f, 0.42f, 0.07f, 0.07f)
                ),
                demoDefect(
                    id = "demo-qfp-3",
                    classIndex = 3,
                    rawName = "spur",
                    confidence = 0.81f,
                    box = BoundingBox(0.63f, 0.72f, 0.08f, 0.08f)
                )
            )
            SamplePcbCatalog.SCENARIO_CLEAN_BOARD -> emptyList()
            SamplePcbCatalog.SCENARIO_MOUSE_BITE -> listOf(
                demoDefect(
                    id = "demo-ctrl-1",
                    classIndex = 2,
                    rawName = "mouse_bite",
                    confidence = 0.87f,
                    box = BoundingBox(0.10f, 0.61f, 0.08f, 0.12f)
                ),
                demoDefect(
                    id = "demo-ctrl-2",
                    classIndex = 6,
                    rawName = "copper_burr",
                    confidence = 0.76f,
                    box = BoundingBox(0.68f, 0.75f, 0.12f, 0.09f)
                )
            )
            else -> emptyList()
        }

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        return DetectionOutcome.Success(
            detections = detections,
            latencyMetrics = InspectionLatencyMetrics(
                imagePreprocessingMs = null,
                yoloInferenceMs = null,
                outputDecodingNmsMs = null,
                totalDurationMs = elapsed
            ),
            provenance = demoProvenance,
            isDemonstration = true
        )
    }

    private fun demoDefect(
        id: String,
        classIndex: Int,
        rawName: String,
        confidence: Float,
        box: BoundingBox
    ): DetectedDefect {
        val taxonomy = DefectTaxonomy.resolve(rawName)
        return DetectedDefect(
            defectId = id,
            classIndex = classIndex,
            rawClassName = rawName,
            canonicalClassName = taxonomy.canonicalLabel,
            confidence = confidence,
            severity = taxonomy.defaultSeverity,
            requiresManualReview = taxonomy.requiresManualReview,
            boundingBox = box,
            code = taxonomy.code,
            description = taxonomy.description
        )
    }

    override suspend fun unload() = Unit
    override suspend fun close() = Unit
}
