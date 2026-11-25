package com.example.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.repository.StorageAccessManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles user-initiated Storage Access Framework (SAF) document writes and FileProvider
 * cache exports for Android Share Sheet, completely separated from automated audit logging.
 */
class StorageAccessManagerImpl(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : StorageAccessManager {

    private val shareCacheDir: File
        get() = File(context.cacheDir, "shared_exports").apply { mkdirs() }

    override suspend fun writeCsvToSafUri(uri: Uri, csvContent: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(csvContent.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                } ?: error("Unable to open SAF output stream for $uri")
                Unit
            }
        }

    override suspend fun prepareShareableCsvUri(fileName: String, csvContent: String): Result<Uri> =
        withContext(ioDispatcher) {
            runCatching {
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val exportFile = File(shareCacheDir, safeName)
                FileOutputStream(exportFile, false).use { out ->
                    out.write(csvContent.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )
            }
        }
}
