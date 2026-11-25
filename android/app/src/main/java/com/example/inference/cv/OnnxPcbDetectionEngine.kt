package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.domain.model.PcbImageInput
import com.example.domain.policy.DefectTaxonomy
import com.example.imageprocessing.SafeImageDecoder
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production YOLOv8n ONNX Runtime detector.
 *
 * Inspects actual model tensor metadata and metadata class labels dynamically at load time,
 * runs smoke inference before marking READY, and deterministically frees native tensors and sessions.
 */
class OnnxPcbDetectionEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : PcbDetectionEngine {

    override val engineLabel: String = "OnnxPcbDetectionEngine (YOLOv8n / ONNX Runtime)"
    override val requiresBitmap: Boolean = true
    override val isDemonstrationEngine: Boolean = false

    private val sessionMutex = Mutex()
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var activeContract: InspectedOnnxContract? = null

    private val _modelInfo = MutableStateFlow(initialNotLoadedInfo())
    override val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    data class InspectedOnnxContract(
        val inputName: String,
        val outputName: String,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputShape: LongArray,
        val classLabels: List<String>,
        val hasEmbeddedClassMetadata: Boolean,
        val sha256: String,
        val fileName: String,
        val contractVersion: String
    )

    override suspend fun initialize(): ModelOperationResult {
        val currentPath = _modelInfo.value.artifactPath
        if (currentPath.isNullOrBlank()) {
            return ModelOperationResult(false, "No ONNX model artifact path configured.")
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
                        "Model artifact stored (${validFile.name}); tap Load to inspect and initialize ONNX Runtime."
                    } else {
                        "Not loaded. Import a YOLOv8n .onnx file in Settings to inspect real camera/gallery boards."
                    }
                )
            }
        }
    }

    suspend fun validateAndStageCandidate(candidateFile: File): Result<InspectedOnnxContract> =
        withContext(ioDispatcher) {
            runCatching {
                require(candidateFile.exists() && candidateFile.canRead() && candidateFile.length() > 0L) {
                    "ONNX file is missing or unreadable."
                }
                val sha256 = computeSha256(candidateFile)
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                options.use { opts ->
                    env.createSession(candidateFile.absolutePath, opts).use { testSession ->
                        val contract = inspectAndSmokeTestSession(env, testSession, candidateFile.name, sha256)
                        contract
                    }
                }
            }
        }

    suspend fun loadFromModelFile(modelFile: File): ModelOperationResult =
        sessionMutex.withLock {
            withContext(ioDispatcher) {
                _modelInfo.value = _modelInfo.value.copy(
                    state = ModelRuntimeState.LOADING,
                    detailMessage = "Inspecting ONNX metadata and initializing session..."
                )
                try {
                    require(modelFile.exists() && modelFile.canRead() && modelFile.length() > 0L) {
                        "ONNX model artifact missing at ${modelFile.absolutePath}"
                    }
                    val sha256 = computeSha256(modelFile)
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

                    // Close prior active session only after replacement smoke test succeeds
                    runCatching { ortSession?.close() }
                    ortEnvironment = env
                    ortSession = newSession
                    activeContract = contract

                    _modelInfo.value = ModelInfo(
                        slot = ModelSlot.PCB_DETECTION,
                        displayName = "YOLOv8n PCB Detector (${modelFile.name})",
                        runtimeLabel = "ONNX Runtime Android $ORT_RUNTIME_VERSION",
                        state = ModelRuntimeState.READY,
                        artifactPath = modelFile.absolutePath,
                        sha256 = sha256,
                        contractVersion = contract.contractVersion,
                        runtimeVersion = ORT_RUNTIME_VERSION,
                        detailMessage = "Ready • input=[1,3,${contract.inputHeight},${contract.inputWidth}] • output=${contract.outputShape.contentToString()} • ${contract.classLabels.size} classes",
                        isDemonstrationOnly = false
                    )
                    ModelOperationResult(true, "ONNX session initialized and smoke-tested (${contract.inputWidth}x${contract.inputHeight}).")
                } catch (e: Throwable) {
                    val existingReady = ortSession != null && activeContract != null
                    if (!existingReady) {
                        _modelInfo.value = _modelInfo.value.copy(
                            state = ModelRuntimeState.ERROR,
                            detailMessage = "ONNX initialization failed: ${e.message ?: "unknown error"}"
                        )
                    }
                    ModelOperationResult(false, "ONNX load failed: ${e.message ?: "unknown error"}")
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
                        errorMessage = "Production ONNX model is ${_modelInfo.value.state.name}. Import and load a YOLOv8n .onnx model in Settings.",
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
                        errorMessage = "Failed to decode PCB image for ONNX tensor preprocessing.",
                        latencyMetrics = InspectionLatencyMetrics(),
                        provenance = currentProvenance(contract)
                    )

                    return@withContext try {
                        val tensorInput = YoloPreprocessor.prepareNchwFloatInput(
                            bitmap = decoded.bitmap,
                            targetWidth = contract.inputWidth,
                            targetHeight = contract.inputHeight
                        )
                        preprocessMs = (System.currentTimeMillis() - prepStart).coerceAtLeast(1L)

                        val inferStart = System.currentTimeMillis()
                        var flatOutput: FloatArray? = null
                        var outputShape: LongArray? = null

                        OnnxTensor.createTensor(env, tensorInput.floatBuffer, tensorInput.shape).use { inputTensor ->
                            session.run(mapOf(contract.inputName to inputTensor)).use { results ->
                                inferenceMs = (System.currentTimeMillis() - inferStart).coerceAtLeast(1L)
                                val outValue = results.get(contract.outputName).orElse(null)
                                    ?: results.get(0)
                                val outTensor = outValue as? OnnxTensor
                                    ?: return@withContext DetectionOutcome.Failure(
                                        errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                        errorMessage = "Primary ONNX output is not a tensor.",
                                        latencyMetrics = InspectionLatencyMetrics(
                                            imagePreprocessingMs = preprocessMs,
                                            yoloInferenceMs = inferenceMs
                                        ),
                                        provenance = currentProvenance(contract)
                                    )
                                val info = outTensor.info
                                outputShape = info.shape
                                val buffer = outTensor.floatBuffer
                                val floats = FloatArray(buffer.remaining())
                                buffer.get(floats)
                                flatOutput = floats
                            }
                        }

                        val decodeStart = System.currentTimeMillis()
                        val decodedResult = YoloOutputDecoder.decode(
                            flatData = flatOutput!!,
                            shape = outputShape!!,
                            classLabels = contract.classLabels,
                            transform = tensorInput.transform,
                            sourceBitmap = decoded.bitmap,
                            confidenceThreshold = YoloOutputDecoder.DEFAULT_CONFIDENCE_THRESHOLD,
                            iouThreshold = YoloOutputDecoder.DEFAULT_IOU_THRESHOLD
                        )
                        decodeMs = (System.currentTimeMillis() - decodeStart).coerceAtLeast(1L)

                        when (decodedResult) {
                            is YoloDecodeResult.Success -> {
                                DetectionOutcome.Success(
                                    detections = decodedResult.detections,
                                    latencyMetrics = InspectionLatencyMetrics(
                                        imagePreprocessingMs = preprocessMs,
                                        yoloInferenceMs = inferenceMs,
                                        outputDecodingNmsMs = decodeMs,
                                        totalDurationMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                                    ),
                                    provenance = currentProvenance(contract),
                                    isDemonstration = false
                                )
                            }
                            is YoloDecodeResult.UnsupportedShape -> {
                                DetectionOutcome.Failure(
                                    errorType = DetectionErrorType.OUTPUT_SHAPE_MISMATCH,
                                    errorMessage = decodedResult.message,
                                    latencyMetrics = InspectionLatencyMetrics(
                                        imagePreprocessingMs = preprocessMs,
                                        yoloInferenceMs = inferenceMs,
                                        outputDecodingNmsMs = decodeMs
                                    ),
                                    provenance = currentProvenance(contract)
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
                        errorMessage = "Out of memory during ONNX inference: ${e.message}",
                        latencyMetrics = InspectionLatencyMetrics(
                            imagePreprocessingMs = preprocessMs,
                            yoloInferenceMs = inferenceMs,
                            outputDecodingNmsMs = decodeMs
                        ),
                        provenance = currentProvenance(contract)
                    )
                } catch (e: Throwable) {
                    return@withContext DetectionOutcome.Failure(
                        errorType = DetectionErrorType.RUNTIME_EXECUTION_FAILED,
                        errorMessage = "ONNX execution failed: ${e.message ?: "unknown runtime error"}",
                        latencyMetrics = InspectionLatencyMetrics(
                            imagePreprocessingMs = preprocessMs,
                            yoloInferenceMs = inferenceMs,
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
                        "Model artifact stored (${File(current.artifactPath).name}), session unloaded to conserve memory."
                    } else {
                        "No YOLOv8n ONNX model imported."
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
        return buildString {
            appendLine("Slot: PCB Defect Detection (YOLOv8n)")
            appendLine("State: ${info.state.name}")
            appendLine("Runtime: ${info.runtimeLabel}")
            appendLine("Artifact: ${info.artifactPath ?: "None"}")
            appendLine("SHA-256: ${info.sha256 ?: "N/A"}")
            if (contract != null) {
                appendLine("Input Node: ${contract.inputName} [1, 3, ${contract.inputHeight}, ${contract.inputWidth}]")
                appendLine("Output Node: ${contract.outputName} ${contract.outputShape.contentToString()}")
                val sourceNote = if (contract.hasEmbeddedClassMetadata) "embedded ONNX metadata" else "spec 11-class binding (no embedded 'names' key)"
                appendLine("Classes (${contract.classLabels.size}, $sourceNote): ${contract.classLabels.joinToString(", ")}")
                appendLine("Contract Version: ${contract.contractVersion}")
            }
        }
    }

    private fun inspectAndSmokeTestSession(
        env: OrtEnvironment,
        session: OrtSession,
        fileName: String,
        sha256: String
    ): InspectedOnnxContract {
        val inputEntry = session.inputInfo.entries.firstOrNull()
            ?: error("ONNX model has no input nodes.")
        val inputName = inputEntry.key
        val inputInfo = inputEntry.value.info as? TensorInfo
            ?: error("Primary ONNX input '$inputName' is not a tensor.")
        OnnxContractValidator.validateInputSignature(
            inputName = inputName,
            inputType = inputInfo.type,
            inputShape = inputInfo.shape
        )

        val inputH = OnnxContractValidator.EXPECTED_INPUT_SIZE
        val inputW = OnnxContractValidator.EXPECTED_INPUT_SIZE

        val outputEntry = session.outputInfo.entries.firstOrNull()
            ?: error("ONNX model has no output nodes.")
        val outputName = outputEntry.key
        val outputInfo = outputEntry.value.info as? TensorInfo
            ?: error("Primary ONNX output '$outputName' is not a tensor.")
        require(outputInfo.type == OnnxJavaType.FLOAT) {
            "Unsupported output tensor dtype ${outputInfo.type}; expected FLOAT (FP32)."
        }

        // Run a real smoke tensor pass so dynamic batch shapes resolve and execution is proven
        val smokeBuffer = FloatBuffer.allocate(3 * inputH * inputW)
        val smokeShape = longArrayOf(1L, 3L, inputH.toLong(), inputW.toLong())
        var resolvedOutputShape = outputInfo.shape
        var resolvedOutputType = outputInfo.type

        OnnxTensor.createTensor(env, smokeBuffer, smokeShape).use { smokeTensor ->
            session.run(mapOf(inputName to smokeTensor)).use { smokeResult ->
                val outTensor = smokeResult.get(outputName).orElse(null) as? OnnxTensor
                    ?: error("Smoke inference failed to return a valid OnnxTensor.")
                resolvedOutputShape = outTensor.info.shape
                resolvedOutputType = outTensor.info.type
            }
        }

        val customMetadata = session.metadata?.customMetadata ?: emptyMap()
        val validatedContract = OnnxContractValidator.validateResolvedContract(
            outputName = outputName,
            outputType = resolvedOutputType,
            resolvedOutputShape = resolvedOutputShape,
            customMetadata = customMetadata
        )

        return InspectedOnnxContract(
            inputName = inputName,
            outputName = outputName,
            inputWidth = validatedContract.inputWidth,
            inputHeight = validatedContract.inputHeight,
            outputShape = resolvedOutputShape,
            classLabels = validatedContract.classLabels,
            hasEmbeddedClassMetadata = validatedContract.hasEmbeddedClassMetadata,
            sha256 = sha256,
            fileName = fileName,
            contractVersion = validatedContract.contractVersion
        )
    }

    private fun currentProvenance(contract: InspectedOnnxContract?): ModelExecutionProvenance {
        return ModelExecutionProvenance(
            inferenceEngine = "OnnxPcbDetectionEngine",
            modelIdentity = contract?.fileName ?: "NONE",
            modelSha256 = contract?.sha256 ?: "NONE",
            modelContractVersion = contract?.contractVersion ?: "UNVERIFIED",
            preprocessingVersion = YoloPreprocessor.PREPROCESSING_VERSION,
            decoderVersion = YoloOutputDecoder.DECODER_VERSION,
            classMappingVersion = DefectTaxonomy.CLASS_MAPPING_VERSION,
            runtimeVersion = ORT_RUNTIME_VERSION
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun initialNotLoadedInfo(): ModelInfo = ModelInfo(
        slot = ModelSlot.PCB_DETECTION,
        displayName = "YOLOv8n PCB Detector",
        runtimeLabel = "ONNX Runtime Android $ORT_RUNTIME_VERSION",
        state = ModelRuntimeState.NOT_LOADED,
        runtimeVersion = ORT_RUNTIME_VERSION,
        detailMessage = "Not loaded. Import a YOLOv8n .onnx file in Settings to inspect real camera/gallery boards.",
        isDemonstrationOnly = false
    )

    companion object {
        const val ORT_RUNTIME_VERSION = "1.22.0"
    }
}
