package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
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
import com.example.domain.model.TransistorAnomalyData
import com.example.imageprocessing.SafeImageDecoder
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production PatchCore Anomaly Detection Engine (ONNX Runtime).
 *
 * Implements unsupervised anomaly detection:
 * - Input: [2, 3, 256, 256] FP32 NCHW (strict static batch size 2).
 * - Output: extracts pred_score[0] and anomaly_map[0, 0].
 * - Decodes results via PatchCoreOutputDecoder into deterministic PASS / REVIEW classifications.
 */
class PatchCoreTransistorEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : PcbDetectionEngine {

    override val engineLabel: String = "PatchCoreTransistorEngine (PatchCore / ONNX Runtime)"
    override val requiresBitmap: Boolean = true
    override val isDemonstrationEngine: Boolean = false

    private val sessionMutex = Mutex()
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var activeContract: InspectedPatchCoreContract? = null

    private val _modelInfo = MutableStateFlow(initialNotLoadedInfo())
    override val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    data class InspectedPatchCoreContract(
        val inputName: String,
        val inputBatchSize: Long,
        val inputChannels: Long,
        val inputHeight: Int,
        val inputWidth: Int,
        val predScoreOutputName: String,
        val anomalyMapOutputName: String,
        val sha256: String,
        val fileName: String,
        val contractVersion: String
    )

    override suspend fun initialize(): ModelOperationResult {
        val currentPath = _modelInfo.value.artifactPath
        if (currentPath.isNullOrBlank()) {
            return ModelOperationResult(false, "No PatchCore ONNX model artifact path configured.")
        }
        return loadFromModelFile(File(currentPath))
    }

    suspend fun setConfiguredArtifact(modelFile: File?) {
        sessionMutex.withLock {
            val validFile = modelFile?.takeIf { it.exists() && it.canRead() && it.length() > 0L }
            if (ortSession == null) {
                _modelInfo.value = _modelInfo.value.copy(
                    state = if (validFile != null) ModelRuntimeState.UNLOADED else ModelRuntimeState.NOT_LOADED,
                    artifactPath = validFile?.absolutePath,
                    detailMessage = if (validFile != null) {
                        "PatchCore model artifact stored (${displayArtifactName(validFile)}); tap Load to inspect and initialize ONNX Runtime."
                    } else {
                        "Not loaded. Import active_patchcore.onnx in Settings to enable visual anomaly inspection."
                    }
                )
            }
        }
    }

    suspend fun validateAndStageCandidate(candidateFile: File): Result<InspectedPatchCoreContract> =
        withContext(ioDispatcher) {
            runCatching {
                require(candidateFile.exists() && candidateFile.canRead() && candidateFile.length() > 0L) {
                    "PatchCore ONNX file is missing or unreadable."
                }
                val sha256 = computeSha256(candidateFile)
                require(sha256.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                    "SHA-256 mismatch for PatchCore model. Expected $EXPECTED_MODEL_SHA256, but found $sha256."
                }
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                options.use { opts ->
                    env.createSession(candidateFile.absolutePath, opts).use { testSession ->
                        inspectAndSmokeTestSession(env, testSession, candidateFile.name, sha256)
                    }
                }
            }
        }

    suspend fun loadFromModelFile(modelFile: File): ModelOperationResult =
        sessionMutex.withLock {
            withContext(ioDispatcher) {
                _modelInfo.value = _modelInfo.value.copy(
                    state = ModelRuntimeState.LOADING,
                    detailMessage = "Inspecting PatchCore ONNX contract and initializing session..."
                )
                try {
                    require(modelFile.exists() && modelFile.canRead() && modelFile.length() > 0L) {
                        "PatchCore model artifact missing or unreadable at ${modelFile.absolutePath}"
                    }
                    val sha256 = computeSha256(modelFile)
                    require(sha256.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                        "SHA-256 mismatch for PatchCore model. Expected $EXPECTED_MODEL_SHA256, but found $sha256."
                    }

                    val env = OrtEnvironment.getEnvironment()
                    val options = OrtSession.SessionOptions()
                    val newSession = try {
                        env.createSession(modelFile.absolutePath, options)
                    } finally {
                        options.close()
                    }

                    val contract = try {
                        inspectAndSmokeTestSession(env, newSession, modelFile.name, sha256)
                    } catch (e: Throwable) {
                        runCatching { newSession.close() }
                        throw e
                    }

                    // Close prior active session safely after replacement smoke test succeeds
                    runCatching { ortSession?.close() }
                    ortEnvironment = env
                    ortSession = newSession
                    activeContract = contract

                    val displayArtifact = displayArtifactName(modelFile)
                    _modelInfo.value = ModelInfo(
                        slot = ModelSlot.TRANSISTOR_ANOMALY,
                        displayName = "PatchCore Anomaly Detector ($displayArtifact)",
                        runtimeLabel = "ONNX Runtime Android $ORT_RUNTIME_VERSION",
                        state = ModelRuntimeState.READY,
                        artifactPath = modelFile.absolutePath,
                        sha256 = sha256,
                        contractVersion = contract.contractVersion,
                        runtimeVersion = ORT_RUNTIME_VERSION,
                        detailMessage = "Ready • input=[${contract.inputBatchSize},${contract.inputChannels},${contract.inputHeight},${contract.inputWidth}] • outputs=[${contract.predScoreOutputName}, ${contract.anomalyMapOutputName}] • SHA-256 verified",
                        isDemonstrationOnly = false
                    )
                    ModelOperationResult(true, "PatchCore model session initialized and smoke-tested (${contract.inputWidth}x${contract.inputHeight}).")
                } catch (e: Throwable) {
                    val existingReady = ortSession != null && activeContract != null
                    if (!existingReady) {
                        _modelInfo.value = _modelInfo.value.copy(
                            state = ModelRuntimeState.ERROR,
                            detailMessage = "PatchCore initialization failed: ${e.message ?: "unknown error"}"
                        )
                    }
                    ModelOperationResult(false, "PatchCore load failed: ${e.message ?: "unknown error"}")
                }
            }
        }

    override suspend fun detect(input: PcbImageInput): DetectionOutcome =
        sessionMutex.withLock {
            withContext(ioDispatcher) {
                val contract = activeContract
                val session = ortSession
                val env = ortEnvironment
                if (session == null || env == null || contract == null || _modelInfo.value.state != ModelRuntimeState.READY) {
                    val errorType = if (_modelInfo.value.state == ModelRuntimeState.UNLOADED) {
                        DetectionErrorType.MODEL_UNLOADED
                    } else {
                        DetectionErrorType.MODEL_NOT_LOADED
                    }
                    return@withContext DetectionOutcome.Failure(
                        errorType = errorType,
                        errorMessage = "PatchCore model is ${_modelInfo.value.state.name}. Import and load active_patchcore.onnx in Settings.",
                        latencyMetrics = InspectionLatencyMetrics(),
                        provenance = currentProvenance(contract)
                    )
                }

                val totalStart = System.currentTimeMillis()
                var preprocessMs: Long? = null
                var inferenceMs: Long? = null
                var decodeMs: Long? = null

                try {
                    val prepStart = System.currentTimeMillis()
                    val uri = Uri.parse(input.sourceUri)
                    val decoded = SafeImageDecoder.decodeBoundedOrientedBitmap(maxEdgePx = 1600) {
                        context.contentResolver.openInputStream(uri)
                    } ?: return@withContext DetectionOutcome.Failure(
                        errorType = DetectionErrorType.INVALID_INPUT_TENSOR,
                        errorMessage = "Failed to decode image for PatchCore preprocessing.",
                        latencyMetrics = InspectionLatencyMetrics(),
                        provenance = currentProvenance(contract)
                    )

                    return@withContext try {
                        val tensorInput = PatchCorePreprocessor.prepareNchwFloatInput(decoded.bitmap)
                        preprocessMs = (System.currentTimeMillis() - prepStart).coerceAtLeast(1L)

                        val inferStart = System.currentTimeMillis()
                        val predScores = FloatArray(PatchCoreOutputDecoder.EXACT_SCORE_SIZE)
                        val anomalyMap = FloatArray(PatchCoreOutputDecoder.EXACT_MAP_SIZE)

                        OnnxTensor.createTensor(env, tensorInput.floatBuffer, tensorInput.shape).use { inputTensor ->
                            session.run(mapOf(contract.inputName to inputTensor)).use { results ->
                                inferenceMs = (System.currentTimeMillis() - inferStart).coerceAtLeast(1L)

                                val scoreValue = results.get(contract.predScoreOutputName).orElse(null)
                                    ?: return@withContext DetectionOutcome.Failure(
                                        errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                        errorMessage = "Primary output '${contract.predScoreOutputName}' missing from inference results.",
                                        latencyMetrics = InspectionLatencyMetrics(
                                            imagePreprocessingMs = preprocessMs,
                                            patchCoreInferenceMs = inferenceMs
                                        ),
                                        provenance = currentProvenance(contract)
                                    )
                                val scoreTensor = scoreValue as? OnnxTensor
                                    ?: return@withContext DetectionOutcome.Failure(
                                        errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                        errorMessage = "Output '${contract.predScoreOutputName}' is not an OnnxTensor.",
                                        latencyMetrics = InspectionLatencyMetrics(
                                            imagePreprocessingMs = preprocessMs,
                                            patchCoreInferenceMs = inferenceMs
                                        ),
                                        provenance = currentProvenance(contract)
                                    )
                                val scoreBuffer = scoreTensor.floatBuffer
                                scoreBuffer.get(predScores)

                                val mapValue = results.get(contract.anomalyMapOutputName).orElse(null)
                                    ?: return@withContext DetectionOutcome.Failure(
                                        errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                        errorMessage = "Primary output '${contract.anomalyMapOutputName}' missing from inference results.",
                                        latencyMetrics = InspectionLatencyMetrics(
                                            imagePreprocessingMs = preprocessMs,
                                            patchCoreInferenceMs = inferenceMs
                                        ),
                                        provenance = currentProvenance(contract)
                                    )
                                val mapTensor = mapValue as? OnnxTensor
                                    ?: return@withContext DetectionOutcome.Failure(
                                        errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                        errorMessage = "Output '${contract.anomalyMapOutputName}' is not an OnnxTensor.",
                                        latencyMetrics = InspectionLatencyMetrics(
                                            imagePreprocessingMs = preprocessMs,
                                            patchCoreInferenceMs = inferenceMs
                                        ),
                                        provenance = currentProvenance(contract)
                                    )
                                val mapBuffer = mapTensor.floatBuffer
                                mapBuffer.get(anomalyMap)
                            }
                        }

                        val decodeStart = System.currentTimeMillis()
                        val decodeResult = PatchCoreOutputDecoder.decode(
                            predScores = predScores,
                            anomalyMap = anomalyMap,
                            originalWidth = decoded.bitmap.width,
                            originalHeight = decoded.bitmap.height
                        )
                        decodeMs = (System.currentTimeMillis() - decodeStart).coerceAtLeast(1L)

                        when (decodeResult) {
                            is PatchCoreDecodeResult.InvalidInferenceOutput -> {
                                DetectionOutcome.Failure(
                                    errorType = DetectionErrorType.DECODER_FAILED,
                                    errorMessage = decodeResult.reason,
                                    latencyMetrics = InspectionLatencyMetrics(
                                        imagePreprocessingMs = preprocessMs,
                                        patchCoreInferenceMs = inferenceMs,
                                        outputDecodingNmsMs = decodeMs
                                    ),
                                    provenance = currentProvenance(contract)
                                )
                            }
                            is PatchCoreDecodeResult.Success -> {
                                val normBox = decodeResult.boundingBox?.let { box ->
                                    BoundingBox(
                                        x = box.x1.toFloat() / decoded.bitmap.width.toFloat(),
                                        y = box.y1.toFloat() / decoded.bitmap.height.toFloat(),
                                        width = box.width.toFloat() / decoded.bitmap.width.toFloat(),
                                        height = box.height.toFloat() / decoded.bitmap.height.toFloat()
                                    ).clamped()
                                }

                                val anomalyData = TransistorAnomalyData(
                                    rawAnomalyScore = decodeResult.rawAnomalyScore,
                                    normalizedDisplayScore = decodeResult.displayScore,
                                    isAnomalous = decodeResult.isAnomalous,
                                    affectedAreaPercent = if (decodeResult.isAnomalous) decodeResult.areaPct else null,
                                    boundingBox = normBox
                                )

                                val detections = if (decodeResult.isAnomalous) {
                                    val defect = DetectedDefect(
                                        defectId = "DEF-TR-${UUID.randomUUID().toString().take(6).uppercase(Locale.US)}",
                                        classIndex = 0,
                                        rawClassName = "transistor_anomaly",
                                        canonicalClassName = "Transistor Anomaly",
                                        confidence = decodeResult.displayScore,
                                        severity = DefectSeverity.MANUAL_REVIEW,
                                        requiresManualReview = true,
                                        boundingBox = normBox,
                                        code = "TR-ANOMALY-REVIEW",
                                        description = "Unsupervised PatchCore anomaly detection flagged surface visual irregularity (score: ${"%.2f".format(Locale.US, decodeResult.rawAnomalyScore)} >= 39.5)."
                                    )
                                    listOf(defect)
                                } else {
                                    emptyList()
                                }

                                DetectionOutcome.Success(
                                    detections = detections,
                                    latencyMetrics = InspectionLatencyMetrics(
                                        imagePreprocessingMs = preprocessMs,
                                        patchCoreInferenceMs = inferenceMs,
                                        outputDecodingNmsMs = decodeMs,
                                        totalDurationMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                                    ),
                                    provenance = currentProvenance(contract),
                                    isDemonstration = false,
                                    transistorAnomaly = anomalyData
                                )
                            }
                        }
                    } finally {
                        if (!decoded.bitmap.isRecycled) {
                            decoded.bitmap.recycle()
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    return@withContext DetectionOutcome.Failure(
                        errorType = DetectionErrorType.RESOURCE_ALLOCATION_FAILED,
                        errorMessage = "Out of memory during PatchCore inference: ${e.message}",
                        latencyMetrics = InspectionLatencyMetrics(
                            imagePreprocessingMs = preprocessMs,
                            patchCoreInferenceMs = inferenceMs,
                            outputDecodingNmsMs = decodeMs
                        ),
                        provenance = currentProvenance(contract)
                    )
                } catch (e: Throwable) {
                    return@withContext DetectionOutcome.Failure(
                        errorType = DetectionErrorType.RUNTIME_EXECUTION_FAILED,
                        errorMessage = "PatchCore execution failed: ${e.message ?: "unknown runtime error"}",
                        latencyMetrics = InspectionLatencyMetrics(
                            imagePreprocessingMs = preprocessMs,
                            patchCoreInferenceMs = inferenceMs,
                            outputDecodingNmsMs = decodeMs
                        ),
                        provenance = currentProvenance(contract)
                    )
                }
            }
        }

    override suspend fun unload() {
        sessionMutex.withLock {
            withContext(ioDispatcher) {
                runCatching { ortSession?.close() }
                ortSession = null
                activeContract = null
                val current = _modelInfo.value
                _modelInfo.value = current.copy(
                    state = if (current.artifactPath != null) ModelRuntimeState.UNLOADED else ModelRuntimeState.NOT_LOADED,
                    detailMessage = if (current.artifactPath != null) {
                        "Model artifact stored (${displayArtifactName(File(current.artifactPath))}), session unloaded to conserve memory."
                    } else {
                        "No PatchCore ONNX model imported."
                    }
                )
            }
        }
    }

    override suspend fun close() {
        unload()
    }

    fun inspectCurrentMetadataText(): String {
        val info = _modelInfo.value
        val contract = activeContract
        val displayArtifact = info.artifactPath?.let { path ->
            displayArtifactName(File(path))
        } ?: "None"
        return buildString {
            appendLine("Slot: Unsupervised Anomaly Detection (PatchCore)")
            appendLine("Approach: Unsupervised anomaly detection")
            appendLine("Output: Anomaly score and spatial anomaly localization")
            appendLine("Purpose: Identify unusual visual patterns and potentially defective regions")
            appendLine("Detection: Feature-based comparison against learned normal patterns")
            appendLine("State: ${info.state.name}")
            appendLine("Runtime: ${info.runtimeLabel}")
            appendLine("Artifact: $displayArtifact")
            appendLine("SHA-256: ${info.sha256 ?: "N/A"}")
            if (contract != null) {
                appendLine("Input Node: ${contract.inputName} [${contract.inputBatchSize}, ${contract.inputChannels}, ${contract.inputHeight}, ${contract.inputWidth}]")
                appendLine("Outputs: ${contract.predScoreOutputName} [2], ${contract.anomalyMapOutputName} [2, 1, 256, 256]")
                appendLine("Contract Version: ${contract.contractVersion}")
            }
            appendLine("Decoder: ${PatchCoreOutputDecoder.DECODER_VERSION}")
            appendLine("Class Mapping: pcb-anomaly-v1")
            appendLine("Detail: ${info.detailMessage}")
        }
    }

    private fun inspectAndSmokeTestSession(
        env: OrtEnvironment,
        session: OrtSession,
        fileName: String,
        sha256: String
    ): InspectedPatchCoreContract {
        val inputInfoMap = session.inputInfo
        require(inputInfoMap.isNotEmpty()) { "PatchCore model has no input nodes." }
        val inputName = inputInfoMap.keys.first()
        val inputNodeInfo = inputInfoMap.getValue(inputName)
        val inputTensorInfo = inputNodeInfo.info as? TensorInfo
            ?: error("Model input '$inputName' is not a TensorInfo.")

        PatchCoreContractValidator.validateInputSignature(
            inputName = inputName,
            inputType = inputTensorInfo.type,
            inputShape = inputTensorInfo.shape
        )

        val outputInfoMap = session.outputInfo
        val outputTypes = mutableMapOf<String, OnnxJavaType>()
        val outputShapes = mutableMapOf<String, LongArray>()
        for ((outName, outNodeInfo) in outputInfoMap) {
            val tInfo = outNodeInfo.info as? TensorInfo
            if (tInfo != null) {
                outputTypes[outName] = tInfo.type
                outputShapes[outName] = tInfo.shape
            }
        }

        val validationResult = PatchCoreContractValidator.validateOutputSignatures(
            outputTypes = outputTypes,
            outputShapes = outputShapes
        )

        // Smoke test with zero float buffer [2, 3, 256, 256]
        val totalFloats = (PatchCoreContractValidator.EXPECTED_BATCH_SIZE *
            PatchCoreContractValidator.EXPECTED_INPUT_CHANNELS *
            PatchCoreContractValidator.EXPECTED_INPUT_HEIGHT *
            PatchCoreContractValidator.EXPECTED_INPUT_WIDTH).toInt()
        val dummyBuffer = FloatBuffer.allocate(totalFloats)
        val dummyShape = longArrayOf(
            PatchCoreContractValidator.EXPECTED_BATCH_SIZE,
            PatchCoreContractValidator.EXPECTED_INPUT_CHANNELS,
            PatchCoreContractValidator.EXPECTED_INPUT_HEIGHT.toLong(),
            PatchCoreContractValidator.EXPECTED_INPUT_WIDTH.toLong()
        )

        OnnxTensor.createTensor(env, dummyBuffer, dummyShape).use { dummyTensor ->
            session.run(mapOf(validationResult.inputName to dummyTensor)).use { results ->
                val scoreValue = results.get(validationResult.predScoreOutputName).orElse(null)
                    ?: error("Smoke test: missing ${validationResult.predScoreOutputName}")
                val scoreTensor = scoreValue as? OnnxTensor
                    ?: error("Smoke test: ${validationResult.predScoreOutputName} is not an OnnxTensor")
                require(scoreTensor.info.shape.contentEquals(longArrayOf(PatchCoreContractValidator.EXPECTED_BATCH_SIZE))) {
                    "Smoke test: score shape mismatch"
                }

                val mapValue = results.get(validationResult.anomalyMapOutputName).orElse(null)
                    ?: error("Smoke test: missing ${validationResult.anomalyMapOutputName}")
                val mapTensor = mapValue as? OnnxTensor
                    ?: error("Smoke test: ${validationResult.anomalyMapOutputName} is not an OnnxTensor")
                require(
                    mapTensor.info.shape.contentEquals(
                        longArrayOf(
                            PatchCoreContractValidator.EXPECTED_BATCH_SIZE,
                            1L,
                            PatchCoreContractValidator.EXPECTED_INPUT_HEIGHT.toLong(),
                            PatchCoreContractValidator.EXPECTED_INPUT_WIDTH.toLong()
                        )
                    )
                ) {
                    "Smoke test: map shape mismatch"
                }
            }
        }

        return InspectedPatchCoreContract(
            inputName = validationResult.inputName,
            inputBatchSize = validationResult.inputBatchSize,
            inputChannels = PatchCoreContractValidator.EXPECTED_INPUT_CHANNELS,
            inputHeight = validationResult.inputHeight,
            inputWidth = validationResult.inputWidth,
            predScoreOutputName = validationResult.predScoreOutputName,
            anomalyMapOutputName = validationResult.anomalyMapOutputName,
            sha256 = sha256,
            fileName = fileName,
            contractVersion = validationResult.contractVersion
        )
    }

    private fun currentProvenance(contract: InspectedPatchCoreContract?): ModelExecutionProvenance {
        val rawIdentity = contract?.fileName ?: "active_patchcore.onnx"
        val cleanIdentity = when (rawIdentity) {
            "active_transistor_patchcore.onnx", "patchcore_transistor_single.onnx" -> "active_patchcore.onnx"
            else -> rawIdentity
        }
        return ModelExecutionProvenance(
            inferenceEngine = "PatchCoreTransistorEngine",
            modelIdentity = cleanIdentity,
            modelSha256 = contract?.sha256 ?: "NONE",
            modelContractVersion = contract?.contractVersion ?: PatchCoreContractValidator.CONTRACT_VERSION,
            preprocessingVersion = PatchCorePreprocessor.PREPROCESSING_VERSION,
            decoderVersion = PatchCoreOutputDecoder.DECODER_VERSION,
            classMappingVersion = "pcb-anomaly-v1",
            runtimeVersion = "ONNX Runtime Android $ORT_RUNTIME_VERSION"
        )
    }

    private fun displayArtifactName(file: File): String {
        return when (file.name) {
            "active_transistor_patchcore.onnx", "patchcore_transistor_single.onnx" -> "active_patchcore.onnx"
            else -> file.name
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun initialNotLoadedInfo() = ModelInfo(
        slot = ModelSlot.TRANSISTOR_ANOMALY,
        displayName = "PatchCore Anomaly Detector",
        runtimeLabel = "ONNX Runtime Android $ORT_RUNTIME_VERSION",
        state = ModelRuntimeState.NOT_LOADED,
        detailMessage = "Not loaded. Import active_patchcore.onnx in Settings to enable visual anomaly inspection.",
        isDemonstrationOnly = false
    )

    companion object {
        const val EXPECTED_MODEL_SHA256 = "544af0939a22ce9cf2f64460f1e3e8323ec4be2c91d0d914f8c4e788c4f44e95"
        const val ORT_RUNTIME_VERSION = "1.22.0"
    }
}
