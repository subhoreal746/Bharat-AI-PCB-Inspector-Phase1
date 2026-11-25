package com.example.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.InspectionRecord
import com.example.domain.repository.CsvExportManager
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.StorageAccessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val isLoading: Boolean = true,
    val record: InspectionRecord? = null,
    val showAnnotatedOverlay: Boolean = true,
    val selectedDefectId: String? = null,
    val isProvenanceExpanded: Boolean = false,
    val statusMessage: String? = null
)

class ResultsViewModel(
    private val inspectionId: String,
    inspectionRepository: InspectionRepository,
    private val csvExportManager: CsvExportManager,
    private val storageAccessManager: StorageAccessManager
) : ViewModel() {

    private val _uiControls = MutableStateFlow(ResultsUiControls())

    private data class ResultsUiControls(
        val showAnnotatedOverlay: Boolean = true,
        val selectedDefectId: String? = null,
        val isProvenanceExpanded: Boolean = false,
        val statusMessage: String? = null
    )

    val uiState: StateFlow<ResultsUiState> = combine(
        inspectionRepository.observeRecordById(inspectionId),
        _uiControls
    ) { record, controls ->
        val validSelection = if (record != null && controls.selectedDefectId != null &&
            record.defects.any { it.defectId == controls.selectedDefectId }
        ) {
            controls.selectedDefectId
        } else {
            null
        }
        ResultsUiState(
            isLoading = false,
            record = record,
            showAnnotatedOverlay = controls.showAnnotatedOverlay,
            selectedDefectId = validSelection,
            isProvenanceExpanded = controls.isProvenanceExpanded,
            statusMessage = controls.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ResultsUiState()
    )

    fun setAnnotatedOverlay(enabled: Boolean) {
        _uiControls.update { it.copy(showAnnotatedOverlay = enabled) }
    }

    fun selectDefect(defectId: String?) {
        _uiControls.update { current ->
            val next = if (current.selectedDefectId == defectId) null else defectId
            current.copy(selectedDefectId = next, showAnnotatedOverlay = true)
        }
    }

    fun toggleProvenanceExpanded() {
        _uiControls.update { it.copy(isProvenanceExpanded = !it.isProvenanceExpanded) }
    }

    fun suggestedCsvFilename(): String = csvExportManager.suggestedFileNameForRecord(inspectionId)

    fun exportToSafUri(uri: Uri) {
        val record = uiState.value.record ?: return
        viewModelScope.launch {
            val csv = csvExportManager.serializeRecordsToCsv(listOf(record))
            storageAccessManager.writeCsvToSafUri(uri, csv)
                .onSuccess {
                    _uiControls.update { it.copy(statusMessage = "Exported inspection CSV via SAF.") }
                }
                .onFailure { err ->
                    _uiControls.update {
                        it.copy(statusMessage = "CSV export failed: ${err.message ?: "unknown error"}")
                    }
                }
        }
    }

    fun prepareShareUri(onReady: (Uri) -> Unit) {
        val record = uiState.value.record ?: return
        viewModelScope.launch {
            val csv = csvExportManager.serializeRecordsToCsv(listOf(record))
            storageAccessManager.prepareShareableCsvUri(
                fileName = csvExportManager.suggestedFileNameForRecord(record.inspectionId),
                csvContent = csv
            ).onSuccess { uri ->
                onReady(uri)
            }.onFailure { err ->
                _uiControls.update {
                    it.copy(statusMessage = "Share failed: ${err.message ?: "unknown error"}")
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiControls.update { it.copy(statusMessage = null) }
    }

    class Factory(
        private val inspectionId: String,
        private val inspectionRepository: InspectionRepository,
        private val csvExportManager: CsvExportManager,
        private val storageAccessManager: StorageAccessManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ResultsViewModel(
                inspectionId = inspectionId,
                inspectionRepository = inspectionRepository,
                csvExportManager = csvExportManager,
                storageAccessManager = storageAccessManager
            ) as T
        }
    }
}
