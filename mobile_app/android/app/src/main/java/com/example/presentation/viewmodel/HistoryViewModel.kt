package com.example.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.DefectSeverity
import com.example.domain.model.InspectionDisposition
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

enum class HistoryDispositionFilter(val label: String) {
    ALL("All"),
    PASS("PASS"),
    REVIEW("REVIEW"),
    REJECT("REJECT"),
    INCOMPLETE("INCOMPLETE")
}

enum class HistorySortOrder(val label: String) {
    NEWEST_FIRST("Newest First"),
    OLDEST_FIRST("Oldest First"),
    MOST_DEFECTS("Most Defects"),
    HIGHEST_SEVERITY("Highest Severity")
}

data class HistoryUiState(
    val searchQuery: String = "",
    val dispositionFilter: HistoryDispositionFilter = HistoryDispositionFilter.ALL,
    val sortOrder: HistorySortOrder = HistorySortOrder.NEWEST_FIRST,
    val totalRecordsCount: Int = 0,
    val filteredRecords: List<InspectionRecord> = emptyList(),
    val statusMessage: String? = null
)

class HistoryViewModel(
    private val inspectionRepository: InspectionRepository,
    private val csvExportManager: CsvExportManager,
    private val storageAccessManager: StorageAccessManager,
    initialDisposition: String? = null
) : ViewModel() {

    private data class FilterState(
        val searchQuery: String = "",
        val dispositionFilter: HistoryDispositionFilter = HistoryDispositionFilter.ALL,
        val sortOrder: HistorySortOrder = HistorySortOrder.NEWEST_FIRST,
        val statusMessage: String? = null
    )

    private val resolvedInitialFilter = initialDisposition?.let {
        runCatching { HistoryDispositionFilter.valueOf(it) }.getOrNull()
    } ?: HistoryDispositionFilter.ALL

    private val _filterState = MutableStateFlow(FilterState(dispositionFilter = resolvedInitialFilter))
    private var latestAllRecords: List<InspectionRecord> = emptyList()
    private var pendingSafCsvContent: String = ""

    val uiState: StateFlow<HistoryUiState> = combine(
        inspectionRepository.observeAllRecords(),
        _filterState
    ) { allRecords, filters ->
        latestAllRecords = allRecords
        val filtered = filterAndSort(
            records = allRecords,
            query = filters.searchQuery,
            filter = filters.dispositionFilter,
            sortOrder = filters.sortOrder
        )
        HistoryUiState(
            searchQuery = filters.searchQuery,
            dispositionFilter = filters.dispositionFilter,
            sortOrder = filters.sortOrder,
            totalRecordsCount = allRecords.size,
            filteredRecords = filtered,
            statusMessage = filters.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HistoryUiState(dispositionFilter = resolvedInitialFilter)
    )

    fun onSearchQueryChanged(query: String) {
        _filterState.update { it.copy(searchQuery = query) }
    }

    fun onDispositionFilterSelected(filter: HistoryDispositionFilter) {
        _filterState.update { it.copy(dispositionFilter = filter) }
    }

    fun onSortOrderSelected(sortOrder: HistorySortOrder) {
        _filterState.update { it.copy(sortOrder = sortOrder) }
    }

    fun stageExportAllCsv(): String {
        val recordsToExport = uiState.value.filteredRecords.ifEmpty { latestAllRecords }
        pendingSafCsvContent = csvExportManager.serializeRecordsToCsv(recordsToExport)
        return csvExportManager.suggestedFileNameForAll()
    }

    fun stageExportSingleCsv(record: InspectionRecord): String {
        pendingSafCsvContent = csvExportManager.serializeRecordsToCsv(listOf(record))
        return csvExportManager.suggestedFileNameForRecord(record.inspectionId)
    }

    fun writeStagedCsvToSafUri(uri: Uri) {
        val payload = pendingSafCsvContent
        if (payload.isBlank()) return
        viewModelScope.launch {
            storageAccessManager.writeCsvToSafUri(uri, payload)
                .onSuccess {
                    _filterState.update { it.copy(statusMessage = "CSV exported successfully via SAF.") }
                }
                .onFailure { err ->
                    _filterState.update {
                        it.copy(statusMessage = "CSV export failed: ${err.message ?: "unknown error"}")
                    }
                }
        }
    }

    fun shareRecordCsv(record: InspectionRecord, onUriReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val csv = csvExportManager.serializeRecordsToCsv(listOf(record))
            val name = csvExportManager.suggestedFileNameForRecord(record.inspectionId)
            storageAccessManager.prepareShareableCsvUri(name, csv)
                .onSuccess { onUriReady(it) }
                .onFailure { err ->
                    _filterState.update {
                        it.copy(statusMessage = "Share failed: ${err.message ?: "unknown error"}")
                    }
                }
        }
    }

    fun shareAllCsv(onUriReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val records = uiState.value.filteredRecords.ifEmpty { latestAllRecords }
            val csv = csvExportManager.serializeRecordsToCsv(records)
            val name = csvExportManager.suggestedFileNameForAll()
            storageAccessManager.prepareShareableCsvUri(name, csv)
                .onSuccess { onUriReady(it) }
                .onFailure { err ->
                    _filterState.update {
                        it.copy(statusMessage = "Share failed: ${err.message ?: "unknown error"}")
                    }
                }
        }
    }

    fun clearStatusMessage() {
        _filterState.update { it.copy(statusMessage = null) }
    }

    private fun filterAndSort(
        records: List<InspectionRecord>,
        query: String,
        filter: HistoryDispositionFilter,
        sortOrder: HistorySortOrder
    ): List<InspectionRecord> {
        val trimmed = query.trim().lowercase()
        val afterFilter = records.filter { rec ->
            val matchesDisposition = when (filter) {
                HistoryDispositionFilter.ALL -> true
                HistoryDispositionFilter.PASS -> rec.officialDisposition == InspectionDisposition.PASS
                HistoryDispositionFilter.REVIEW -> rec.officialDisposition == InspectionDisposition.REVIEW
                HistoryDispositionFilter.REJECT -> rec.officialDisposition == InspectionDisposition.REJECT
                HistoryDispositionFilter.INCOMPLETE -> rec.officialDisposition == InspectionDisposition.INCOMPLETE
            }
            val matchesQuery = trimmed.isEmpty() ||
                rec.inspectionId.lowercase().contains(trimmed) ||
                rec.boardName.lowercase().contains(trimmed) ||
                rec.defects.any { d ->
                    d.canonicalClassName.lowercase().contains(trimmed) ||
                        d.rawClassName.lowercase().contains(trimmed)
                }
            matchesDisposition && matchesQuery
        }

        return when (sortOrder) {
            HistorySortOrder.NEWEST_FIRST -> afterFilter.sortedByDescending { it.timestampEpochMs }
            HistorySortOrder.OLDEST_FIRST -> afterFilter.sortedBy { it.timestampEpochMs }
            HistorySortOrder.MOST_DEFECTS -> afterFilter.sortedWith(
                compareByDescending<InspectionRecord> { it.totalDefects }
                    .thenByDescending { it.timestampEpochMs }
            )
            HistorySortOrder.HIGHEST_SEVERITY -> afterFilter.sortedWith(
                compareByDescending<InspectionRecord> { severityRank(it) }
                    .thenByDescending { it.timestampEpochMs }
            )
        }
    }

    private fun severityRank(record: InspectionRecord): Int {
        if (record.defects.isEmpty()) {
            return if (record.officialDisposition == InspectionDisposition.INCOMPLETE) 1 else 0
        }
        return record.defects.maxOf { defect ->
            when (defect.severity) {
                DefectSeverity.CRITICAL -> 5
                DefectSeverity.MEDIUM -> 4
                DefectSeverity.MANUAL_REVIEW,
                DefectSeverity.REQUIRES_DATASET_VERIFICATION -> 3
                DefectSeverity.LOW -> 2
            }
        }
    }

    class Factory(
        private val inspectionRepository: InspectionRepository,
        private val csvExportManager: CsvExportManager,
        private val storageAccessManager: StorageAccessManager,
        private val initialDisposition: String? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(
                inspectionRepository = inspectionRepository,
                csvExportManager = csvExportManager,
                storageAccessManager = storageAccessManager,
                initialDisposition = initialDisposition
            ) as T
        }
    }
}
