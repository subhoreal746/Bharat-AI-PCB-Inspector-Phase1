package com.example.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.app.AppContainer
import com.example.presentation.components.AdaptiveInspectionScaffold
import com.example.presentation.screens.AdvisoryScreen
import com.example.presentation.screens.DashboardScreen
import com.example.presentation.screens.HistoryScreen
import com.example.presentation.screens.InspectScreen
import com.example.presentation.screens.ResultsScreen
import com.example.presentation.screens.SettingsScreen
import com.example.presentation.viewmodel.AdvisoryViewModel
import com.example.presentation.viewmodel.DashboardViewModel
import com.example.presentation.viewmodel.HistoryViewModel
import com.example.presentation.viewmodel.InspectViewModel
import com.example.presentation.viewmodel.ResultsViewModel
import com.example.presentation.viewmodel.SettingsViewModel

@Composable
fun AppNavGraph(
    widthSizeClass: WindowWidthSizeClass,
    container: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val currentTopLevel = when {
        currentDestination?.hasRoute<AppRoute.Dashboard>() == true -> TopLevelDestination.DASHBOARD
        currentDestination?.hasRoute<AppRoute.Inspect>() == true -> TopLevelDestination.INSPECT
        currentDestination?.hasRoute<AppRoute.History>() == true -> TopLevelDestination.HISTORY
        currentDestination?.hasRoute<AppRoute.Settings>() == true -> TopLevelDestination.SETTINGS
        else -> null
    }

    fun navigateToTopLevel(destination: TopLevelDestination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    AdaptiveInspectionScaffold(
        widthSizeClass = widthSizeClass,
        currentTopLevel = currentTopLevel,
        onSelectDestination = ::navigateToTopLevel
    ) { contentModifier ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.Dashboard,
            modifier = contentModifier
        ) {
            composable<AppRoute.Dashboard> {
                val vm: DashboardViewModel = viewModel(
                    factory = DashboardViewModel.Factory(
                        inspectionRepository = container.inspectionRepository,
                        modelManager = container.modelManager,
                        computeDashboardMetricsUseCase = container.computeDashboardMetricsUseCase
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                DashboardScreen(
                    uiState = uiState,
                    onSelectPeriod = vm::selectPeriod,
                    onSelectTrendWindow = vm::selectTrendWindow,
                    onSelectTrendMetric = vm::selectTrendMetric,
                    onStartInspectionClick = { navigateToTopLevel(TopLevelDestination.INSPECT) },
                    onHistoryClick = { navigateToTopLevel(TopLevelDestination.HISTORY) },
                    onRecordClick = { recordId -> navController.navigate(AppRoute.Results(recordId)) },
                    onSettingsClick = { navigateToTopLevel(TopLevelDestination.SETTINGS) },
                    onDispositionClick = { disposition ->
                        navController.navigate(AppRoute.History(initialDisposition = disposition.name))
                    }
                )
            }

            composable<AppRoute.Inspect> {
                val vm: InspectViewModel = viewModel(
                    factory = InspectViewModel.Factory(
                        imageStorageManager = container.imageStorageManager,
                        settingsRepository = container.settingsRepository,
                        runInspectionUseCase = container.runInspectionUseCase,
                        modelManager = container.modelManager
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                InspectScreen(
                    uiState = uiState,
                    allocateCameraCaptureDestination = vm::allocateCameraCaptureDestination,
                    onSetLiveCameraActive = vm::setLiveCameraActive,
                    onCameraImageCaptured = vm::onCameraImageCaptured,
                    onCameraCaptureError = vm::onCameraCaptureError,
                    onGalleryImagePicked = vm::onGalleryImagePicked,
                    onSamplePresetSelected = vm::onSamplePresetSelected,
                    onBoardNameChanged = vm::onBoardNameChanged,
                    onClearSelectedImage = vm::clearSelectedImage,
                    onDismissError = vm::dismissError,
                    onStartInspectionClick = {
                        vm.startInspection { recordId ->
                            navController.navigate(AppRoute.Results(recordId))
                        }
                    },
                    onOpenSettingsClick = { navigateToTopLevel(TopLevelDestination.SETTINGS) },
                    onTargetSelected = vm::onTargetSelected
                )
            }

            composable<AppRoute.History> { backStackEntry ->
                val historyRoute = backStackEntry.toRoute<AppRoute.History>()
                val vm: HistoryViewModel = viewModel(
                    key = "history_${historyRoute.initialDisposition}",
                    factory = HistoryViewModel.Factory(
                        inspectionRepository = container.inspectionRepository,
                        csvExportManager = container.csvExportManager,
                        storageAccessManager = container.storageAccessManager,
                        initialDisposition = historyRoute.initialDisposition
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                HistoryScreen(
                    uiState = uiState,
                    onSearchQueryChanged = vm::onSearchQueryChanged,
                    onDispositionFilterSelected = vm::onDispositionFilterSelected,
                    onSortOrderSelected = vm::onSortOrderSelected,
                    onStageExportAllCsv = vm::stageExportAllCsv,
                    onStageExportSingleCsv = vm::stageExportSingleCsv,
                    onSafUriSelected = vm::writeStagedCsvToSafUri,
                    onShareAllCsv = vm::shareAllCsv,
                    onShareRecordCsv = vm::shareRecordCsv,
                    onRecordClick = { recordId -> navController.navigate(AppRoute.Results(recordId)) },
                    onClearStatusMessage = vm::clearStatusMessage
                )
            }

            composable<AppRoute.Settings> {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        settingsRepository = container.settingsRepository,
                        modelManager = container.modelManager,
                        inspectionRepository = container.inspectionRepository,
                        imageStorageManager = container.imageStorageManager,
                        csvLogger = container.csvLogger,
                        csvExportManager = container.csvExportManager,
                        storageAccessManager = container.storageAccessManager
                    )
                )
                LaunchedEffect(Unit) {
                    vm.refreshStorageStats()
                }
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    uiState = uiState,
                    suggestedAllCsvFilename = vm.suggestedAllCsvFilename(),
                    onSetThemeMode = vm::setThemeMode,
                    onUpdatePolicyConfig = vm::updatePolicyConfig,
                    onResetPolicyDefaults = vm::resetPolicyDefaults,
                    onImportDetectionModel = vm::importDetectionModel,
                    onLoadDetectionModel = vm::loadDetectionModel,
                    onUnloadDetectionModel = vm::unloadDetectionModel,
                    onImportTransistorModel = vm::importTransistorModel,
                    onLoadTransistorModel = vm::loadTransistorModel,
                    onUnloadTransistorModel = vm::unloadTransistorModel,
                    onImportAdvisoryModel = vm::importAdvisoryModel,
                    onImportAdvisoryTokenizer = vm::importAdvisoryTokenizer,
                    onLoadAdvisoryModel = vm::loadAdvisoryModel,
                    onUnloadAdvisoryModel = vm::unloadAdvisoryModel,
                    onInspectModelMetadata = vm::inspectModelMetadata,
                    onDismissMetadataDialog = vm::dismissMetadataDialog,
                    onDismissModelStatusBanner = vm::dismissModelStatusBanner,
                    onReconcileCsvOutbox = vm::reconcileCsvOutbox,
                    onExportAllCsvToSafUri = vm::exportAllCsvToSafUri,
                    onRequestClearHistory = vm::requestClearHistory,
                    onDismissClearHistory = vm::dismissClearHistory,
                    onConfirmClearHistory = vm::confirmClearHistory,
                    onClearStorageMessage = vm::clearStorageMessage
                )
            }

            composable<AppRoute.Results> { entry ->
                val args = entry.toRoute<AppRoute.Results>()
                val vm: ResultsViewModel = viewModel(
                    key = "results_${args.recordId}",
                    factory = ResultsViewModel.Factory(
                        inspectionId = args.recordId,
                        inspectionRepository = container.inspectionRepository,
                        csvExportManager = container.csvExportManager,
                        storageAccessManager = container.storageAccessManager
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                ResultsScreen(
                    uiState = uiState,
                    suggestedCsvFilename = vm.suggestedCsvFilename(),
                    onBackClick = { navController.popBackStack() },
                    onSetAnnotatedOverlay = vm::setAnnotatedOverlay,
                    onSelectDefect = vm::selectDefect,
                    onToggleProvenance = vm::toggleProvenanceExpanded,
                    onOpenAdvisoryClick = { id -> navController.navigate(AppRoute.Advisory(id)) },
                    onSafExportUriSelected = vm::exportToSafUri,
                    onShareCsvClick = vm::prepareShareUri,
                    onClearStatusMessage = vm::clearStatusMessage
                )
            }

            composable<AppRoute.Advisory> { entry ->
                val args = entry.toRoute<AppRoute.Advisory>()
                val vm: AdvisoryViewModel = viewModel(
                    key = "advisory_${args.recordId}",
                    factory = AdvisoryViewModel.Factory(
                        inspectionId = args.recordId,
                        inspectionRepository = container.inspectionRepository,
                        modelManager = container.modelManager
                    )
                )
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                AdvisoryScreen(
                    uiState = uiState,
                    onBackClick = { navController.popBackStack() },
                    onOpenSettingsClick = { navigateToTopLevel(TopLevelDestination.SETTINGS) }
                )
            }
        }
    }
}
