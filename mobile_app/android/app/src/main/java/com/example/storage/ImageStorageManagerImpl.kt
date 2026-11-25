package com.example.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.SamplePcbPreset
import com.example.domain.repository.ImageStorageManager
import com.example.imageprocessing.SafeImageDecoder
import com.example.inference.demo.SamplePcbCatalog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageStorageManagerImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ImageStorageManager {

    private val capturesDir: File
        get() = File(context.filesDir, "captured_pcbs").apply { mkdirs() }

    private val samplesDir: File
        get() = File(capturesDir, "samples").apply { mkdirs() }

    override suspend fun createCameraCaptureDestination(): Pair<String, Uri> =
        withContext(ioDispatcher) {
            val file = File(capturesDir, "pcb_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
            if (!file.exists()) {
                file.createNewFile()
            }
            val fileUriString = Uri.fromFile(file).toString()
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            fileUriString to contentUri
        }

    override suspend fun importPickedImageToAppStorage(sourceUri: Uri): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val targetFile = File(
                    capturesDir,
                    "picked_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
                )
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: error("Cannot open selected image URI")

                if (targetFile.length() <= 0L) {
                    targetFile.delete()
                    error("Imported image file is empty")
                }
                Uri.fromFile(targetFile).toString()
            }
        }

    override suspend fun materializeSamplePreset(preset: SamplePcbPreset): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val resId = SamplePcbCatalog.drawableResId(preset.drawableName)
                require(resId != 0) { "Sample drawable resource not found: ${preset.drawableName}" }
                val targetFile = File(samplesDir, "${preset.id}.png")
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    val bitmap = BitmapFactory.decodeResource(context.resources, resId)
                        ?: error("Failed to decode sample resource ${preset.drawableName}")
                    FileOutputStream(targetFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                Uri.fromFile(targetFile).toString()
            }
        }

    override suspend fun validateAndAssessClarity(
        imageUriString: String,
        minWidth: Int,
        minHeight: Int
    ): ImageValidationResult = withContext(ioDispatcher) {
        val uri = runCatching { Uri.parse(imageUriString) }.getOrNull()
            ?: return@withContext ImageValidationResult(
                isValid = false,
                isProcessable = false,
                width = 0,
                height = 0,
                clarityScore = null,
                lowClarityRisk = true,
                errorMessage = "Malformed image URI."
            )

        if (uri.scheme == "file") {
            val file = uri.path?.let(::File)
            if (file == null || !file.exists() || !file.canRead() || file.length() <= 0L) {
                return@withContext ImageValidationResult(
                    isValid = false,
                    isProcessable = false,
                    width = 0,
                    height = 0,
                    clarityScore = null,
                    lowClarityRisk = true,
                    errorMessage = "Captured image file is missing or empty."
                )
            }
        }

        SafeImageDecoder.validateAndAssessClarity(
            contentResolver = context.contentResolver,
            uri = uri,
            minWidth = minWidth,
            minHeight = minHeight
        )
    }

    override suspend fun cleanupOrphanedAndEmptyFiles(protectedUris: Set<String>): Int =
        withContext(ioDispatcher) {
            var deleted = 0
            val protectedPaths = protectedUris.mapNotNull { runCatching { Uri.parse(it).path }.getOrNull() }.toSet()
            capturesDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val isProtected = protectedPaths.contains(file.absolutePath)
                    if (!isProtected && (file.length() == 0L || file.name.startsWith("tmp_"))) {
                        if (file.delete()) deleted++
                    }
                }
            }
            deleted
        }

    override suspend fun deleteAllUnreferencedCaptures(protectedUris: Set<String>): Int =
        withContext(ioDispatcher) {
            var deleted = 0
            val protectedPaths = protectedUris.mapNotNull { runCatching { Uri.parse(it).path }.getOrNull() }.toSet()
            capturesDir.walkTopDown().forEach { file ->
                if (file.isFile && !protectedPaths.contains(file.absolutePath)) {
                    if (file.delete()) deleted++
                }
            }
            deleted
        }

    override suspend fun deleteUnreferencedCaptureIfEmpty(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        withContext(ioDispatcher) {
            val path = runCatching { Uri.parse(uriString).path }.getOrNull() ?: return@withContext
            val file = File(path)
            if (file.exists() && file.isFile && file.length() == 0L) {
                file.delete()
            }
        }
    }

    override suspend fun getImageStorageBytes(): Long = withContext(ioDispatcher) {
        capturesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
