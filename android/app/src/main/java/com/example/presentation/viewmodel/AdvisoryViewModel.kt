package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.InspectionRecord
import com.example.domain.model.ModelInfo
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.ModelManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AdvisoryUiState(
    val isLoading: Boolean = true,
    val record: InspectionRecord? = null,
    val advisoryModelInfo: ModelInfo
)

class AdvisoryViewModel(
    inspectionId: String,
    inspectionRepository: InspectionRepository,
    modelManager: ModelManager
) : ViewModel() {

    val uiState: StateFlow<AdvisoryUiState> = combine(
        inspectionRepository.observeRecordById(inspectionId),
        modelManager.advisoryModelInfo
    ) { record, modelInfo ->
        AdvisoryUiState(
            isLoading = false,
            record = record,
            advisoryModelInfo = modelInfo
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AdvisoryUiState(
            advisoryModelInfo = modelManager.advisoryModelInfo.value
        )
    )

    class Factory(
        private val inspectionId: String,
        private val inspectionRepository: InspectionRepository,
        private val modelManager: ModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AdvisoryViewModel(
                inspectionId = inspectionId,
                inspectionRepository = inspectionRepository,
                modelManager = modelManager
            ) as T
        }
    }
}
