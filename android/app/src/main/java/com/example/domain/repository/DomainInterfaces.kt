package com.example.domain.repository

import android.net.Uri
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelSlot
import com.example.domain.model.SamplePcbPreset
import com.example.inference.cv.ModelOperationResult
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InspectionRepository {
    fun observeAllRecords(): Flow<List<InspectionRecord>>
    fun observeRecordById(inspectionId: String): Flow<InspectionRecord?>
    suspend fun getRecordById(inspectionId: String): InspectionRecord?
    suspend fun saveInspectionAtomically(record: InspectionRecord)
    suspend fun clearAllHistory()
    suspend fun getReferencedImageUris(): Set<String>
    suspend fun getPendingOutboxCount(): Int
    suspend fun getConfirmedCsvLedgerCount(): Int
}

interface SettingsRepository {
    val themeMode: Flow<AppThemeMode>
    val policyConfig: Flow<InspectionPolicyConfig>
    suspend fun setThemeMode(mode: AppThemeMode)
    suspend fun updatePolicyConfig(config: InspectionPolicyConfig)
    suspend fun resetPolicyDefaults()
}

interface ImageStorageManager {
    suspend fun createCameraCaptureDestination(): Pair<String, Uri>
    suspend fun importPickedImageToAppStorage(sourceUri: Uri): Result<String>
    suspend fun materializeSamplePreset(preset: SamplePcbPreset): Result<String>
    suspend fun validateAndAssessClarity(
        imageUriString: String,
        minWidth: Int,
        minHeight: Int
    ): ImageValidationResult
    suspend fun cleanupOrphanedAndEmptyFiles(protectedUris: Set<String>): Int
    suspend fun deleteAllUnreferencedCaptures(protectedUris: Set<String>): Int
    suspend fun deleteUnreferencedCaptureIfEmpty(uriString: String?)
    suspend fun getImageStorageBytes(): Long
}

interface ModelManager {
    val detectionModelInfo: StateFlow<ModelInfo>
    val advisoryModelInfo: StateFlow<ModelInfo>
    val transistorModelInfo: StateFlow<ModelInfo>

    suspend fun importDetectionModel(sourceUri: Uri): ModelOperationResult
    suspend fun loadDetectionModel(): ModelOperationResult
    suspend fun unloadDetectionModel(): ModelOperationResult

    suspend fun importAdvisoryModel(sourceUri: Uri): ModelOperationResult
    suspend fun importAdvisoryTokenizer(sourceUri: Uri): ModelOperationResult
    suspend fun loadAdvisoryModel(): ModelOperationResult
    suspend fun unloadAdvisoryModel(): ModelOperationResult

    suspend fun importTransistorModel(sourceUri: Uri): ModelOperationResult
    suspend fun loadTransistorModel(): ModelOperationResult
    suspend fun unloadTransistorModel(): ModelOperationResult

    fun inspectModelMetadata(slot: ModelSlot): String
}

interface InspectionCsvLogger {
    suspend fun processPendingOutboxEvents(): CsvAuditStatus
    suspend fun regenerateAuditCsvFromRoom(): Result<String>
    suspend fun clearAuditLogAndLedger()
    suspend fun getAuditFileSizeBytes(): Long
    fun getAuditFilePath(): String
}

data class CsvAuditStatus(
    val pendingCount: Int,
    val confirmedCount: Int,
    val failedCount: Int,
    val lastError: String? = null
)

interface CsvExportManager {
    fun serializeRecordsToCsv(records: List<InspectionRecord>): String
    fun suggestedFileNameForAll(): String
    fun suggestedFileNameForRecord(inspectionId: String): String
}

interface StorageAccessManager {
    suspend fun writeCsvToSafUri(uri: Uri, csvContent: String): Result<Unit>
    suspend fun prepareShareableCsvUri(fileName: String, csvContent: String): Result<Uri>
}
