package com.example.domain.model

import kotlinx.serialization.Serializable

enum class InspectionDisposition {
    PASS,
    REVIEW,
    REJECT,
    INCOMPLETE
}

enum class DefectSeverity {
    CRITICAL,
    MEDIUM,
    LOW,
    MANUAL_REVIEW,
    REQUIRES_DATASET_VERIFICATION
}

enum class PcbImageSource {
    CAMERA,
    GALLERY,
    SAMPLE
}

enum class ModelRuntimeState {
    NOT_LOADED,
    LOADING,
    READY,
    ERROR,
    UNLOADED
}

enum class InspectionTarget {
    PCB_BOARD,
    TRANSISTOR
}

enum class ModelSlot {
    PCB_DETECTION,
    AI_ADVISORY,
    TRANSISTOR_ANOMALY
}

enum class AdvisoryAvailability {
    AVAILABLE,
    UNAVAILABLE,
    NOT_REQUESTED
}

enum class DetectionErrorType {
    MODEL_NOT_LOADED,
    MODEL_UNLOADED,
    SESSION_INIT_FAILED,
    INVALID_INPUT_TENSOR,
    RUNTIME_EXECUTION_FAILED,
    OUTPUT_SHAPE_MISMATCH,
    DECODER_FAILED,
    RESOURCE_ALLOCATION_FAILED,
    UNSUPPORTED_REAL_IMAGE_FALLBACK
}

@Serializable
data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    fun clamped(): BoundingBox {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        val cw = width.coerceIn(0f, 1f - cx)
        val ch = height.coerceIn(0f, 1f - cy)
        return BoundingBox(cx, cy, cw, ch)
    }
}

@Serializable
data class DetectedDefect(
    val defectId: String,
    val classIndex: Int,
    val rawClassName: String,
    val canonicalClassName: String,
    val confidence: Float,
    val severity: DefectSeverity,
    val requiresManualReview: Boolean,
    val boundingBox: BoundingBox?,
    val code: String? = null,
    val description: String? = null
)

@Serializable
data class InspectionPolicyConfig(
    val profileName: String = DEFAULT_PROFILE_NAME,
    val policyVersion: String = DEFAULT_POLICY_VERSION,
    val rejectOnCritical: Boolean = true,
    val reviewOnMedium: Boolean = true,
    val reviewOnLow: Boolean = false,
    val reviewOnLowClarityZeroDefects: Boolean = true,
    val minImageWidth: Int = DEFAULT_MIN_IMAGE_DIM,
    val minImageHeight: Int = DEFAULT_MIN_IMAGE_DIM
) {
    fun effectiveThresholdsSummary(): String {
        return "critical=${if (rejectOnCritical) "REJECT" else "REVIEW"}, " +
            "medium=${if (reviewOnMedium) "REVIEW" else "PASS"}, " +
            "low=${if (reviewOnLow) "REVIEW" else "PASS"}, " +
            "lowClarityGate=REVIEW(always-guarded), " +
            "minDims=${minImageWidth}x${minImageHeight}"
    }

    companion object {
        const val DEFAULT_PROFILE_NAME = "Configurable Screening Profile (IPC-A-610 Class 2 Baseline)"
        const val DEFAULT_POLICY_VERSION = "1.1.0-screening"
        const val DEFAULT_MIN_IMAGE_DIM = 224
    }
}

@Serializable
data class ModelExecutionProvenance(
    val inferenceEngine: String,
    val modelIdentity: String,
    val modelSha256: String,
    val modelContractVersion: String,
    val preprocessingVersion: String,
    val decoderVersion: String,
    val classMappingVersion: String,
    val runtimeVersion: String
) {
    companion object {
        fun unavailableProduction(runtimeVersion: String): ModelExecutionProvenance =
            ModelExecutionProvenance(
                inferenceEngine = "OnnxPcbDetectionEngine",
                modelIdentity = "NONE",
                modelSha256 = "NONE",
                modelContractVersion = "UNVERIFIED",
                preprocessingVersion = "yolo-letterbox-v1",
                decoderVersion = "yolo-nms-v1",
                classMappingVersion = "pcb-11-v1",
                runtimeVersion = runtimeVersion
            )
    }
}

@Serializable
data class InspectionLatencyMetrics(
    val imagePreprocessingMs: Long? = null,
    val yoloInferenceMs: Long? = null,
    val patchCoreInferenceMs: Long? = null,
    val outputDecodingNmsMs: Long? = null,
    val policyEvaluationMs: Long? = null,
    val llmAdvisoryMs: Long? = null,
    val totalDurationMs: Long? = null
)

@Serializable
data class TransistorAnomalyData(
    val rawAnomalyScore: Float,
    val normalizedDisplayScore: Float, // Heuristic normalized display score; not a calibrated probability.
    val isAnomalous: Boolean,
    val affectedAreaPercent: Float? = null,
    val boundingBox: BoundingBox? = null
)

@Serializable
data class AiAdvisory(
    val availability: AdvisoryAvailability,
    val isDemonstration: Boolean,
    val severityClassification: String? = null,
    val probableRootCause: String? = null,
    val recommendedOperatorAction: String? = null,
    val relevantStandard: String? = null,
    val suggestedDisposition: InspectionDisposition? = null,
    val limitations: String? = null,
    val unavailableReason: String? = null,
    val advisoryLatencyMs: Long? = null,
    val provenanceSummary: String? = null
)

data class ImageValidationResult(
    val isValid: Boolean,
    val isProcessable: Boolean,
    val width: Int,
    val height: Int,
    val clarityScore: Double?,
    val lowClarityRisk: Boolean,
    val orientationDegrees: Int = 0,
    val errorMessage: String? = null
)

data class PcbImageInput(
    val sourceUri: String,
    val sourceType: PcbImageSource,
    val boardName: String,
    val validation: ImageValidationResult,
    val sampleScenarioHint: String? = null
)

sealed interface DetectionOutcome {
    val latencyMetrics: InspectionLatencyMetrics
    val provenance: ModelExecutionProvenance

    data class Success(
        val detections: List<DetectedDefect>,
        override val latencyMetrics: InspectionLatencyMetrics,
        override val provenance: ModelExecutionProvenance,
        val isDemonstration: Boolean,
        val transistorAnomaly: TransistorAnomalyData? = null
    ) : DetectionOutcome

    data class Failure(
        val errorType: DetectionErrorType,
        val errorMessage: String,
        override val latencyMetrics: InspectionLatencyMetrics,
        override val provenance: ModelExecutionProvenance
    ) : DetectionOutcome
}

data class PolicyEvaluationResult(
    val officialDisposition: InspectionDisposition,
    val rationale: String,
    val evaluatedDefects: List<DetectedDefect>,
    val policyProfile: String,
    val policyVersion: String,
    val configSnapshot: InspectionPolicyConfig,
    val effectiveThresholdsSummary: String
)

data class ModelInfo(
    val slot: ModelSlot,
    val displayName: String,
    val runtimeLabel: String,
    val state: ModelRuntimeState,
    val artifactPath: String? = null,
    val tokenizerPath: String? = null,
    val sha256: String? = null,
    val contractVersion: String? = null,
    val runtimeVersion: String? = null,
    val detailMessage: String,
    val isDemonstrationOnly: Boolean = false
)

data class SamplePcbPreset(
    val id: String,
    val boardName: String,
    val subtitle: String,
    val width: Int,
    val height: Int,
    val orientationLabel: String,
    val scenarioHint: String,
    val drawableName: String
)

data class InspectionRecord(
    val inspectionId: String,
    val timestampEpochMs: Long,
    val boardName: String,
    val sourceType: PcbImageSource,
    val imageUri: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val lowClarityRisk: Boolean,
    val clarityScore: Double?,
    val officialDisposition: InspectionDisposition,
    val policyProfile: String,
    val policyVersion: String,
    val policyConfigSnapshot: InspectionPolicyConfig,
    val effectiveThresholdsSummary: String,
    val policyRationale: String,
    val defects: List<DetectedDefect>,
    val totalDefects: Int,
    val isDemonstration: Boolean,
    val provenance: ModelExecutionProvenance,
    val advisory: AiAdvisory,
    val latencyMetrics: InspectionLatencyMetrics,
    val target: InspectionTarget = InspectionTarget.PCB_BOARD,
    val transistorAnomaly: TransistorAnomalyData? = null,
    val completedEpochMs: Long? = null
) {
    val modelInferenceLatencyMs: Long?
        get() = when (target) {
            InspectionTarget.PCB_BOARD -> latencyMetrics.yoloInferenceMs
            InspectionTarget.TRANSISTOR -> latencyMetrics.patchCoreInferenceMs
        }
}
