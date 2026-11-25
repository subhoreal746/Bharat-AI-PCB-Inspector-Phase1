package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.AiAdvisory
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.inference.cv.ModelOperationResult
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule

/**
 * Production on-device SmolLM2 360M Instruct advisory engine via ExecuTorch Android 1.6.0.
 *
 * Remains NOT_LOADED until compatible `.pte` and tokenizer files are imported and loaded.
 * Advisory generation is strictly read-only/explanatory and never alters policy dispositions.
 */
class ExecuTorchAdvisoryEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : AdvisoryEngine {

    override val engineLabel: String = "ExecuTorch Advisory Engine"
    override val isDemonstrationEngine: Boolean = false

    private val inferenceMutex = Mutex()
    private var llmModule: LlmModule? = null
    private var smolLm2Tokenizer: SmolLm2Tokenizer? = null
    private var activeModelPath: String? = null
    private var activeTokenizerPath: String? = null
    private var activeSha256: String? = null
    private val isGenerating = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val invocationCounter = AtomicLong(0L)

    var latestProfilingMetrics: AdvisoryProfilingMetrics = AdvisoryProfilingMetrics()
        private set
    var latestRawOutput: String? = null
        private set
    var latestCallbackCount: Int = 0
        private set
    var debugOutputDir: File? = null

    private val _modelInfo = MutableStateFlow(initialNotLoadedInfo())
    override val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    override suspend fun initialize(): ModelOperationResult {
        val mPath = activeModelPath ?: _modelInfo.value.artifactPath
        val tPath = activeTokenizerPath ?: _modelInfo.value.tokenizerPath
        if (mPath.isNullOrBlank() || tPath.isNullOrBlank()) {
            return ModelOperationResult(false, "Both .pte model and tokenizer files must be imported first.")
        }
        return loadFromArtifacts(File(mPath), File(tPath))
    }

    suspend fun setConfiguredArtifacts(modelFile: File?, tokenizerFile: File?) {
        inferenceMutex.withLock {
            val mPath = modelFile?.takeIf { it.exists() && it.length() > 0L }?.absolutePath
            val tPath = tokenizerFile?.takeIf { it.exists() && it.length() > 0L }?.absolutePath
            activeModelPath = mPath
            activeTokenizerPath = tPath
            if (llmModule == null) {
                val detail = when {
                    mPath == null && tPath == null ->
                        "Not loaded. Import ExecuTorch .pte model and tokenizer files in Settings."
                    mPath == null ->
                        "Tokenizer staged (${File(tPath!!).name}); awaiting .pte model file."
                    tPath == null ->
                        "Model staged (${File(mPath).name}); awaiting tokenizer file."
                    else ->
                        "Artifacts staged (${File(mPath).name} + ${File(tPath).name}); tap Load to initialize ExecuTorch."
                }
                val resolvedDisplayName = modelFile?.let { resolveModelDisplayName(it) } ?: "ExecuTorch Advisory Model"
                _modelInfo.value = _modelInfo.value.copy(
                    displayName = resolvedDisplayName,
                    state = ModelRuntimeState.NOT_LOADED,
                    artifactPath = mPath,
                    tokenizerPath = tPath,
                    detailMessage = detail
                )
            }
        }
    }

    suspend fun loadFromArtifacts(modelFile: File, tokenizerFile: File): ModelOperationResult =
        inferenceMutex.withLock {
            withContext(ioDispatcher) {
                _modelInfo.value = _modelInfo.value.copy(
                    state = ModelRuntimeState.LOADING,
                    detailMessage = "Validating .pte + tokenizer and loading ExecuTorch runtime..."
                )
                val baselineRss = MemoryProfiler.getProcessRssMb()
                val loadStartMs = System.currentTimeMillis()
                try {
                    // Pre-load validation
                    val preValidation = validatePreLoadArtifacts(modelFile, tokenizerFile)
                    if (!preValidation.success) {
                        SafeLog.e(TAG, "Pre-load validation failed: ${preValidation.message}")
                        if (llmModule == null) {
                            _modelInfo.value = _modelInfo.value.copy(
                                state = ModelRuntimeState.ERROR,
                                artifactPath = modelFile.absolutePath,
                                tokenizerPath = tokenizerFile.absolutePath,
                                detailMessage = preValidation.message
                            )
                        }
                        return@withContext preValidation
                    }

                    val tokenizer = if (tokenizerFile.name.endsWith(".json", ignoreCase = true)) {
                        runCatching { SmolLm2Tokenizer.load(tokenizerFile) }.getOrNull()
                    } else {
                        null
                    }

                    val sha256 = computeSha256(modelFile)
                    val modelDisplayName = resolveModelDisplayName(modelFile)
                    SafeLog.i(
                        TAG,
                        "Instantiating LlmModule for '$modelDisplayName' (${modelFile.length()} bytes, sha256=${sha256.take(12)}...)"
                    )

                    val module = LlmModule(
                        modelFile.absolutePath,
                        tokenizerFile.absolutePath,
                        0.2f
                    )
                    try {
                        module.load()
                    } catch (loadErr: Throwable) {
                        runCatching { module.close() }
                        throw loadErr
                    }

                    // Release any prior loaded module only after replacement succeeds
                    runCatching { llmModule?.resetContext() }
                    runCatching { llmModule?.close() }
                    llmModule = module
                    smolLm2Tokenizer = tokenizer
                    activeModelPath = modelFile.absolutePath
                    activeTokenizerPath = tokenizerFile.absolutePath
                    activeSha256 = sha256

                    val loadLatency = (System.currentTimeMillis() - loadStartMs).coerceAtLeast(1L)
                    val loadedRss = MemoryProfiler.getProcessRssMb()
                    latestProfilingMetrics = latestProfilingMetrics.copy(
                        baselineRssMb = baselineRss,
                        loadedRssMb = loadedRss,
                        loadLatencyMs = loadLatency
                    )
                    SafeLog.i(
                        TAG,
                        "$modelDisplayName loaded in ${loadLatency}ms. RSS: baseline=${"%.2f".format(baselineRss)}MB, loaded=${"%.2f".format(loadedRss)}MB"
                    )

                    _modelInfo.value = ModelInfo(
                        slot = ModelSlot.AI_ADVISORY,
                        displayName = modelDisplayName,
                        runtimeLabel = "ExecuTorch Android $EXECUTORCH_RUNTIME_VERSION",
                        state = ModelRuntimeState.READY,
                        artifactPath = modelFile.absolutePath,
                        tokenizerPath = tokenizerFile.absolutePath,
                        sha256 = sha256,
                        contractVersion = AdvisoryPromptBuilder.PROMPT_VERSION,
                        runtimeVersion = EXECUTORCH_RUNTIME_VERSION,
                        detailMessage = "Ready • model=${modelFile.name} • tokenizer=${tokenizerFile.name}",
                        isDemonstrationOnly = false
                    )
                    ModelOperationResult(true, "ExecuTorch LLM runtime initialized ($modelDisplayName).")
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "ExecuTorch load failed: ${e.message}", e)
                    if (llmModule == null) {
                        _modelInfo.value = _modelInfo.value.copy(
                            state = ModelRuntimeState.ERROR,
                            artifactPath = modelFile.absolutePath,
                            tokenizerPath = tokenizerFile.absolutePath,
                            detailMessage = "ExecuTorch load failed: ${e.message ?: "unknown error"}"
                        )
                    }
                    ModelOperationResult(false, "ExecuTorch load failed: ${e.message ?: "unknown error"}")
                }
            }
        }

    override suspend fun generateAdvisory(request: AdvisoryRequest): AiAdvisory =
        inferenceMutex.withLock {
            withContext(ioDispatcher) {
                val module = llmModule
                if (module == null || _modelInfo.value.state != ModelRuntimeState.READY) {
                    return@withContext AiAdvisory(
                        availability = AdvisoryAvailability.UNAVAILABLE,
                        isDemonstration = false,
                        unavailableReason = "ExecuTorch LLM is ${_modelInfo.value.state.name}. Import and load ExecuTorch .pte model + tokenizer in Settings.",
                        provenanceSummary = currentProvenanceSummary()
                    )
                }

                val invocationId = invocationCounter.incrementAndGet()
                val moduleIdentity = System.identityHashCode(module).toString(16)
                SafeLog.i(
                    TAG,
                    "[$invocationId] Starting generation: inspection='${request.inspectionId}', module=@$moduleIdentity"
                )

                // Cleanly reset KV-cache state before generation to prevent stale position accumulation
                SafeLog.i(TAG, "[$invocationId] Resetting KV-cache context via module.resetContext() before inference.")
                runCatching { module.resetContext() }.onFailure { err ->
                    SafeLog.w(TAG, "[$invocationId] Pre-generation resetContext warning: ${err.message}")
                }

                val isLlama = activeModelPath?.let { File(it) }?.let { file ->
                    file.name.contains("llama", ignoreCase = true) || file.length() == LLAMA_3_2_3B_EXPECTED_SIZE
                } ?: (_modelInfo.value.displayName.contains("llama", ignoreCase = true))

                val prompt = AdvisoryPromptBuilder.buildGroundedPrompt(request, isLlama = isLlama)
                val tokenizer = getOrLoadTokenizer()
                val promptTokenIds = tokenizer?.encode(prompt) ?: emptyList()
                val promptTokens = if (promptTokenIds.isNotEmpty()) promptTokenIds.size else (prompt.length / 3).coerceAtLeast(1)

                val budget = GenerationBudgeter.calculate(
                    promptTokens = promptTokens,
                    maxContextLen = MAX_CONTEXT_LEN,
                    maxNewTokens = MAX_NEW_TOKENS
                )

                SafeLog.i(
                    TAG,
                    "[$invocationId] Parameters (isLlama=$isLlama): promptTokens=$promptTokens, newTokensBudget=${budget.newTokensBudget}, " +
                    "totalSeqLen=${budget.totalSeqLen}, maxContext=$MAX_CONTEXT_LEN"
                )

                if (!budget.isExecutable) {
                    SafeLog.w(TAG, "[$invocationId] Generation budget rejected: ${budget.rejectionReason}")
                    return@withContext AiAdvisory(
                        availability = AdvisoryAvailability.UNAVAILABLE,
                        isDemonstration = false,
                        unavailableReason = "Advisory unavailable: ${budget.rejectionReason}",
                        provenanceSummary = currentProvenanceSummary()
                    )
                }

                val genConfig = LlmGenerationConfig.create()
                    .seqLen(budget.totalSeqLen)
                    .maxNewTokens(budget.newTokensBudget)
                    .temperature(0.15f)
                    .echo(false)
                    .build()

                val startGenMs = System.currentTimeMillis()
                var firstTokenMs = 0L
                val startRss = MemoryProfiler.getProcessRssMb()
                var peakRss = startRss
                val outputBuffer = StringBuilder()
                var callbackCount = 0
                var callbackErrorCode: Int? = null
                var callbackErrorMessage: String? = null
                val callbackTrace = mutableListOf<String>()
                val inferredTokenIds = mutableListOf<Int>()

                val earlyStopTriggered = AtomicBoolean(false)
                isCancelled.set(false)
                isGenerating.set(true)
                try {
                    module.generate(
                        prompt,
                        genConfig,
                        object : LlmCallback {
                            override fun onResult(result: String) {
                                callbackCount++
                                if (firstTokenMs == 0L) {
                                    firstTokenMs = System.currentTimeMillis()
                                }
                                if (outputBuffer.length < MAX_RAW_BUFFER_LEN) {
                                    outputBuffer.append(result)
                                }
                                val currentRss = MemoryProfiler.getProcessRssMb()
                                if (currentRss > peakRss) {
                                    peakRss = currentRss
                                }

                                if (!earlyStopTriggered.get() && shouldStopGeneration(outputBuffer)) {
                                    if (earlyStopTriggered.compareAndSet(false, true)) {
                                        SafeLog.i(TAG, "[$invocationId] Complete valid 4-field advisory JSON detected at callback #$callbackCount. Stopping native generation safely.")
                                        runCatching { module.stop() }.onFailure { stopErr ->
                                            SafeLog.w(TAG, "[$invocationId] module.stop() warning: ${stopErr.message}")
                                        }
                                    }
                                }

                                val inferredIds = if (tokenizer != null) {
                                    val singleId = tokenizer.lookupTokenId(result)
                                    if (singleId != null) listOf(singleId) else tokenizer.encode(result)
                                } else {
                                    emptyList()
                                }
                                inferredTokenIds.addAll(inferredIds)

                                val fragLen = result.length
                                val escapedFrag = escapeString(result)
                                val codePoints = toUnicodeCodePoints(result)
                                val aggLen = outputBuffer.length
                                val idStr = if (inferredIds.isNotEmpty()) inferredIds.joinToString(",") else "none"
                                val traceEntry = "Callback #$callbackCount: text=\"$escapedFrag\", len=$fragLen, codePoints=[$codePoints], aggLen=$aggLen (inferredIds=[$idStr])"
                                callbackTrace.add(traceEntry)

                                if (callbackCount <= 30 || callbackCount % 50 == 0) {
                                    SafeLog.d(TAG, "[$invocationId] $traceEntry")
                                }
                            }

                            override fun onStats(stats: String) {
                                SafeLog.d(TAG, "[$invocationId] ExecuTorch stats: $stats")
                            }

                            override fun onError(errorCode: Int, message: String) {
                                callbackErrorCode = errorCode
                                callbackErrorMessage = message
                                SafeLog.e(TAG, "[$invocationId] ExecuTorch LlmCallback.onError: code=$errorCode, message=$message")
                            }
                        }
                    )

                    val endGenMs = System.currentTimeMillis()
                    val totalLatencyMs = (endGenMs - startGenMs).coerceAtLeast(1L)
                    val prefillLatencyMs = if (firstTokenMs > 0L) (firstTokenMs - startGenMs).coerceAtLeast(1L) else totalLatencyMs
                    val decodeLatencyMs = (totalLatencyMs - prefillLatencyMs).coerceAtLeast(0L)
                    peakRss = maxOf(peakRss, MemoryProfiler.getProcessRssMb())

                    val wasCancelled = isCancelled.get()
                    val wasEarlyStopped = earlyStopTriggered.get()
                    val hasEosText = outputBuffer.contains("<|im_end|>") ||
                        outputBuffer.contains("<|endoftext|>") ||
                        outputBuffer.contains("<|eot_id|>") ||
                        outputBuffer.contains("<|end_of_text|>")
                    val terminationReason = when {
                        wasCancelled -> "EXPLICIT_CANCELLATION"
                        callbackErrorCode != null -> "ERROR_$callbackErrorCode"
                        wasEarlyStopped -> "EARLY_STOP_COMPLETE_4_FIELDS"
                        callbackCount >= budget.newTokensBudget -> "MAX_NEW_TOKENS_REACHED"
                        hasEosText -> "NATURAL_EOS_TEXT_DETECTED"
                        callbackCount < budget.newTokensBudget -> "NATURAL_EOS"
                        else -> "COMPLETED"
                    }

                    latestProfilingMetrics = latestProfilingMetrics.copy(
                        generationPeakRssMb = peakRss,
                        prefillLatencyMs = prefillLatencyMs,
                        generationLatencyMs = totalLatencyMs
                    )

                    SafeLog.i(
                        TAG,
                        "[$invocationId] Generation completed ($terminationReason): callbacks=$callbackCount, outputLength=${outputBuffer.length}, " +
                        "prefillMs=$prefillLatencyMs, decodeMs=$decodeLatencyMs, totalMs=$totalLatencyMs, " +
                        "RSS: start=${"%.2f".format(startRss)}MB, peak=${"%.2f".format(peakRss)}MB"
                    )

                    if (callbackErrorCode != null) {
                        return@withContext AiAdvisory(
                            availability = AdvisoryAvailability.UNAVAILABLE,
                            isDemonstration = false,
                            unavailableReason = "Engineering advisory generation could not be completed safely. Technical details logged to system log.",
                            advisoryLatencyMs = totalLatencyMs,
                            provenanceSummary = currentProvenanceSummary()
                        )
                    }

                    // STRICT PRESERVATION: Exact raw output without artificial prefix or characters
                    val rawOutput = outputBuffer.toString()
                    latestRawOutput = rawOutput
                    latestCallbackCount = callbackCount
                    SafeLog.i(TAG, "[$invocationId] Raw generated output (${rawOutput.length} chars, $callbackCount callbacks):\n$rawOutput")

                    val parsedAdvisory = AdvisoryOutputParser.parseAndValidate(
                        rawText = rawOutput,
                        officialDisposition = request.officialDisposition,
                        hasVerifiedReferences = request.verifiedReferenceExcerpts.isNotEmpty(),
                        isDemonstration = false,
                        latencyMs = totalLatencyMs,
                        provenanceSummary = currentProvenanceSummary(),
                        hasZeroDefects = request.defects.isEmpty()
                    )

                    // Write diagnostics artifact and exact raw output safely to disk
                    runCatching {
                        saveDiagnosticsSafely(
                            invocationId = invocationId,
                            request = request,
                            prompt = prompt,
                            promptTokens = promptTokens,
                            promptTokenIds = promptTokenIds,
                            budget = budget,
                            genConfig = genConfig,
                            rawOutput = rawOutput,
                            callbackCount = callbackCount,
                            callbackTrace = callbackTrace,
                            inferredTokenIds = inferredTokenIds,
                            hasEosText = hasEosText,
                            terminationReason = terminationReason,
                            prefillMs = prefillLatencyMs,
                            decodeMs = decodeLatencyMs,
                            totalMs = totalLatencyMs,
                            startRss = startRss,
                            peakRss = peakRss,
                            parsedAdvisory = parsedAdvisory
                        )
                    }.onFailure { diagErr ->
                        SafeLog.e(TAG, "[$invocationId] Diagnostic artifact writing failed safely: ${diagErr.message}", diagErr)
                    }

                    if (rawOutput.isEmpty()) {
                        return@withContext AiAdvisory(
                            availability = AdvisoryAvailability.UNAVAILABLE,
                            isDemonstration = false,
                            unavailableReason = "Engineering advisory generation produced no output.",
                            advisoryLatencyMs = totalLatencyMs,
                            provenanceSummary = currentProvenanceSummary()
                        )
                    }

                    parsedAdvisory
                } catch (c: CancellationException) {
                    SafeLog.i(TAG, "[$invocationId] Generation cancelled by caller.")
                    runCatching { module.stop() }
                    throw c
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "[$invocationId] ExecuTorch generation exception: ${e.message}", e)
                    val totalLatencyMs = (System.currentTimeMillis() - startGenMs).coerceAtLeast(1L)
                    AiAdvisory(
                        availability = AdvisoryAvailability.UNAVAILABLE,
                        isDemonstration = false,
                        unavailableReason = "Engineering advisory generation encountered a runtime error. Technical details logged to system log.",
                        advisoryLatencyMs = totalLatencyMs,
                        provenanceSummary = currentProvenanceSummary()
                    )
                } finally {
                    isGenerating.set(false)
                    // Reset KV-cache context after generation to leave clean state
                    SafeLog.i(TAG, "[$invocationId] Resetting KV-cache context via module.resetContext() post-inference.")
                    runCatching { module.resetContext() }.onFailure { err ->
                        SafeLog.w(TAG, "[$invocationId] Post-generation resetContext warning: ${err.message}")
                    }
                }
            }
        }

    fun cancelActiveGeneration() {
        if (isGenerating.get()) {
            isCancelled.set(true)
            runCatching { llmModule?.stop() }
        }
    }

    /**
     * Executes raw generation for diagnostic probe testing.
     * Follows identical KV-cache reset and LlmGenerationConfig lifecycle.
     */
    suspend fun generateRaw(
        prompt: String,
        maxNewTokens: Int = 128,
        temperature: Float = 0.2f
    ): String = inferenceMutex.withLock {
        withContext(ioDispatcher) {
            val module = llmModule
            if (module == null || _modelInfo.value.state != ModelRuntimeState.READY) {
                return@withContext ""
            }
            runCatching { module.resetContext() }.onFailure { err ->
                SafeLog.w(TAG, "Pre-generateRaw resetContext warning: ${err.message}")
            }
            val tokenizer = getOrLoadTokenizer()
            val promptTokenIds = tokenizer?.encode(prompt) ?: emptyList()
            val promptTokens = if (promptTokenIds.isNotEmpty()) promptTokenIds.size else (prompt.length / 3).coerceAtLeast(1)
            val budget = GenerationBudgeter.calculate(
                promptTokens = promptTokens,
                maxContextLen = MAX_CONTEXT_LEN,
                maxNewTokens = maxNewTokens
            )
            val genConfig = LlmGenerationConfig.create()
                .seqLen(budget.totalSeqLen)
                .maxNewTokens(budget.newTokensBudget)
                .temperature(temperature)
                .echo(false)
                .build()
            val outputBuffer = StringBuilder()
            var callbackCount = 0
            isCancelled.set(false)
            isGenerating.set(true)
            try {
                module.generate(
                    prompt,
                    genConfig,
                    object : LlmCallback {
                        override fun onResult(result: String) {
                            callbackCount++
                            if (outputBuffer.length < MAX_RAW_BUFFER_LEN) {
                                outputBuffer.append(result)
                            }
                        }
                        override fun onStats(stats: String) {}
                        override fun onError(errorCode: Int, message: String) {
                            SafeLog.e(TAG, "generateRaw onError: code=$errorCode, message=$message")
                        }
                    }
                )
            } finally {
                isGenerating.set(false)
                runCatching { module.resetContext() }.onFailure { err ->
                    SafeLog.w(TAG, "Post-generateRaw resetContext warning: ${err.message}")
                }
            }
            val res = outputBuffer.toString()
            latestRawOutput = res
            latestCallbackCount = callbackCount
            res
        }
    }

    override suspend fun unload() {
        cancelActiveGeneration()
        inferenceMutex.withLock {
            withContext(ioDispatcher) {
                runCatching { llmModule?.resetContext() }
                runCatching { llmModule?.close() }
                llmModule = null
                smolLm2Tokenizer = null
                val unloadRss = MemoryProfiler.getProcessRssMb()
                latestProfilingMetrics = latestProfilingMetrics.copy(
                    unloadRssMb = unloadRss
                )
                SafeLog.i(TAG, "SmolLM2 unloaded. RSS: ${"%.2f".format(unloadRss)}MB")

                val hasBoth = !activeModelPath.isNullOrBlank() && !activeTokenizerPath.isNullOrBlank()
                _modelInfo.value = _modelInfo.value.copy(
                    state = if (hasBoth) ModelRuntimeState.UNLOADED else ModelRuntimeState.NOT_LOADED,
                    detailMessage = if (hasBoth) {
                        "Artifacts stored; ExecuTorch session unloaded to free RAM."
                    } else {
                        "Not loaded. Import ExecuTorch .pte model and tokenizer files in Settings."
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
        return buildString {
            appendLine("Slot: AI Technical Advisory (${info.displayName})")
            appendLine("State: ${info.state.name}")
            appendLine("Runtime: ${info.runtimeLabel}")
            appendLine("Model (.pte): ${info.artifactPath ?: "None"}")
            appendLine("Tokenizer: ${info.tokenizerPath ?: "None"}")
            appendLine("Model SHA-256: ${info.sha256 ?: "N/A"}")
            appendLine("Prompt Contract: ${info.contractVersion ?: AdvisoryPromptBuilder.PROMPT_VERSION}")
            appendLine("Max Context Len: $MAX_CONTEXT_LEN")
            appendLine("Max New Tokens: $MAX_NEW_TOKENS")
        }
    }

    private fun getOrLoadTokenizer(): SmolLm2Tokenizer? {
        if (smolLm2Tokenizer != null) return smolLm2Tokenizer
        val tPath = activeTokenizerPath ?: return null
        val tFile = File(tPath)
        if (tFile.exists() && tFile.name.endsWith(".json", ignoreCase = true)) {
            smolLm2Tokenizer = runCatching { SmolLm2Tokenizer.load(tFile) }.getOrNull()
        }
        return smolLm2Tokenizer
    }

    private fun validatePreLoadArtifacts(modelFile: File, tokenizerFile: File): ModelOperationResult {
        if (!modelFile.exists() || !modelFile.canRead()) {
            return ModelOperationResult(false, "ExecuTorch .pte file '${modelFile.name}' is missing or unreadable.")
        }
        if (!modelFile.name.endsWith(".pte", ignoreCase = true)) {
            return ModelOperationResult(false, "Expected a .pte ExecuTorch program file, got '${modelFile.name}'.")
        }
        val modelLength = modelFile.length()
        if (modelLength < MIN_PTE_SIZE) {
            return ModelOperationResult(
                false,
                "ExecuTorch .pte file '${modelFile.name}' is too small to be valid ($modelLength bytes; minimum is $MIN_PTE_SIZE bytes)."
            )
        }

        // Llama 3.2 3B size verification
        val isLlama = modelFile.name.contains("llama", ignoreCase = true) || modelLength == LLAMA_3_2_3B_EXPECTED_SIZE
        if (isLlama && modelLength < LLAMA_3_2_3B_EXPECTED_SIZE) {
            val actualMib = modelLength / (1024 * 1024)
            val expectedMib = LLAMA_3_2_3B_EXPECTED_SIZE / (1024 * 1024)
            return ModelOperationResult(
                false,
                "Llama 3.2 3B model is truncated: size is $modelLength bytes (~$actualMib MiB), expected $LLAMA_3_2_3B_EXPECTED_SIZE bytes (~$expectedMib MiB). Aborting load to prevent SIGABRT."
            )
        }

        if (!tokenizerFile.exists() || !tokenizerFile.canRead()) {
            return ModelOperationResult(false, "Tokenizer file '${tokenizerFile.name}' is missing or unreadable.")
        }
        if (tokenizerFile.length() == 0L) {
            return ModelOperationResult(false, "Tokenizer file '${tokenizerFile.name}' is empty.")
        }
        val tokLower = tokenizerFile.name.lowercase()
        if (!tokLower.endsWith(".bin") && !tokLower.endsWith(".model") && !tokLower.endsWith(".json")) {
            return ModelOperationResult(
                false,
                "Unsupported tokenizer extension '${tokenizerFile.name}'; expected .bin, .model, or .json."
            )
        }
        if (tokLower.endsWith(".json")) {
            val content = tokenizerFile.readText()
            if (content.isBlank() || (!content.trimStart().startsWith("{") && !content.trimStart().startsWith("["))) {
                return ModelOperationResult(
                    false,
                    "Tokenizer file '${tokenizerFile.name}' does not contain valid JSON structure."
                )
            }
        }

        SafeLog.i(
            TAG,
            "Pre-load validation passed: model='${modelFile.name}' ($modelLength bytes), tokenizer='${tokenizerFile.name}' (${tokenizerFile.length()} bytes). Basic file and size checks passed; does not guarantee internal PTE graph validity."
        )
        return ModelOperationResult(true, "Pre-load validation passed.")
    }

    private fun resolveModelDisplayName(file: File): String {
        val name = file.name.lowercase()
        val size = file.length()
        return when {
            name.contains("llama") || size == LLAMA_3_2_3B_EXPECTED_SIZE -> "Llama 3.2 3B Instruct (${file.name})"
            name.contains("smollm") || size in (200L * 1024 * 1024)..(260L * 1024 * 1024) -> "SmolLM2 360M Instruct (${file.name})"
            else -> "ExecuTorch Advisory Model (${file.name})"
        }
    }

    private fun validateArtifactExtensions(modelFile: File, tokenizerFile: File) {
        require(modelFile.name.endsWith(".pte", ignoreCase = true)) {
            "Expected a .pte ExecuTorch program file, got '${modelFile.name}'."
        }
        val tokLower = tokenizerFile.name.lowercase()
        require(tokLower.endsWith(".bin") || tokLower.endsWith(".model") || tokLower.endsWith(".json")) {
            "Unsupported tokenizer extension '${tokenizerFile.name}'; expected .bin, .model, or .json."
        }
    }

    private fun currentProvenanceSummary(): String {
        val modelName = _modelInfo.value.displayName
        return "$modelName • ExecuTorch $EXECUTORCH_RUNTIME_VERSION • prompt=${AdvisoryPromptBuilder.PROMPT_VERSION} • sha256=${activeSha256?.take(12) ?: "NONE"}"
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
        slot = ModelSlot.AI_ADVISORY,
        displayName = "ExecuTorch Advisory Model",
        runtimeLabel = "ExecuTorch Android $EXECUTORCH_RUNTIME_VERSION",
        state = ModelRuntimeState.NOT_LOADED,
        contractVersion = AdvisoryPromptBuilder.PROMPT_VERSION,
        runtimeVersion = EXECUTORCH_RUNTIME_VERSION,
        detailMessage = "Not loaded. Import ExecuTorch .pte model and tokenizer files in Settings.",
        isDemonstrationOnly = false
    )

    private fun saveDiagnosticsSafely(
        invocationId: Long,
        request: AdvisoryRequest,
        prompt: String,
        promptTokens: Int,
        promptTokenIds: List<Int>,
        budget: GenerationBudget,
        genConfig: LlmGenerationConfig,
        rawOutput: String,
        callbackCount: Int,
        callbackTrace: List<String>,
        inferredTokenIds: List<Int>,
        hasEosText: Boolean,
        terminationReason: String,
        prefillMs: Long,
        decodeMs: Long,
        totalMs: Long,
        startRss: Double,
        peakRss: Double,
        parsedAdvisory: AiAdvisory
    ) {
        val targetDir = debugOutputDir
            ?: File("/data/user/0/com.aistudio.bharatpcb.xkqzmw/files").takeIf { it.exists() && it.canWrite() }
            ?: File("/data/data/com.aistudio.bharatpcb.xkqzmw/files").takeIf { it.exists() && it.canWrite() }

        val hasOpenBrace = rawOutput.contains('{')
        val hasCloseBrace = rawOutput.contains('}')
        val hasQuote = rawOutput.contains('"')
        val hasImEnd = rawOutput.contains("<|im_end|>")
        val hasEndOfText = rawOutput.contains("<|endoftext|>")
        val hasImStart = rawOutput.contains("<|im_start|>")

        val charFreq = computeCharFrequencies(rawOutput)
        val escapedRawOutput = escapeString(rawOutput)

        val report = buildString {
            appendLine("================================================================================")
            appendLine("SMOLLM2 360M EXECUCTORCH INFERENCE DIAGNOSTIC REPORT")
            appendLine("================================================================================")
            appendLine("Invocation ID       : $invocationId")
            appendLine("Inspection ID       : ${request.inspectionId}")
            appendLine("Board Name          : ${request.boardName}")
            appendLine("Official Disposition: ${request.officialDisposition.name}")
            appendLine("Model Path          : ${activeModelPath ?: "N/A"}")
            appendLine("Tokenizer Path      : ${activeTokenizerPath ?: "N/A"}")
            appendLine("Runtime Version     : ExecuTorch $EXECUTORCH_RUNTIME_VERSION")
            appendLine("Contract Version    : ${AdvisoryPromptBuilder.PROMPT_VERSION}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("CALLBACK EMISSION TRACE & HEURISTIC INFERRED TOKEN IDS")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Prompt Tokens Count : ${promptTokenIds.size} (est=$promptTokens)")
            appendLine("Prompt Token IDs (first 30): ${promptTokenIds.take(30).joinToString(", ")}${if (promptTokenIds.size > 30) "..." else ""}")
            appendLine("Generation Budget   : ${budget.newTokensBudget} tokens (seqLen=${budget.totalSeqLen})")
            appendLine("Callbacks Received  : $callbackCount (output chars: ${rawOutput.length})")
            appendLine("Inferred Token IDs  : ${inferredTokenIds.take(60).joinToString(", ")}${if (inferredTokenIds.size > 60) "..." else ""}")
            appendLine("Note: ExecuTorch 1.6.0 JNI delivers UTF-8 string fragments via LlmCallback.onResult().")
            appendLine("      Native integer token IDs are not exposed over JNI; Inferred Token IDs are heuristic.")
            appendLine("Termination Reason  : $terminationReason")
            appendLine("EOS Text In Output  : $hasEosText")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("GENERATION CONFIGURATION & BUDGET")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Prompt Tokens (est) : $promptTokens")
            appendLine("Prompt Chars Length : ${prompt.length}")
            appendLine("Config seqLen       : ${budget.totalSeqLen}")
            appendLine("Config maxNewTokens : ${budget.newTokensBudget}")
            appendLine("Config temperature  : ${genConfig.temperature}")
            appendLine("Config echo         : ${genConfig.echo}")
            appendLine("Config numBos       : ${genConfig.numBos}")
            appendLine("Config numEos       : ${genConfig.numEos}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("EXECUTION METRICS")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Termination Reason  : $terminationReason")
            appendLine("Total Callbacks     : $callbackCount")
            appendLine("Raw Output Length   : ${rawOutput.length} chars")
            appendLine("Prefill Latency     : ${prefillMs}ms")
            appendLine("Decode Latency      : ${decodeMs}ms")
            appendLine("Total Latency       : ${totalMs}ms")
            appendLine("RSS (Start / Peak)  : ${"%.2f".format(startRss)}MB / ${"%.2f".format(peakRss)}MB")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("SPECIAL MARKER & TOKEN PRESENCE")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Contains '{'          : $hasOpenBrace")
            appendLine("Contains '}'          : $hasCloseBrace")
            appendLine("Contains '\"'          : $hasQuote")
            appendLine("Contains '<|im_end|>' : $hasImEnd")
            appendLine("Contains '<|endoftext|>': $hasEndOfText")
            appendLine("Contains '<|im_start|>': $hasImStart")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("CHARACTER FREQUENCY ANALYSIS")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(charFreq)
            appendLine("--------------------------------------------------------------------------------")
            appendLine("PARSER EVALUATION")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Availability        : ${parsedAdvisory.availability}")
            appendLine("Unavailable Reason  : ${parsedAdvisory.unavailableReason ?: "None (Available)"}")
            appendLine("Probable Root Cause : ${parsedAdvisory.probableRootCause ?: "N/A"}")
            appendLine("Operator Action     : ${parsedAdvisory.recommendedOperatorAction ?: "N/A"}")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("EXACT PROMPT PASSED TO module.generate():")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(prompt)
            appendLine("--------------------------------------------------------------------------------")
            appendLine("EXACT RAW OUTPUT (UNALTERED):")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(rawOutput)
            appendLine("--------------------------------------------------------------------------------")
            appendLine("ESCAPED RAW OUTPUT:")
            appendLine("--------------------------------------------------------------------------------")
            appendLine(escapedRawOutput)
            appendLine("--------------------------------------------------------------------------------")
            appendLine("CALLBACK-BY-CALLBACK TRACE (first 30 and last 20):")
            appendLine("--------------------------------------------------------------------------------")
            if (callbackTrace.size <= 50) {
                callbackTrace.forEach { appendLine(it) }
            } else {
                callbackTrace.take(30).forEach { appendLine(it) }
                appendLine("... [${callbackTrace.size - 50} intermediate callbacks omitted for brevity] ...")
                callbackTrace.takeLast(20).forEach { appendLine(it) }
            }
            appendLine("================================================================================")
        }

        SafeLog.i(TAG, "[$invocationId] Diagnostic summary: len=${rawOutput.length}, callbacks=$callbackCount, quotes=$hasQuote, openBrace=$hasOpenBrace, closeBrace=$hasCloseBrace, imEnd=$hasImEnd")
        SafeLog.i(TAG, "[$invocationId] Character Frequencies:\n$charFreq")

        if (targetDir != null) {
            try {
                if (!targetDir.exists()) targetDir.mkdirs()

                val rawFile = File(targetDir, "smollm2_raw_output.txt")
                rawFile.writeText(rawOutput)
                SafeLog.i(TAG, "[$invocationId] Wrote raw output (${rawFile.length()} bytes) to: ${rawFile.absolutePath}")

                val diagFile = File(targetDir, "smollm2_debug_diagnostics.txt")
                diagFile.writeText(report)
                SafeLog.i(TAG, "[$invocationId] Wrote full diagnostics (${diagFile.length()} bytes) to: ${diagFile.absolutePath}")
            } catch (e: Throwable) {
                SafeLog.e(TAG, "[$invocationId] Failed to write diagnostics to ${targetDir.absolutePath}: ${e.message}", e)
            }
        } else {
            SafeLog.w(TAG, "[$invocationId] debugOutputDir is null; cannot write smollm2_raw_output.txt to disk.")
        }
    }

    private fun escapeString(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                in '\u0000'..'\u001f', in '\u007f'..'\u009f' -> append(String.format(Locale.US, "\\u%04X", c.code))
                else -> append(c)
            }
        }
    }

    internal fun toUnicodeCodePoints(s: String): String {
        return s.codePoints().toArray().joinToString(" ") { String.format(Locale.US, "U+%04X", it) }
    }

    internal fun computeCharFrequencies(s: String): String {
        if (s.isEmpty()) return "  (empty output buffer)"
        val map = mutableMapOf<Char, Int>()
        for (c in s) {
            map[c] = (map[c] ?: 0) + 1
        }
        return map.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { (ch, count) ->
                val label = when (ch) {
                    '\n' -> "\\n (newline)"
                    '\r' -> "\\r (carriage return)"
                    '\t' -> "\\t (tab)"
                    ' ' -> "<space>"
                    '\"' -> "\\\" (quote)"
                    else -> "'$ch'"
                }
                val codeHex = String.format(Locale.US, "U+%04X", ch.code)
                val pctStr = String.format(Locale.US, "%.1f%%", count * 100.0 / s.length)
                "  $label [$codeHex]: $count ($pctStr)"
            }
    }

    /**
     * Strictly verifies whether the accumulated generation buffer contains a complete, parseable,
     * fully populated JSON object containing ALL FOUR mandatory advisory fields:
     * 1. "probable_root_cause"
     * 2. "recommended_operator_action"
     * 3. "relevant_standard"
     * 4. "limitations"
     *
     * Early-stop occurs ONLY when all four fields are confirmed present, non-blank, and free of placeholders.
     */
    internal fun shouldStopGeneration(buffer: CharSequence): Boolean {
        val startIdx = buffer.indexOf('{')
        if (startIdx < 0) return false

        var depth = 0
        var inString = false
        var escape = false
        var seenOpen = false
        var balancedJson: String? = null

        for (i in startIdx until buffer.length) {
            val c = buffer[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                    seenOpen = true
                } else if (c == '}') {
                    depth--
                    if (depth == 0 && seenOpen) {
                        balancedJson = buffer.substring(startIdx, i + 1)
                        break
                    }
                }
            }
        }

        if (balancedJson == null) return false

        return AdvisoryOutputParser.validateAdvisoryFields(balancedJson)
    }

    companion object {
        private const val TAG = "ExecuTorchAdvisory"
        const val EXECUTORCH_RUNTIME_VERSION = "1.6.0"
        const val MAX_CONTEXT_LEN = 2048
        const val MAX_NEW_TOKENS = 180
        private const val MAX_RAW_BUFFER_LEN = 8192

        const val LLAMA_3_2_3B_EXPECTED_SIZE = 2_379_319_808L
        const val LLAMA_3_2_3B_EXPECTED_SHA256 = "8369298bf081eeed73cf23ac9ec9113f508ca672cf11ba4cb876104f751298cb"
        const val MIN_PTE_SIZE = 1024L
    }
}

private object SafeLog {
    fun i(tag: String, msg: String) {
        runCatching { android.util.Log.i(tag, msg) }.onFailure { println("[$tag] INFO: $msg") }
    }
    fun w(tag: String, msg: String) {
        runCatching { android.util.Log.w(tag, msg) }.onFailure { println("[$tag] WARN: $msg") }
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr != null) android.util.Log.e(tag, msg, tr)
            else android.util.Log.e(tag, msg)
        }.onFailure {
            println("[$tag] ERROR: $msg ${tr?.message.orEmpty()}")
        }
    }
    fun d(tag: String, msg: String) {
        runCatching { android.util.Log.d(tag, msg) }.onFailure { println("[$tag] DEBUG: $msg") }
    }
}
