package com.example.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.ModelManager
import com.example.domain.usecase.ComputeDashboardMetricsUseCase
import com.example.domain.usecase.DashboardMetrics
import com.example.domain.usecase.DashboardPeriod
import com.example.domain.usecase.TrendMetric
import com.example.domain.usecase.TrendWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DashboardUiState(
    val selectedPeriod: DashboardPeriod = DashboardPeriod.ALL_TIME,
    val selectedTrendWindow: TrendWindow = TrendWindow.LAST_7_DAYS,
    val selectedTrendMetric: TrendMetric = TrendMetric.DEFECT_COUNT,
    val metrics: DashboardMetrics = DashboardMetrics(),
    val detectionModelInfo: ModelInfo,
    val advisoryModelInfo: ModelInfo,
    val transistorModelInfo: ModelInfo,
    val isLoaded: Boolean = false
) {
    val hasRuntimeAlert: Boolean
        get() = detectionModelInfo.state != ModelRuntimeState.READY ||
            advisoryModelInfo.state != ModelRuntimeState.READY ||
            transistorModelInfo.state != ModelRuntimeState.READY
}

class DashboardViewModel(
    inspectionRepository: InspectionRepository,
    modelManager: ModelManager,
    private val computeDashboardMetricsUseCase: ComputeDashboardMetricsUseCase
) : ViewModel() {

    private val selectedPeriod = MutableStateFlow(DashboardPeriod.ALL_TIME)
    private val selectedTrendWindow = MutableStateFlow(TrendWindow.LAST_7_DAYS)
    private val selectedTrendMetric = MutableStateFlow(TrendMetric.DEFECT_COUNT)

    private val filterParams = combine(
        selectedPeriod,
        selectedTrendWindow,
        selectedTrendMetric
    ) { period, window, metric -> Triple(period, window, metric) }

    val uiState: StateFlow<DashboardUiState> = combine(
        inspectionRepository.observeAllRecords(),
        filterParams,
        modelManager.detectionModelInfo,
        modelManager.advisoryModelInfo,
        modelManager.transistorModelInfo
    ) { records, (period, window, metric), detInfo, advInfo, transInfo ->
        val metrics = computeDashboardMetricsUseCase.compute(
            allRecords = records,
            period = period,
            trendWindow = window
        )
        DashboardUiState(
            selectedPeriod = period,
            selectedTrendWindow = window,
            selectedTrendMetric = metric,
            metrics = metrics,
            detectionModelInfo = detInfo,
            advisoryModelInfo = advInfo,
            transistorModelInfo = transInfo,
            isLoaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(
            detectionModelInfo = modelManager.detectionModelInfo.value,
            advisoryModelInfo = modelManager.advisoryModelInfo.value,
            transistorModelInfo = modelManager.transistorModelInfo.value
        )
    )

    fun selectPeriod(period: DashboardPeriod) {
        selectedPeriod.value = period
    }

    fun selectTrendWindow(window: TrendWindow) {
        selectedTrendWindow.value = window
    }

    fun selectTrendMetric(metric: TrendMetric) {
        selectedTrendMetric.value = metric
    }

    class Factory(
        private val inspectionRepository: InspectionRepository,
        private val modelManager: ModelManager,
        private val computeDashboardMetricsUseCase: ComputeDashboardMetricsUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                inspectionRepository = inspectionRepository,
                modelManager = modelManager,
                computeDashboardMetricsUseCase = computeDashboardMetricsUseCase
            ) as T
        }
    }
}
