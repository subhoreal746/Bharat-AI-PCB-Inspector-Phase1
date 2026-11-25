package com.example.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelSlot
import com.example.domain.repository.CsvExportManager
import com.example.domain.repository.ImageStorageManager
import com.example.domain.repository.InspectionCsvLogger
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.ModelManager
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.StorageAccessManager
import com.example.inference.cv.ModelOperationResult
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageStatsSnapshot(
    val auditFilePath: String = "",
    val auditFileSizeBytes: Long = 0L,
    val imageStorageBytes: Long = 0L,
    val pendingCsvOutboxCount: Int = 0,
    val confirmedCsvLedgerCount: Int = 0
)

data class SettingsUiState(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val policyConfig: InspectionPolicyConfig = InspectionPolicyConfig(),
    val detectionModelInfo: ModelInfo,
    val advisoryModelInfo: ModelInfo,
    val transistorModelInfo: ModelInfo,
    val totalRecordsCount: Int = 0,
    val storageStats: StorageStatsSnapshot = StorageStatsSnapshot(),
    val isBusy: Boolean = false,
    val modelStatusBanner: ModelOperationResult? = null,
    val inspectedMetadataTitle: String? = null,
    val inspectedMetadataBody: String? = null,
    val showClearHistoryDialog: Boolean = false,
    val storageActionMessage: String? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager,
    private val inspectionRepository: InspectionRepository,
    private val imageStorageManager: ImageStorageManager,
    private val csvLogger: InspectionCsvLogger,
    private val csvExportManager: CsvExportManager,
    private val storageAccessManager: StorageAccessManager
) : ViewModel() {

    private data class LocalControls(
        val storageStats: StorageStatsSnapshot = StorageStatsSnapshot(),
        val isBusy: Boolean = false,
        val modelStatusBanner: ModelOperationResult? = null,
        val inspectedMetadataTitle: String? = null,
        val inspectedMetadataBody: String? = null,
        val showClearHistoryDialog: Boolean = false,
        val storageActionMessage: String? = null
    )

    private val _controls = MutableStateFlow(LocalControls())
    private var latestRecords: List<InspectionRecord> = emptyList()

    private val modelInfosFlow = combine(
        modelManager.detectionModelInfo,
        modelManager.advisoryModelInfo,
        modelManager.transistorModelInfo
    ) { det, adv, trans -> Triple(det, adv, trans) }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.themeMode,
        settingsRepository.policyConfig,
        modelInfosFlow,
        combine(inspectionRepository.observeAllRecords(), _controls) { records, ctrl ->
            latestRecords = records
            records.size to ctrl
        }
    ) { theme, policy, (detInfo, advInfo, transInfo), (recordCount, ctrl) ->
        SettingsUiState(
            themeMode = theme,
            policyConfig = policy,
            detectionModelInfo = detInfo,
            advisoryModelInfo = advInfo,
            transistorModelInfo = transInfo,
            totalRecordsCount = recordCount,
            storageStats = ctrl.storageStats,
            isBusy = ctrl.isBusy,
            modelStatusBanner = ctrl.modelStatusBanner,
            inspectedMetadataTitle = ctrl.inspectedMetadataTitle,
            inspectedMetadataBody = ctrl.inspectedMetadataBody,
            showClearHistoryDialog = ctrl.showClearHistoryDialog,
            storageActionMessage = ctrl.storageActionMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            detectionModelInfo = modelManager.detectionModelInfo.value,
            advisoryModelInfo = modelManager.advisoryModelInfo.value,
            transistorModelInfo = modelManager.transistorModelInfo.value
        )
    )

    init {
        refreshStorageStats()
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun updatePolicyConfig(config: InspectionPolicyConfig) {
        viewModelScope.launch {
            settingsRepository.updatePolicyConfig(config)
        }
    }

    fun resetPolicyDefaults() {
        viewModelScope.launch {
            settingsRepository.resetPolicyDefaults()
            _controls.update { it.copy(storageActionMessage = "Screening policy reset to baseline defaults.") }
        }
    }

    fun importDetectionModel(uri: Uri) {
        runModelOperation { modelManager.importDetectionModel(uri) }
    }

    fun loadDetectionModel() {
        runModelOperation { modelManager.loadDetectionModel() }
    }

    fun unloadDetectionModel() {
        runModelOperation { modelManager.unloadDetectionModel() }
    }

    fun importTransistorModel(uri: Uri) {
        runModelOperation { modelManager.importTransistorModel(uri) }
    }

    fun loadTransistorModel() {
        runModelOperation { modelManager.loadTransistorModel() }
    }

    fun unloadTransistorModel() {
        runModelOperation { modelManager.unloadTransistorModel() }
    }

    fun importAdvisoryModel(uri: Uri) {
        runModelOperation { modelManager.importAdvisoryModel(uri) }
    }

    fun importAdvisoryTokenizer(uri: Uri) {
        runModelOperation { modelManager.importAdvisoryTokenizer(uri) }
    }

    fun loadAdvisoryModel() {
        runModelOperation { modelManager.loadAdvisoryModel() }
    }

    fun unloadAdvisoryModel() {
        runModelOperation { modelManager.unloadAdvisoryModel() }
    }

    fun inspectModelMetadata(slot: ModelSlot) {
        val title = when (slot) {
            ModelSlot.PCB_DETECTION -> "YOLOv8n ONNX Runtime Metadata"
            ModelSlot.AI_ADVISORY -> "ExecuTorch Advisory Metadata"
            ModelSlot.TRANSISTOR_ANOMALY -> "PatchCore Unsupervised Anomaly Metadata"
        }
        val body = modelManager.inspectModelMetadata(slot)
        _controls.update {
            it.copy(inspectedMetadataTitle = title, inspectedMetadataBody = body)
        }
    }

    fun dismissMetadataDialog() {
        _controls.update { it.copy(inspectedMetadataTitle = null, inspectedMetadataBody = null) }
    }

    fun dismissModelStatusBanner() {
        _controls.update { it.copy(modelStatusBanner = null) }
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = StorageStatsSnapshot(
                auditFilePath = csvLogger.getAuditFilePath(),
                auditFileSizeBytes = csvLogger.getAuditFileSizeBytes(),
                imageStorageBytes = imageStorageManager.getImageStorageBytes(),
                pendingCsvOutboxCount = inspectionRepository.getPendingOutboxCount(),
                confirmedCsvLedgerCount = inspectionRepository.getConfirmedCsvLedgerCount()
            )
            _controls.update { it.copy(storageStats = stats) }
        }
    }

    fun reconcileCsvOutbox() {
        viewModelScope.launch {
            _controls.update { it.copy(isBusy = true) }
            val status = csvLogger.processPendingOutboxEvents()
            val msg = if (status.lastError != null) {
                "CSV reconciliation issue: ${status.lastError}"
            } else {
                "CSV audit log synced (${status.confirmedCount} confirmed, ${status.pendingCount} pending)."
            }
            _controls.update { it.copy(isBusy = false, storageActionMessage = msg) }
            refreshStorageStats()
        }
    }

    fun suggestedAllCsvFilename(): String = csvExportManager.suggestedFileNameForAll()

    fun exportAllCsvToSafUri(uri: Uri) {
        viewModelScope.launch {
            val records = inspectionRepository.observeAllRecords().first()
            val csv = csvExportManager.serializeRecordsToCsv(records)
            storageAccessManager.writeCsvToSafUri(uri, csv)
                .onSuccess {
                    _controls.update { it.copy(storageActionMessage = "Exported ${records.size} inspection record(s) to CSV.") }
                }
                .onFailure { err ->
                    _controls.update {
                        it.copy(storageActionMessage = "Export failed: ${err.message ?: "unknown error"}")
                    }
                }
        }
    }

    fun requestClearHistory() {
        _controls.update { it.copy(showClearHistoryDialog = true) }
    }

    fun dismissClearHistory() {
        _controls.update { it.copy(showClearHistoryDialog = false) }
    }

    fun confirmClearHistory() {
        viewModelScope.launch {
            _controls.update { it.copy(isBusy = true, showClearHistoryDialog = false) }
            inspectionRepository.clearAllHistory()
            csvLogger.clearAuditLogAndLedger()
            val protectedUris = inspectionRepository.getReferencedImageUris()
            imageStorageManager.deleteAllUnreferencedCaptures(protectedUris)
            _controls.update {
                it.copy(
                    isBusy = false,
                    storageActionMessage = "Cleared Room history, CSV audit log, and unreferenced PCB images."
                )
            }
            refreshStorageStats()
        }
    }

    fun clearStorageMessage() {
        _controls.update { it.copy(storageActionMessage = null) }
    }

    private fun runModelOperation(block: suspend () -> ModelOperationResult) {
        viewModelScope.launch {
            _controls.update { it.copy(isBusy = true, modelStatusBanner = null) }
            val result = block()
            _controls.update { it.copy(isBusy = false, modelStatusBanner = result) }
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val modelManager: ModelManager,
        private val inspectionRepository: InspectionRepository,
        private val imageStorageManager: ImageStorageManager,
        private val csvLogger: InspectionCsvLogger,
        private val csvExportManager: CsvExportManager,
        private val storageAccessManager: StorageAccessManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository = settingsRepository,
                modelManager = modelManager,
                inspectionRepository = inspectionRepository,
                imageStorageManager = imageStorageManager,
                csvLogger = csvLogger,
                csvExportManager = csvExportManager,
                storageAccessManager = storageAccessManager
            ) as T
        }
    }
}
