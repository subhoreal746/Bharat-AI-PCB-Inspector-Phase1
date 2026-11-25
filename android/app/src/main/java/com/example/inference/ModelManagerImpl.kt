package com.example.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelSlot
import com.example.domain.repository.ModelManager
import com.example.inference.cv.ModelOperationResult
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages atomic staging, validation, safe promotion, rollback, and unloading
 * of production ONNX, PatchCore, and ExecuTorch model artifacts in app-private storage.
 */
class ModelManagerImpl(
    private val context: Context,
    private val settingsRepository: SettingsRepositoryImpl,
    private val onnxEngine: OnnxPcbDetectionEngine,
    private val execuTorchEngine: ExecuTorchAdvisoryEngine,
    private val patchCoreEngine: PatchCoreTransistorEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ModelManager {

    override val detectionModelInfo: StateFlow<ModelInfo> = onnxEngine.modelInfo
    override val advisoryModelInfo: StateFlow<ModelInfo> = execuTorchEngine.modelInfo
    override val transistorModelInfo: StateFlow<ModelInfo> = patchCoreEngine.modelInfo

    private val advisoryOperationMutex = Mutex()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val stagingDir: File
        get() = File(modelsDir, "staging").apply { mkdirs() }

    override suspend fun importDetectionModel(sourceUri: Uri): ModelOperationResult =
        withContext(ioDispatcher) {
            val displayName = resolveDisplayName(sourceUri) ?: "yolov8n_pcb.onnx"
            if (!displayName.endsWith(".onnx", ignoreCase = true)) {
                return@withContext ModelOperationResult(
                    false,
                    "Invalid file '$displayName': expected a .onnx YOLOv8n artifact."
                )
            }

            val stagedFile = File(stagingDir, "stage_${UUID.randomUUID()}.onnx")
            try {
                copyUriToFile(sourceUri, stagedFile)
                val validation = onnxEngine.validateAndStageCandidate(stagedFile)
                if (validation.isFailure) {
                    return@withContext ModelOperationResult(
                        false,
                        "ONNX validation failed (previous model preserved): ${validation.exceptionOrNull()?.message}"
                    )
                }

                val targetFile = File(modelsDir, "active_pcb_detector.onnx")
                val backupFile = File(modelsDir, "backup_pcb_detector.onnx")
                if (targetFile.exists()) {
                    targetFile.copyTo(backupFile, overwrite = true)
                }

                check(stagedFile.copyTo(targetFile, overwrite = true).length() > 0L) {
                    "Failed to promote staged ONNX file."
                }
                val loadResult = onnxEngine.loadFromModelFile(targetFile)
                if (!loadResult.success) {
                    if (backupFile.exists()) {
                        backupFile.copyTo(targetFile, overwrite = true)
                        onnxEngine.loadFromModelFile(targetFile)
                    }
                    return@withContext loadResult
                }

                backupFile.delete()
                settingsRepository.setDetectionModelPath(targetFile.absolutePath)
                loadResult
            } catch (e: Throwable) {
                ModelOperationResult(false, "ONNX import failed: ${e.message ?: "unknown error"}")
            } finally {
                stagedFile.delete()
            }
        }

    override suspend fun loadDetectionModel(): ModelOperationResult {
        val artifacts = settingsRepository.storedModelArtifacts.first()
        val path = artifacts.detectionModelPath ?: return ModelOperationResult(
            false,
            "No imported ONNX model found. Import a .onnx file first."
        )
        return onnxEngine.loadFromModelFile(File(path))
    }

    override suspend fun unloadDetectionModel(): ModelOperationResult {
        onnxEngine.unload()
        return ModelOperationResult(true, "ONNX detection session unloaded.")
    }

    override suspend fun importAdvisoryModel(sourceUri: Uri): ModelOperationResult =
        withContext(ioDispatcher) {
            advisoryOperationMutex.withLock {
                val (resolvedName, declaredSize) = resolveSourceInfo(sourceUri)
                val displayName = resolvedName ?: "model.pte"
                if (!displayName.endsWith(".pte", ignoreCase = true)) {
                    return@withContext ModelOperationResult(
                        false,
                        "Invalid file '$displayName': expected an ExecuTorch .pte model."
                    )
                }

                SafeLog.i(
                    TAG,
                    "Starting advisory model import: name='$displayName', declaredSize=$declaredSize, uri=$sourceUri"
                )

                val stagedFile = File(stagingDir, "stage_${UUID.randomUUID()}.pte")
                try {
                    // Step 1: Stream from source and capture actual bytes copied
                    val bytesCopied = copyUriToFileWithStats(sourceUri, stagedFile)
                    val stagedSize = stagedFile.length()

                    SafeLog.i(
                        TAG,
                        "Model streamed to staging: path='${stagedFile.absolutePath}', bytesCopied=$bytesCopied, stagedSize=$stagedSize"
                    )

                    // Step 2: Pre-promotion candidate validation
                    val validation = ModelArtifactValidator.validateStagedPte(
                        file = stagedFile,
                        displayName = displayName,
                        declaredSourceSize = declaredSize,
                        bytesCopied = bytesCopied
                    )
                    if (!validation.success) {
                        SafeLog.e(TAG, "Advisory model candidate validation failed: ${validation.message}")
                        stagedFile.delete()
                        return@withContext validation
                    }

                    // Step 3: Safe promotion to active destination with backup preservation
                    val targetFile = File(modelsDir, ACTIVE_ADVISORY_MODEL_NAME)
                    val legacyFile = File(modelsDir, LEGACY_ACTIVE_ADVISORY_MODEL_NAME)
                    val backupFile = File(modelsDir, BACKUP_ADVISORY_MODEL_NAME)

                    // Backup current active model if it exists
                    if (targetFile.exists()) {
                        atomicMoveOrCopy(targetFile, backupFile)
                    } else if (legacyFile.exists()) {
                        atomicMoveOrCopy(legacyFile, backupFile)
                    }

                    // Move candidate into active destination (O(1) inode move on same filesystem)
                    val moveSuccess = atomicMoveOrCopy(stagedFile, targetFile)
                    val promotionVerified = moveSuccess && targetFile.exists() && targetFile.length() == stagedSize

                    if (!promotionVerified) {
                        SafeLog.e(TAG, "Promotion failed or size mismatch. Restoring previous active model if available.")
                        if (backupFile.exists()) {
                            atomicMoveOrCopy(backupFile, targetFile)
                        }
                        stagedFile.delete()
                        return@withContext ModelOperationResult(
                            false,
                            "Failed to promote staged model candidate to active storage."
                        )
                    }

                    // Promotion verified: safe to discard backup
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    if (legacyFile.exists() && legacyFile != targetFile) {
                        legacyFile.delete()
                    }

                    // Step 4: Persist promoted path in settings
                    settingsRepository.setAdvisoryModelPath(targetFile.absolutePath)
                    SafeLog.i(TAG, "Active advisory model successfully updated: '${targetFile.absolutePath}' (${targetFile.length()} bytes)")

                    // Step 5: Notify engine and update configured artifacts
                    val artifacts = settingsRepository.storedModelArtifacts.first()
                    val tokFile = resolveActiveTokenizerFile(artifacts.advisoryTokenizerPath)
                    execuTorchEngine.setConfiguredArtifacts(targetFile, tokFile)

                    val statusMsg = if (tokFile != null && tokFile.exists()) {
                        "Imported $displayName (${formatBytesHuman(targetFile.length())}) successfully. Artifacts ready; tap Load to initialize ExecuTorch."
                    } else {
                        "Imported $displayName (${formatBytesHuman(targetFile.length())}) successfully. Import tokenizer next to load ExecuTorch."
                    }
                    ModelOperationResult(true, statusMsg)
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "Advisory model import error: ${e.message}", e)
                    ModelOperationResult(false, "ExecuTorch .pte import failed: ${e.message ?: "unknown error"}")
                } finally {
                    if (stagedFile.exists()) {
                        stagedFile.delete()
                    }
                }
            }
        }

    override suspend fun importAdvisoryTokenizer(sourceUri: Uri): ModelOperationResult =
        withContext(ioDispatcher) {
            advisoryOperationMutex.withLock {
                val (resolvedName, _) = resolveSourceInfo(sourceUri)
                val displayName = resolvedName ?: "tokenizer.json"
                val lower = displayName.lowercase()
                if (!lower.endsWith(".bin") && !lower.endsWith(".model") && !lower.endsWith(".json")) {
                    return@withContext ModelOperationResult(
                        false,
                        "Invalid tokenizer '$displayName': expected .json, .bin, or .model."
                    )
                }
                val stagedFile = File(stagingDir, "stage_tok_${UUID.randomUUID()}_$displayName")
                try {
                    copyUriToFileWithStats(sourceUri, stagedFile)
                    if (lower.endsWith(".json")) {
                        val content = stagedFile.readText()
                        if (content.isBlank() || (!content.trimStart().startsWith("{") && !content.trimStart().startsWith("["))) {
                            stagedFile.delete()
                            return@withContext ModelOperationResult(
                                false,
                                "Invalid tokenizer JSON: '$displayName' does not contain valid JSON structure."
                            )
                        }
                    }

                    val ext = displayName.substringAfterLast('.', "json")
                    val targetFile = File(modelsDir, "active_tokenizer.$ext")
                    val backupFile = File(modelsDir, "backup_tokenizer.$ext")
                    if (targetFile.exists()) {
                        atomicMoveOrCopy(targetFile, backupFile)
                    }

                    val moved = atomicMoveOrCopy(stagedFile, targetFile)
                    if (!moved || !targetFile.exists() || targetFile.length() == 0L) {
                        if (backupFile.exists()) atomicMoveOrCopy(backupFile, targetFile)
                        stagedFile.delete()
                        return@withContext ModelOperationResult(false, "Failed to stage tokenizer artifact.")
                    }
                    if (backupFile.exists()) backupFile.delete()

                    settingsRepository.setAdvisoryTokenizerPath(targetFile.absolutePath)

                    // Stage auxiliary tokenizer config/special-tokens files if available alongside source
                    if (sourceUri.scheme == "file" || sourceUri.scheme == null) {
                        val sourcePath = sourceUri.path
                        if (sourcePath != null) {
                            val parentDir = File(sourcePath).parentFile
                            if (parentDir != null && parentDir.exists()) {
                                listOf("special_tokens_map.json", "tokenizer_config.json").forEach { auxName ->
                                    val auxSrc = File(parentDir, auxName)
                                    if (auxSrc.exists()) {
                                        auxSrc.copyTo(File(modelsDir, auxName), overwrite = true)
                                    }
                                }
                            }
                        }
                    }

                    val artifacts = settingsRepository.storedModelArtifacts.first()
                    val modelFile = resolveActiveAdvisoryModelFile(artifacts.advisoryModelPath)
                    execuTorchEngine.setConfiguredArtifacts(modelFile, targetFile)

                    val statusMsg = if (modelFile != null && modelFile.exists()) {
                        "Imported tokenizer $displayName. Artifacts ready; tap Load to initialize ExecuTorch."
                    } else {
                        "Imported tokenizer $displayName. Import .pte model next to load ExecuTorch."
                    }
                    ModelOperationResult(true, statusMsg)
                } catch (e: Throwable) {
                    ModelOperationResult(false, "Tokenizer import failed: ${e.message ?: "unknown error"}")
                } finally {
                    if (stagedFile.exists()) stagedFile.delete()
                }
            }
        }

    override suspend fun loadAdvisoryModel(): ModelOperationResult =
        withContext(ioDispatcher) {
            advisoryOperationMutex.withLock {
                val artifacts = settingsRepository.storedModelArtifacts.first()
                val modelFile = resolveActiveAdvisoryModelFile(artifacts.advisoryModelPath)
                val tokFile = resolveActiveTokenizerFile(artifacts.advisoryTokenizerPath)

                if (modelFile == null || !modelFile.exists()) {
                    return@withContext ModelOperationResult(
                        false,
                        "No imported ExecuTorch .pte model found. Import a .pte model file first in Settings."
                    )
                }
                if (tokFile == null || !tokFile.exists()) {
                    return@withContext ModelOperationResult(
                        false,
                        "No imported tokenizer found. Import a tokenizer file first in Settings."
                    )
                }

                execuTorchEngine.loadFromArtifacts(modelFile, tokFile)
            }
        }

    override suspend fun unloadAdvisoryModel(): ModelOperationResult {
        execuTorchEngine.unload()
        return ModelOperationResult(true, "ExecuTorch advisory session unloaded.")
    }

    override suspend fun importTransistorModel(sourceUri: Uri): ModelOperationResult =
        withContext(ioDispatcher) {
            val displayName = resolveDisplayName(sourceUri) ?: ACTIVE_PATCHCORE_MODEL_NAME
            if (!displayName.endsWith(".onnx", ignoreCase = true)) {
                return@withContext ModelOperationResult(
                    false,
                    "Invalid file '$displayName': expected a .onnx PatchCore artifact."
                )
            }

            val stagedFile = File(stagingDir, "stage_patchcore_${UUID.randomUUID()}.onnx")
            try {
                copyUriToFile(sourceUri, stagedFile)
                val validation = patchCoreEngine.validateAndStageCandidate(stagedFile)
                if (validation.isFailure) {
                    return@withContext ModelOperationResult(
                        false,
                        "PatchCore ONNX validation failed (previous model preserved): ${validation.exceptionOrNull()?.message}"
                    )
                }

                val targetFile = File(modelsDir, ACTIVE_PATCHCORE_MODEL_NAME)
                val backupFile = File(modelsDir, BACKUP_PATCHCORE_MODEL_NAME)
                if (targetFile.exists()) {
                    targetFile.copyTo(backupFile, overwrite = true)
                }

                check(stagedFile.copyTo(targetFile, overwrite = true).length() > 0L) {
                    "Failed to promote staged PatchCore ONNX file."
                }
                val loadResult = patchCoreEngine.loadFromModelFile(targetFile)
                if (!loadResult.success) {
                    if (backupFile.exists()) {
                        backupFile.copyTo(targetFile, overwrite = true)
                        patchCoreEngine.loadFromModelFile(targetFile)
                    }
                    return@withContext loadResult
                }

                backupFile.delete()
                val legacyFile = File(modelsDir, LEGACY_ACTIVE_PATCHCORE_MODEL_NAME)
                if (legacyFile.exists() && legacyFile != targetFile) {
                    legacyFile.delete()
                }
                settingsRepository.setTransistorModelPath(targetFile.absolutePath)
                loadResult
            } catch (e: Throwable) {
                ModelOperationResult(false, "PatchCore import failed: ${e.message ?: "unknown error"}")
            } finally {
                stagedFile.delete()
            }
        }

    override suspend fun loadTransistorModel(): ModelOperationResult {
        val artifacts = settingsRepository.storedModelArtifacts.first()
        val path = artifacts.transistorModelPath

        val targetFile = File(modelsDir, ACTIVE_PATCHCORE_MODEL_NAME)
        val legacyFile = File(modelsDir, LEGACY_ACTIVE_PATCHCORE_MODEL_NAME)

        val fileToLoad: File? = when {
            targetFile.exists() && targetFile.length() > 0L -> targetFile
            !path.isNullOrBlank() && File(path).exists() && File(path).length() > 0L -> {
                val f = File(path)
                if (f.name == LEGACY_ACTIVE_PATCHCORE_MODEL_NAME) {
                    runCatching {
                        f.copyTo(targetFile, overwrite = true)
                        settingsRepository.setTransistorModelPath(targetFile.absolutePath)
                    }
                    if (targetFile.exists() && targetFile.length() > 0L) targetFile else f
                } else {
                    f
                }
            }
            legacyFile.exists() && legacyFile.length() > 0L -> {
                runCatching {
                    legacyFile.copyTo(targetFile, overwrite = true)
                    settingsRepository.setTransistorModelPath(targetFile.absolutePath)
                }
                if (targetFile.exists() && targetFile.length() > 0L) targetFile else legacyFile
            }
            else -> null
        }

        if (fileToLoad == null) {
            return ModelOperationResult(
                false,
                "No imported PatchCore model found. Import active_patchcore.onnx first."
            )
        }
        return patchCoreEngine.loadFromModelFile(fileToLoad)
    }

    override suspend fun unloadTransistorModel(): ModelOperationResult {
        patchCoreEngine.unload()
        return ModelOperationResult(true, "PatchCore anomaly detection session unloaded.")
    }

    override fun inspectModelMetadata(slot: ModelSlot): String {
        return when (slot) {
            ModelSlot.PCB_DETECTION -> onnxEngine.inspectCurrentMetadataText()
            ModelSlot.AI_ADVISORY -> execuTorchEngine.inspectCurrentMetadataText()
            ModelSlot.TRANSISTOR_ANOMALY -> patchCoreEngine.inspectCurrentMetadataText()
        }
    }

    private fun resolveActiveAdvisoryModelFile(persistedPath: String?): File? {
        if (!persistedPath.isNullOrBlank()) {
            val file = File(persistedPath)
            if (file.exists() && file.length() > 0L) return file
        }
        val active = File(modelsDir, ACTIVE_ADVISORY_MODEL_NAME)
        if (active.exists() && active.length() > 0L) return active
        val legacy = File(modelsDir, LEGACY_ACTIVE_ADVISORY_MODEL_NAME)
        if (legacy.exists() && legacy.length() > 0L) return legacy
        return null
    }

    private fun resolveActiveTokenizerFile(persistedPath: String?): File? {
        if (!persistedPath.isNullOrBlank()) {
            val file = File(persistedPath)
            if (file.exists() && file.length() > 0L) return file
        }
        listOf("json", "bin", "model").forEach { ext ->
            val tok = File(modelsDir, "active_tokenizer.$ext")
            if (tok.exists() && tok.length() > 0L) return tok
        }
        return null
    }

    private fun atomicMoveOrCopy(source: File, target: File): Boolean {
        if (source.renameTo(target)) {
            return true
        }
        return try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Throwable) {
            SafeLog.w(TAG, "File move failed, falling back to copy: ${e.message}")
            try {
                source.copyTo(target, overwrite = true)
                val ok = target.exists() && target.length() == source.length()
                if (ok) source.delete()
                ok
            } catch (err: Throwable) {
                SafeLog.e(TAG, "Fallback copy also failed: ${err.message}", err)
                false
            }
        }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        copyUriToFileWithStats(uri, target)
    }

    private fun copyUriToFileWithStats(uri: Uri, target: File): Long {
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    totalBytes += read
                }
                output.flush()
            }
        } ?: error("Cannot open selected model file stream.")
        require(target.exists() && target.length() > 0L) {
            "Imported file is empty."
        }
        return totalBytes
    }

    private fun resolveSourceInfo(uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            return Pair(file?.name, file?.takeIf { it.exists() }?.length())
        }
        var name: String? = null
        var size: Long? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        name = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to query ContentResolver for $uri: ${e.message}")
        }
        return Pair(name ?: uri.lastPathSegment, size)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return resolveSourceInfo(uri).first
    }

    private fun formatBytesHuman(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> "%.2f GiB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024L -> "%.0f KiB".format(bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        const val ACTIVE_ADVISORY_MODEL_NAME = "active_advisory.pte"
        const val LEGACY_ACTIVE_ADVISORY_MODEL_NAME = "active_smollm2_advisory.pte"
        const val BACKUP_ADVISORY_MODEL_NAME = "backup_advisory.pte"
        const val ACTIVE_PATCHCORE_MODEL_NAME = "active_patchcore.onnx"
        const val LEGACY_ACTIVE_PATCHCORE_MODEL_NAME = "active_transistor_patchcore.onnx"
        const val BACKUP_PATCHCORE_MODEL_NAME = "backup_patchcore.onnx"
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
}
