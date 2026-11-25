package com.example.domain.usecase

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.AiAdvisory
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.PcbImageInput
import com.example.domain.model.PcbImageSource
import com.example.domain.policy.InspectionPolicyEngine
import com.example.domain.repository.ImageStorageManager
import com.example.domain.repository.InspectionCsvLogger
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.SettingsRepository
import com.example.inference.cv.PatchCoreContractValidator
import com.example.inference.cv.PatchCoreOutputDecoder
import com.example.inference.cv.PatchCorePreprocessor
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.cv.PcbDetectionEngine
import com.example.inference.demo.DemoAdvisoryEngine
import com.example.inference.demo.DemoPcbDetectionEngine
import com.example.inference.llm.AdvisoryEngine
import com.example.inference.llm.AdvisoryRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RunInspectionUseCase(
    private val imageStorageManager: ImageStorageManager,
    private val policyEngine: InspectionPolicyEngine,
    private val productionDetectionEngine: PcbDetectionEngine,
    private val demoDetectionEngine: DemoPcbDetectionEngine,
    private val patchCoreEngine: PatchCoreTransistorEngine,
    private val productionAdvisoryEngine: AdvisoryEngine,
    private val demoAdvisoryEngine: DemoAdvisoryEngine,
    private val inspectionRepository: InspectionRepository,
    private val settingsRepository: SettingsRepository,
    private val csvLogger: InspectionCsvLogger,
    private val applicationScope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    suspend fun execute(
        imageUriString: String,
        sourceType: PcbImageSource,
        boardName: String,
        sampleScenarioHint: String? = null,
        target: InspectionTarget = InspectionTarget.PCB_BOARD,
        onStageProgress: (String) -> Unit = {}
    ): InspectionRecord = withContext(defaultDispatcher) {
        val totalStartMs = System.currentTimeMillis()
        val policyConfig: InspectionPolicyConfig = settingsRepository.policyConfig.first()

        val opticalFrameLabel = if (target == InspectionTarget.TRANSISTOR) "component" else "PCB"
        onStageProgress("Validating $opticalFrameLabel optical frame")
        val validation = imageStorageManager.validateAndAssessClarity(
            imageUriString = imageUriString,
            minWidth = policyConfig.minImageWidth,
            minHeight = policyConfig.minImageHeight
        )

        val input = PcbImageInput(
            sourceUri = imageUriString,
            sourceType = sourceType,
            boardName = boardName.ifBlank {
                if (target == InspectionTarget.TRANSISTOR) "Component Sample" else "PCB Assembly"
            },
            validation = validation,
            sampleScenarioHint = if (sourceType == PcbImageSource.SAMPLE) sampleScenarioHint else null
        )

        val isSampleDemo = sourceType == PcbImageSource.SAMPLE
        onStageProgress(
            when {
                isSampleDemo -> "Running demonstration PCB simulation"
                target == InspectionTarget.TRANSISTOR -> "Running on-device PatchCore unsupervised anomaly detection"
                else -> "Running on-device YOLOv8n detection"
            }
        )

        val detectionOutcome: DetectionOutcome = if (!validation.isProcessable || !validation.isValid) {
            DetectionOutcome.Failure(
                errorType = DetectionErrorType.INVALID_INPUT_TENSOR,
                errorMessage = validation.errorMessage ?: "Image is not processable.",
                latencyMetrics = InspectionLatencyMetrics(),
                provenance = if (isSampleDemo) {
                    ModelExecutionProvenance(
                        inferenceEngine = demoDetectionEngine.engineLabel,
                        modelIdentity = "prototype-sample-simulator",
                        modelSha256 = "DEMO-SAMPLE-ONLY",
                        modelContractVersion = "demo-sample-v1",
                        preprocessingVersion = "demo-noop-v1",
                        decoderVersion = "demo-preset-v1",
                        classMappingVersion = "pcb-11-v1",
                        runtimeVersion = "demo-1.0"
                    )
                } else if (target == InspectionTarget.TRANSISTOR) {
                    ModelExecutionProvenance(
                        inferenceEngine = patchCoreEngine.engineLabel,
                        modelIdentity = "NONE",
                        modelSha256 = "NONE",
                        modelContractVersion = PatchCoreContractValidator.CONTRACT_VERSION,
                        preprocessingVersion = PatchCorePreprocessor.PREPROCESSING_VERSION,
                        decoderVersion = PatchCoreOutputDecoder.DECODER_VERSION,
                        classMappingVersion = "pcb-anomaly-v1",
                        runtimeVersion = "ONNX Runtime Android 1.22.0"
                    )
                } else {
                    ModelExecutionProvenance.unavailableProduction("uninitialized")
                }
            )
        } else if (isSampleDemo) {
            if (target == InspectionTarget.TRANSISTOR) {
                // Sample demonstration presets are not supported for anomaly inspection; SAMPLE must never reach production PatchCore
                DetectionOutcome.Failure(
                    errorType = DetectionErrorType.UNSUPPORTED_REAL_IMAGE_FALLBACK,
                    errorMessage = "Sample demonstration is not supported for anomaly inspection. Use Camera or Gallery with an imported PatchCore model.",
                    latencyMetrics = InspectionLatencyMetrics(),
                    provenance = ModelExecutionProvenance(
                        inferenceEngine = "DemoTransistorUnsupported",
                        modelIdentity = "NONE",
                        modelSha256 = "NONE",
                        modelContractVersion = "NONE",
                        preprocessingVersion = "NONE",
                        decoderVersion = "NONE",
                        classMappingVersion = "NONE",
                        runtimeVersion = "demo-1.0"
                    )
                )
            } else {
                // Sample presets explicitly execute through the demonstration engine
                demoDetectionEngine.detect(input)
            }
        } else {
            // Real CAMERA/GALLERY inputs MUST route according to target; never fall back silently
            when (target) {
                InspectionTarget.PCB_BOARD -> productionDetectionEngine.detect(input)
                InspectionTarget.TRANSISTOR -> patchCoreEngine.detect(input)
            }
        }

        onStageProgress("Evaluating deterministic screening policy")
        val policyStartMs = System.currentTimeMillis()
        val policyResult = policyEngine.evaluate(
            imageValidation = validation,
            detectionOutcome = detectionOutcome,
            config = policyConfig
        )
        val policyEvalMs = (System.currentTimeMillis() - policyStartMs).coerceAtLeast(1L)

        // Generate optional advisory (never overrides policy, never fails inspection)
        onStageProgress("Generating AI technical advisory")
        val advisoryStartMs = System.currentTimeMillis()
        val advisory: AiAdvisory = if (policyResult.officialDisposition == InspectionDisposition.INCOMPLETE && !isSampleDemo) {
            AiAdvisory(
                availability = AdvisoryAvailability.UNAVAILABLE,
                isDemonstration = false,
                unavailableReason = "Technical advisory skipped because inspection is INCOMPLETE."
            )
        } else {
            val engineToUse: AdvisoryEngine = if (isSampleDemo) demoAdvisoryEngine else productionAdvisoryEngine
            val verifiedExcerpts = com.example.domain.policy.PcbStandardsKnowledgeBase.getVerifiedReferences(
                defects = policyResult.evaluatedDefects,
                target = target
            )
            val request = AdvisoryRequest(
                inspectionId = "pending",
                boardName = input.boardName,
                officialDisposition = policyResult.officialDisposition,
                policyRationale = policyResult.rationale,
                defects = policyResult.evaluatedDefects,
                imageWidth = validation.width,
                imageHeight = validation.height,
                lowClarityRisk = validation.lowClarityRisk,
                isDemonstration = isSampleDemo,
                verifiedReferenceExcerpts = verifiedExcerpts
            )
            runCatching { engineToUse.generateAdvisory(request) }.getOrElse { err ->
                AiAdvisory(
                    availability = AdvisoryAvailability.UNAVAILABLE,
                    isDemonstration = isSampleDemo,
                    unavailableReason = "Advisory failed safely: ${err.message ?: "unknown error"}"
                )
            }
        }
        val advisoryLatencyMs = advisory.advisoryLatencyMs
            ?: if (advisory.availability == AdvisoryAvailability.AVAILABLE) {
                (System.currentTimeMillis() - advisoryStartMs).coerceAtLeast(1L)
            } else {
                null
            }

        val totalMs = (System.currentTimeMillis() - totalStartMs).coerceAtLeast(1L)
        val combinedLatency = InspectionLatencyMetrics(
            imagePreprocessingMs = detectionOutcome.latencyMetrics.imagePreprocessingMs,
            yoloInferenceMs = detectionOutcome.latencyMetrics.yoloInferenceMs,
            patchCoreInferenceMs = detectionOutcome.latencyMetrics.patchCoreInferenceMs,
            outputDecodingNmsMs = detectionOutcome.latencyMetrics.outputDecodingNmsMs,
            policyEvaluationMs = policyEvalMs,
            llmAdvisoryMs = advisoryLatencyMs,
            totalDurationMs = totalMs
        )

        val inspectionId = generateInspectionId(target)
        val transistorAnomaly = (detectionOutcome as? DetectionOutcome.Success)?.transistorAnomaly
        val record = InspectionRecord(
            inspectionId = inspectionId,
            timestampEpochMs = System.currentTimeMillis(),
            boardName = input.boardName,
            sourceType = sourceType,
            imageUri = imageUriString,
            imageWidth = validation.width,
            imageHeight = validation.height,
            lowClarityRisk = validation.lowClarityRisk,
            clarityScore = validation.clarityScore,
            officialDisposition = policyResult.officialDisposition,
            policyProfile = policyResult.policyProfile,
            policyVersion = policyResult.policyVersion,
            policyConfigSnapshot = policyResult.configSnapshot,
            effectiveThresholdsSummary = policyResult.effectiveThresholdsSummary,
            policyRationale = policyResult.rationale,
            defects = policyResult.evaluatedDefects,
            totalDefects = policyResult.evaluatedDefects.size,
            isDemonstration = isSampleDemo,
            provenance = detectionOutcome.provenance,
            advisory = advisory,
            latencyMetrics = combinedLatency,
            target = target,
            transistorAnomaly = transistorAnomaly,
            completedEpochMs = System.currentTimeMillis()
        )

        onStageProgress("Saving inspection record")
        inspectionRepository.saveInspectionAtomically(record)

        // Dispatch CSV audit outbox non-blockingly; CSV failure never fails inspection completion
        applicationScope.launch(defaultDispatcher) {
            runCatching { csvLogger.processPendingOutboxEvents() }
        }

        record
    }

    private fun generateInspectionId(target: InspectionTarget = InspectionTarget.PCB_BOARD): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(4).uppercase(Locale.US)
        val prefix = if (target == InspectionTarget.TRANSISTOR) "TR" else "PCB"
        return "$prefix-$stamp-$suffix"
    }
}
