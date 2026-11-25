package com.example.presentation

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.csv.CsvExportManagerImpl
import com.example.data.local.database.PcbInspectorDatabase
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.data.repository.InspectionRepositoryImpl
import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.ModelInfo
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.domain.model.PcbImageSource
import com.example.domain.model.SamplePcbPreset
import com.example.domain.model.TransistorAnomalyData
import com.example.domain.policy.InspectionPolicyEngine
import com.example.domain.repository.ImageStorageManager
import com.example.domain.repository.InspectionCsvLogger
import com.example.domain.repository.StorageAccessManager
import com.example.domain.usecase.ComputeDashboardMetricsUseCase
import com.example.domain.usecase.RunInspectionUseCase
import com.example.inference.ModelManagerImpl
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.demo.DemoAdvisoryEngine
import com.example.inference.demo.DemoPcbDetectionEngine
import com.example.inference.demo.SamplePcbCatalog
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import com.example.presentation.viewmodel.DashboardViewModel
import com.example.presentation.viewmodel.HistoryDispositionFilter
import com.example.presentation.viewmodel.HistoryViewModel
import com.example.presentation.viewmodel.InspectUiState
import com.example.presentation.viewmodel.InspectViewModel
import com.example.presentation.viewmodel.SettingsViewModel
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Prompt5UiAndViewModelIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: PcbInspectorDatabase
    private lateinit var repository: InspectionRepositoryImpl
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var onnxEngine: OnnxPcbDetectionEngine
    private lateinit var execuTorchEngine: ExecuTorchAdvisoryEngine
    private lateinit var patchCoreEngine: PatchCoreTransistorEngine
    private lateinit var modelManager: ModelManagerImpl
    private lateinit var csvExportManager: CsvExportManagerImpl
    private lateinit var storageAccessManager: StorageAccessManager

    private val fakeStorageAccessManager = object : StorageAccessManager {
        override suspend fun writeCsvToSafUri(uri: Uri, csvContent: String): Result<Unit> = Result.success(Unit)
        override suspend fun prepareShareableCsvUri(fileName: String, csvContent: String): Result<Uri> = Result.success(Uri.EMPTY)
    }

    private val fakeCsvLogger = object : InspectionCsvLogger {
        override suspend fun processPendingOutboxEvents(): com.example.domain.repository.CsvAuditStatus =
            com.example.domain.repository.CsvAuditStatus(0, 0, 0)
        override suspend fun regenerateAuditCsvFromRoom(): Result<String> = Result.success("")
        override suspend fun clearAuditLogAndLedger() = Unit
        override suspend fun getAuditFileSizeBytes(): Long = 0L
        override fun getAuditFilePath(): String = "test.csv"
    }

    private val fakeImageStorageManager = object : ImageStorageManager {
        override suspend fun createCameraCaptureDestination(): Pair<String, Uri> = "" to Uri.EMPTY
        override suspend fun importPickedImageToAppStorage(sourceUri: Uri): Result<String> = Result.success("file:///test.jpg")
        override suspend fun materializeSamplePreset(preset: SamplePcbPreset): Result<String> = Result.success("file:///sample.jpg")
        override suspend fun validateAndAssessClarity(imageUriString: String, minWidth: Int, minHeight: Int): ImageValidationResult =
            ImageValidationResult(
                isValid = true,
                isProcessable = true,
                width = 512,
                height = 512,
                clarityScore = 150.0,
                lowClarityRisk = false
            )
        override suspend fun cleanupOrphanedAndEmptyFiles(protectedUris: Set<String>): Int = 0
        override suspend fun deleteAllUnreferencedCaptures(protectedUris: Set<String>): Int = 0
        override suspend fun deleteUnreferencedCaptureIfEmpty(uriString: String?) = Unit
        override suspend fun getImageStorageBytes(): Long = 0L
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PcbInspectorDatabase::class.java)
            .addMigrations(PcbInspectorDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        repository = InspectionRepositoryImpl(db.inspectionDao())

        val dataStoreFile = tempFolder.newFile("test_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create { dataStoreFile }
        settingsRepository = SettingsRepositoryImpl(dataStore)

        onnxEngine = OnnxPcbDetectionEngine(context, Dispatchers.Unconfined)
        execuTorchEngine = ExecuTorchAdvisoryEngine(Dispatchers.Unconfined)
        patchCoreEngine = PatchCoreTransistorEngine(context, Dispatchers.Unconfined)

        modelManager = ModelManagerImpl(
            context = context,
            settingsRepository = settingsRepository,
            onnxEngine = onnxEngine,
            execuTorchEngine = execuTorchEngine,
            patchCoreEngine = patchCoreEngine,
            ioDispatcher = Dispatchers.Unconfined
        )

        csvExportManager = CsvExportManagerImpl()
        storageAccessManager = fakeStorageAccessManager
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun createSampleRecord(
        id: String,
        target: InspectionTarget,
        disposition: InspectionDisposition,
        transistorAnomaly: TransistorAnomalyData? = null
    ): InspectionRecord = InspectionRecord(
        inspectionId = id,
        timestampEpochMs = 1731800000000L,
        boardName = "Test Unit",
        sourceType = PcbImageSource.GALLERY,
        imageUri = "file:///test.jpg",
        imageWidth = 512,
        imageHeight = 512,
        lowClarityRisk = false,
        clarityScore = 150.0,
        officialDisposition = disposition,
        policyProfile = "Baseline",
        policyVersion = "1.0",
        policyConfigSnapshot = InspectionPolicyConfig(),
        effectiveThresholdsSummary = "summary",
        policyRationale = "rationale",
        defects = if (disposition == InspectionDisposition.REVIEW) listOf(
            DetectedDefect(
                defectId = "$id-d1",
                classIndex = 0,
                rawClassName = "TR-ANOMALY-REVIEW",
                canonicalClassName = "Transistor Anomaly",
                confidence = 0.85f,
                severity = DefectSeverity.MANUAL_REVIEW,
                requiresManualReview = true,
                boundingBox = BoundingBox(0.1f, 0.2f, 0.3f, 0.4f),
                code = "TR-ANOMALY-REVIEW"
            )
        ) else emptyList(),
        totalDefects = if (disposition == InspectionDisposition.REVIEW) 1 else 0,
        isDemonstration = false,
        provenance = ModelExecutionProvenance.unavailableProduction("test"),
        advisory = com.example.domain.model.AiAdvisory(
            availability = com.example.domain.model.AdvisoryAvailability.UNAVAILABLE,
            isDemonstration = false
        ),
        latencyMetrics = InspectionLatencyMetrics(patchCoreInferenceMs = 45L),
        target = target,
        transistorAnomaly = transistorAnomaly,
        completedEpochMs = 1731800001000L
    )

    @Test
    fun `historyViewModel initializes with initial disposition filter and can be cleared`() = runBlocking {
        repository.saveInspectionAtomically(
            createSampleRecord("R1", InspectionTarget.PCB_BOARD, InspectionDisposition.PASS)
        )
        repository.saveInspectionAtomically(
            createSampleRecord("R2", InspectionTarget.TRANSISTOR, InspectionDisposition.REVIEW)
        )
        repository.saveInspectionAtomically(
            createSampleRecord("R3", InspectionTarget.PCB_BOARD, InspectionDisposition.REJECT)
        )

        // 1. Initial disposition = REVIEW
        val reviewVm = HistoryViewModel(
            inspectionRepository = repository,
            csvExportManager = csvExportManager,
            storageAccessManager = storageAccessManager,
            initialDisposition = "REVIEW"
        )
        val job1 = launch { reviewVm.uiState.collect() }
        val reviewState = reviewVm.uiState.first { it.dispositionFilter == HistoryDispositionFilter.REVIEW }
        assertEquals(HistoryDispositionFilter.REVIEW, reviewState.dispositionFilter)

        // 2. Initial disposition = PASS
        val passVm = HistoryViewModel(
            inspectionRepository = repository,
            csvExportManager = csvExportManager,
            storageAccessManager = storageAccessManager,
            initialDisposition = "PASS"
        )
        val job2 = launch { passVm.uiState.collect() }
        val passState = passVm.uiState.first { it.dispositionFilter == HistoryDispositionFilter.PASS }
        assertEquals(HistoryDispositionFilter.PASS, passState.dispositionFilter)

        // 3. Reset filter back to ALL
        reviewVm.onDispositionFilterSelected(HistoryDispositionFilter.ALL)
        val stateAfterReset = reviewVm.uiState.first { it.dispositionFilter == HistoryDispositionFilter.ALL }
        assertEquals(HistoryDispositionFilter.ALL, stateAfterReset.dispositionFilter)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `inspectViewModel toggles target and clears sample presets when switching to transistor`() = runBlocking {
        val runInspectionUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runInspectionUseCase,
            modelManager = modelManager
        )
        val job = launch { vm.uiState.collect() }

        // Initial target is PCB_BOARD
        assertEquals(InspectionTarget.PCB_BOARD, vm.uiState.value.selectedTarget)

        // Select a sample preset on PCB_BOARD
        vm.onSamplePresetSelected(SamplePcbCatalog.presets.first())
        val stateAfterSample = vm.uiState.first { it.selectedImageUri != null }
        assertNotNull(stateAfterSample.selectedImageUri)
        assertEquals(PcbImageSource.SAMPLE, stateAfterSample.selectedSourceType)

        // Switch target to TRANSISTOR -> Sample preset must be cleared!
        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        val stateAfterTargetSwitch = vm.uiState.first { it.selectedTarget == InspectionTarget.TRANSISTOR }
        assertEquals(InspectionTarget.TRANSISTOR, stateAfterTargetSwitch.selectedTarget)
        assertNull(stateAfterTargetSwitch.selectedImageUri)
        assertNull(stateAfterTargetSwitch.selectedSourceType)
        assertFalse(stateAfterTargetSwitch.canStartInspection)

        job.cancel()
    }

    @Test
    fun `inspectViewModel gates canStartInspection for transistor mode requiring ready model and non-sample source`() {
        val readyModelInfo = ModelInfo(
            slot = ModelSlot.TRANSISTOR_ANOMALY,
            displayName = "PatchCore Transistor",
            runtimeLabel = "ONNX",
            state = ModelRuntimeState.READY,
            detailMessage = "Ready"
        )
        val notLoadedModelInfo = ModelInfo(
            slot = ModelSlot.TRANSISTOR_ANOMALY,
            displayName = "PatchCore Transistor",
            runtimeLabel = "ONNX",
            state = ModelRuntimeState.NOT_LOADED,
            detailMessage = "Not Loaded"
        )
        val validValidation = ImageValidationResult(
            isValid = true,
            isProcessable = true,
            width = 512,
            height = 512,
            clarityScore = 150.0,
            lowClarityRisk = false
        )

        // Case 1: Transistor target with NOT_LOADED model -> cannot start
        val stateNotLoaded = InspectUiState(
            selectedTarget = InspectionTarget.TRANSISTOR,
            selectedImageUri = "file:///img.jpg",
            selectedSourceType = PcbImageSource.GALLERY,
            validation = validValidation,
            detectionModelInfo = notLoadedModelInfo,
            advisoryModelInfo = notLoadedModelInfo,
            transistorModelInfo = notLoadedModelInfo
        )
        assertFalse(stateNotLoaded.canStartInspection)

        // Case 2: Transistor target with SAMPLE source -> cannot start even if model is READY
        val stateSampleSource = InspectUiState(
            selectedTarget = InspectionTarget.TRANSISTOR,
            selectedImageUri = "file:///img.jpg",
            selectedSourceType = PcbImageSource.SAMPLE,
            validation = validValidation,
            detectionModelInfo = notLoadedModelInfo,
            advisoryModelInfo = notLoadedModelInfo,
            transistorModelInfo = readyModelInfo
        )
        assertFalse(stateSampleSource.canStartInspection)

        // Case 3: Transistor target with GALLERY and READY model -> can start!
        val stateReady = InspectUiState(
            selectedTarget = InspectionTarget.TRANSISTOR,
            selectedImageUri = "file:///img.jpg",
            selectedSourceType = PcbImageSource.GALLERY,
            validation = validValidation,
            detectionModelInfo = notLoadedModelInfo,
            advisoryModelInfo = notLoadedModelInfo,
            transistorModelInfo = readyModelInfo
        )
        assertTrue(stateReady.canStartInspection)
    }

    @Test
    fun `settingsViewModel exposes transistor slot and handles metadata inspection`() = runBlocking {
        val vm = SettingsViewModel(
            settingsRepository = settingsRepository,
            modelManager = modelManager,
            inspectionRepository = repository,
            imageStorageManager = fakeImageStorageManager,
            csvLogger = fakeCsvLogger,
            csvExportManager = csvExportManager,
            storageAccessManager = storageAccessManager
        )
        val job = launch { vm.uiState.collect() }

        val state = vm.uiState.first { it.transistorModelInfo.slot == ModelSlot.TRANSISTOR_ANOMALY }
        assertEquals(ModelSlot.TRANSISTOR_ANOMALY, state.transistorModelInfo.slot)
        assertEquals(ModelRuntimeState.NOT_LOADED, state.transistorModelInfo.state)

        // Inspect metadata for Transistor Anomaly slot
        vm.inspectModelMetadata(ModelSlot.TRANSISTOR_ANOMALY)
        val stateAfterInspect = vm.uiState.first { it.inspectedMetadataTitle != null }
        assertEquals("PatchCore Unsupervised Anomaly Metadata", stateAfterInspect.inspectedMetadataTitle)
        assertNotNull(stateAfterInspect.inspectedMetadataBody)

        job.cancel()
    }

    @Test
    fun `dashboardViewModel computes counts and exposes transistorModelInfo`() = runBlocking {
        repository.saveInspectionAtomically(
            createSampleRecord("R1", InspectionTarget.PCB_BOARD, InspectionDisposition.PASS)
        )
        repository.saveInspectionAtomically(
            createSampleRecord("R2", InspectionTarget.TRANSISTOR, InspectionDisposition.REVIEW)
        )

        val vm = DashboardViewModel(
            inspectionRepository = repository,
            modelManager = modelManager,
            computeDashboardMetricsUseCase = ComputeDashboardMetricsUseCase()
        )
        val job = launch { vm.uiState.collect() }

        val state = vm.uiState.first { it.metrics.totalInspections == 2 }
        assertEquals(2, state.metrics.totalInspections)
        assertEquals(1, state.metrics.passCount)
        assertEquals(1, state.metrics.reviewCount)
        assertEquals(0, state.metrics.rejectCount)
        assertEquals(ModelSlot.TRANSISTOR_ANOMALY, state.transistorModelInfo.slot)

        job.cancel()
    }

    private class FakeModelManager(
        initialDetection: ModelInfo = ModelInfo(
            slot = ModelSlot.PCB_DETECTION,
            displayName = "YOLOv8n Detector",
            runtimeLabel = "ONNX",
            state = ModelRuntimeState.NOT_LOADED,
            detailMessage = "Not loaded"
        ),
        initialAdvisory: ModelInfo = ModelInfo(
            slot = ModelSlot.AI_ADVISORY,
            displayName = "ExecuTorch Advisory",
            runtimeLabel = "ExecuTorch",
            state = ModelRuntimeState.NOT_LOADED,
            detailMessage = "Not loaded"
        ),
        initialTransistor: ModelInfo = ModelInfo(
            slot = ModelSlot.TRANSISTOR_ANOMALY,
            displayName = "PatchCore Transistor",
            runtimeLabel = "ONNX",
            state = ModelRuntimeState.NOT_LOADED,
            detailMessage = "Not loaded"
        )
    ) : com.example.domain.repository.ModelManager {
        val detectionFlow = MutableStateFlow(initialDetection)
        val advisoryFlow = MutableStateFlow(initialAdvisory)
        val transistorFlow = MutableStateFlow(initialTransistor)

        override val detectionModelInfo: StateFlow<ModelInfo> = detectionFlow
        override val advisoryModelInfo: StateFlow<ModelInfo> = advisoryFlow
        override val transistorModelInfo: StateFlow<ModelInfo> = transistorFlow

        override suspend fun importDetectionModel(sourceUri: Uri) = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun loadDetectionModel() = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun unloadDetectionModel() = com.example.inference.cv.ModelOperationResult(true, "ok")

        override suspend fun importAdvisoryModel(sourceUri: Uri) = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun importAdvisoryTokenizer(sourceUri: Uri) = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun loadAdvisoryModel() = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun unloadAdvisoryModel() = com.example.inference.cv.ModelOperationResult(true, "ok")

        override suspend fun importTransistorModel(sourceUri: Uri) = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun loadTransistorModel() = com.example.inference.cv.ModelOperationResult(true, "ok")
        override suspend fun unloadTransistorModel() = com.example.inference.cv.ModelOperationResult(true, "ok")

        override fun inspectModelMetadata(slot: ModelSlot): String = "fake metadata"
    }

    @Test
    fun `startInspection executes successfully when transistor model transitions to READY in uiState while localState has initial snapshot`() = runBlocking {
        val fakeManager = FakeModelManager()
        val runUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        // Switch to transistor and pick an image
        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        val stateAfterPick = vm.uiState.first { it.selectedImageUri != null }

        // At this point, fakeManager has NOT_LOADED transistor model
        assertEquals(ModelRuntimeState.NOT_LOADED, stateAfterPick.transistorModelInfo.state)
        assertFalse(stateAfterPick.canStartInspection)

        // Now transition model manager state to READY (simulating model import/load in Settings)
        fakeManager.transistorFlow.value = fakeManager.transistorFlow.value.copy(
            state = ModelRuntimeState.READY,
            detailMessage = "PatchCore Ready"
        )

        // uiState now reflects READY
        val readyState = vm.uiState.first { it.transistorModelInfo.state == ModelRuntimeState.READY }
        assertTrue(readyState.canStartInspection)

        // Invoke startInspection. Because startInspection reads consolidated state, it must execute successfully!
        var completedInspectionId: String? = null
        vm.startInspection { recordId ->
            completedInspectionId = recordId
        }
        vm.uiState.first { completedInspectionId != null }

        assertNotNull(completedInspectionId)
        assertTrue(completedInspectionId!!.isNotBlank())
        assertNull(vm.uiState.value.errorMessage)

        job.cancel()
    }

    @Test
    fun `startInspection reports visible error when PatchCore model is UNLOADED`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.UNLOADED,
                detailMessage = "Model unloaded"
            )
        )
        val runUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        vm.uiState.first { it.selectedImageUri != null }

        var completed = false
        vm.startInspection { completed = true }

        assertFalse(completed)
        val errorState = vm.uiState.first { it.errorMessage != null }
        assertNotNull(errorState.errorMessage)
        assertTrue(errorState.errorMessage!!.contains("UNLOADED"))
        assertTrue(errorState.errorMessage!!.contains("Load the PatchCore model in Settings"))

        job.cancel()
    }

    @Test
    fun `startInspection reports visible error when image URI is missing`() = runBlocking {
        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = RunInspectionUseCase(
                imageStorageManager = fakeImageStorageManager,
                policyEngine = InspectionPolicyEngine(),
                productionDetectionEngine = onnxEngine,
                demoDetectionEngine = DemoPcbDetectionEngine(),
                patchCoreEngine = patchCoreEngine,
                productionAdvisoryEngine = execuTorchEngine,
                demoAdvisoryEngine = DemoAdvisoryEngine(),
                inspectionRepository = repository,
                settingsRepository = settingsRepository,
                csvLogger = fakeCsvLogger,
                applicationScope = this,
                defaultDispatcher = Dispatchers.Unconfined
            ),
            modelManager = modelManager
        )
        val job = launch { vm.uiState.collect() }

        var completed = false
        vm.startInspection { completed = true }

        assertFalse(completed)
        val errorState = vm.uiState.first { it.errorMessage != null }
        assertEquals("Please select or capture an image before starting inspection.", errorState.errorMessage)

        job.cancel()
    }

    @Test
    fun `duplicate inspection requests do not launch parallel inspections`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            )
        )
        val executionCount = AtomicInteger(0)
        val countingRepository = object : com.example.domain.repository.InspectionRepository by repository {
            override suspend fun saveInspectionAtomically(record: InspectionRecord) {
                executionCount.incrementAndGet()
                repository.saveInspectionAtomically(record)
            }
        }
        val countingUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = countingRepository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = countingUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        vm.uiState.first { it.selectedImageUri != null }

        var completionCount = 0
        vm.startInspection { completionCount++ }
        // Attempt duplicate immediately while one is running
        vm.startInspection { completionCount++ }
        vm.uiState.first { completionCount > 0 }

        assertEquals(1, completionCount)
        assertEquals(1, executionCount.get())

        job.cancel()
    }

    @Test
    fun `two rapid startInspection calls atomically allow only the first invocation to claim state and run`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            )
        )
        val executionCount = AtomicInteger(0)
        val countingRepository = object : com.example.domain.repository.InspectionRepository by repository {
            override suspend fun saveInspectionAtomically(record: InspectionRecord) {
                executionCount.incrementAndGet()
                repository.saveInspectionAtomically(record)
            }
        }
        val countingUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = countingRepository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = countingUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        vm.uiState.first { it.selectedImageUri != null }

        val completions = mutableListOf<String>()

        // Dispatch two rapid startInspection calls back to back
        vm.startInspection { completions.add("call1:$it") }
        vm.startInspection { completions.add("call2:$it") }

        // Wait for completion
        vm.uiState.first { completions.isNotEmpty() }

        // Exactly one call should have claimed the state and finished
        assertEquals(1, completions.size)
        assertEquals(1, executionCount.get())
        assertTrue(completions.first().startsWith("call1:"))
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isRunningInspection)

        job.cancel()
    }

    @Test
    fun `genuinely concurrent startInspection calls from separate coroutines invoke execute exactly once`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            )
        )
        val executionCount = AtomicInteger(0)
        val countingRepository = object : com.example.domain.repository.InspectionRepository by repository {
            override suspend fun saveInspectionAtomically(record: InspectionRecord) {
                executionCount.incrementAndGet()
                repository.saveInspectionAtomically(record)
            }
        }
        val countingUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = countingRepository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = countingUseCase,
            modelManager = fakeManager
        )
        val stateJob = launch { vm.uiState.collect() }

        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        vm.uiState.first { it.selectedImageUri != null }

        val completions = Collections.synchronizedList(mutableListOf<String>())

        val jobs = (1..6).map { i ->
            async(Dispatchers.Default) {
                vm.startInspection { recordId ->
                    completions.add("caller$i:$recordId")
                }
            }
        }

        jobs.awaitAll()

        vm.uiState.first { completions.isNotEmpty() }

        assertEquals(1, completions.size)
        assertEquals(1, executionCount.get())
        assertFalse(vm.uiState.value.isRunningInspection)
        assertNull(vm.uiState.value.errorMessage)

        stateJob.cancel()
    }

    @Test
    fun `startInspection while image is validating is rejected and use case does not run`() = runBlocking {
        val validateHold = CompletableDeferred<Unit>()
        val validatingStorageManager = object : ImageStorageManager by fakeImageStorageManager {
            override suspend fun validateAndAssessClarity(imageUriString: String, minWidth: Int, minHeight: Int): ImageValidationResult {
                validateHold.await()
                return fakeImageStorageManager.validateAndAssessClarity(imageUriString, minWidth, minHeight)
            }
        }
        val executionCount = AtomicInteger(0)
        val countingRepository = object : com.example.domain.repository.InspectionRepository by repository {
            override suspend fun saveInspectionAtomically(record: InspectionRecord) {
                executionCount.incrementAndGet()
                repository.saveInspectionAtomically(record)
            }
        }
        val countingUseCase = RunInspectionUseCase(
            imageStorageManager = validatingStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = countingRepository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = validatingStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = countingUseCase,
            modelManager = modelManager
        )
        val job = launch { vm.uiState.collect() }

        vm.onGalleryImagePicked(Uri.parse("file:///pcb.jpg"))
        val validatingState = vm.uiState.first { it.isValidating }
        assertTrue(validatingState.isValidating)

        var completed = false
        vm.startInspection { completed = true }

        assertFalse(completed)
        assertEquals(0, executionCount.get())

        validateHold.complete(Unit)
        val validatedState = vm.uiState.first { !it.isValidating }
        assertFalse(validatedState.isValidating)
        assertNotNull(validatedState.selectedImageUri)

        job.cancel()
    }

    @Test
    fun `cancellation releases mutex token and allows subsequent inspection to start`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            )
        )
        var shouldThrowCancellation = true
        val cancellationRepository = object : com.example.domain.repository.InspectionRepository by repository {
            override suspend fun saveInspectionAtomically(record: InspectionRecord) {
                if (shouldThrowCancellation) {
                    throw kotlinx.coroutines.CancellationException("Test synthetic cancellation")
                }
                repository.saveInspectionAtomically(record)
            }
        }
        val runUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = cancellationRepository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        vm.onGalleryImagePicked(Uri.parse("file:///dummy.jpg"))
        vm.uiState.first { it.selectedImageUri != null }

        // First inspection: fails with CancellationException
        var firstCompleted = false
        vm.startInspection { firstCompleted = true }
        vm.uiState.first { !it.isRunningInspection }
        assertFalse(firstCompleted)

        // Second inspection: cancellation disabled -> succeeds, proving mutex was safely unlocked!
        shouldThrowCancellation = false
        var secondCompletedId: String? = null
        vm.startInspection { secondCompletedId = it }
        vm.uiState.first { secondCompletedId != null }

        assertNotNull(secondCompletedId)
        assertFalse(vm.uiState.value.isRunningInspection)

        job.cancel()
    }

    @Test
    fun `target-specific image naming and switching metadata updates correctly`() = runBlocking {
        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = RunInspectionUseCase(
                imageStorageManager = fakeImageStorageManager,
                policyEngine = InspectionPolicyEngine(),
                productionDetectionEngine = onnxEngine,
                demoDetectionEngine = DemoPcbDetectionEngine(),
                patchCoreEngine = patchCoreEngine,
                productionAdvisoryEngine = execuTorchEngine,
                demoAdvisoryEngine = DemoAdvisoryEngine(),
                inspectionRepository = repository,
                settingsRepository = settingsRepository,
                csvLogger = fakeCsvLogger,
                applicationScope = this,
                defaultDispatcher = Dispatchers.Unconfined
            ),
            modelManager = modelManager
        )
        val job = launch { vm.uiState.collect() }

        // 1. PCB Gallery
        vm.onGalleryImagePicked(Uri.parse("file:///pcb.jpg"))
        val pcbPicked = vm.uiState.first { it.selectedImageUri != null }
        assertEquals("Uploaded PCB Board", pcbPicked.boardName)

        // 2. Switch to TRANSISTOR -> updates default boardName to Transistor
        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        val transState = vm.uiState.first { it.selectedTarget == InspectionTarget.TRANSISTOR }
        assertEquals("Uploaded Transistor", transState.boardName)
        assertNotNull(transState.selectedImageUri) // Image preserved!

        // 3. Transistor Camera
        vm.onCameraImageCaptured("file:///transistor_cam.jpg", true)
        val camState = vm.uiState.first { it.selectedSourceType == PcbImageSource.CAMERA }
        assertEquals("Captured Transistor", camState.boardName)

        // 4. Switch back to PCB -> updates default boardName to PCB
        vm.onTargetSelected(InspectionTarget.PCB_BOARD)
        val pcbBackState = vm.uiState.first { it.selectedTarget == InspectionTarget.PCB_BOARD }
        assertEquals("Captured PCB Board", pcbBackState.boardName)

        // 5. User enters custom board name
        vm.onBoardNameChanged("Custom-Unit-42")
        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        val customState = vm.uiState.first { it.selectedTarget == InspectionTarget.TRANSISTOR }
        assertEquals("Custom-Unit-42", customState.boardName) // Custom name preserved!

        job.cancel()
    }

    @Test
    fun `PCB mode startInspection behavior remains unchanged and can record when detector is unready`() = runBlocking {
        val fakeManager = FakeModelManager(
            initialDetection = ModelInfo(
                slot = ModelSlot.PCB_DETECTION,
                displayName = "YOLO",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.NOT_LOADED,
                detailMessage = "Not loaded"
            )
        )
        val runUseCase = RunInspectionUseCase(
            imageStorageManager = fakeImageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = fakeImageStorageManager,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        // PCB mode allows startInspection with NOT_LOADED model -> records INCOMPLETE
        vm.onGalleryImagePicked(Uri.parse("file:///pcb.jpg"))
        val pickedState = vm.uiState.first { it.selectedImageUri != null }
        assertTrue(pickedState.canStartInspection)

        var completedRecordId: String? = null
        vm.startInspection { recordId -> completedRecordId = recordId }
        vm.uiState.first { completedRecordId != null }

        assertNotNull(completedRecordId)
        val record = repository.getRecordById(completedRecordId!!)
        assertNotNull(record)
        assertEquals(InspectionDisposition.INCOMPLETE, record!!.officialDisposition)

        job.cancel()
    }

    @Test
    fun `target switching preserves valid 226x226 image and inspection readiness`() = runBlocking {
        val customImageStorage = object : ImageStorageManager {
            override suspend fun createCameraCaptureDestination(): Pair<String, Uri> = "" to Uri.EMPTY
            override suspend fun importPickedImageToAppStorage(sourceUri: Uri): Result<String> =
                Result.success("file:///test_226x226.jpg")
            override suspend fun materializeSamplePreset(preset: SamplePcbPreset): Result<String> =
                Result.success("file:///sample_226x226.jpg")
            override suspend fun validateAndAssessClarity(imageUriString: String, minWidth: Int, minHeight: Int): ImageValidationResult {
                val isDimValid = 226 >= minWidth && 226 >= minHeight
                return ImageValidationResult(
                    isValid = isDimValid,
                    isProcessable = isDimValid,
                    width = 226,
                    height = 226,
                    clarityScore = 120.0,
                    lowClarityRisk = false
                )
            }
            override suspend fun cleanupOrphanedAndEmptyFiles(protectedUris: Set<String>): Int = 0
            override suspend fun deleteAllUnreferencedCaptures(protectedUris: Set<String>): Int = 0
            override suspend fun deleteUnreferencedCaptureIfEmpty(uriString: String?) = Unit
            override suspend fun getImageStorageBytes(): Long = 0L
        }

        val fakeManager = FakeModelManager(
            initialDetection = ModelInfo(
                slot = ModelSlot.PCB_DETECTION,
                displayName = "YOLO",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            ),
            initialTransistor = ModelInfo(
                slot = ModelSlot.TRANSISTOR_ANOMALY,
                displayName = "PatchCore",
                runtimeLabel = "ONNX",
                state = ModelRuntimeState.READY,
                detailMessage = "Ready"
            )
        )

        val runUseCase = RunInspectionUseCase(
            imageStorageManager = customImageStorage,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = customImageStorage,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = fakeManager
        )
        val job = launch { vm.uiState.collect() }

        // Start in PCB mode, pick 226x226 image
        vm.onTargetSelected(InspectionTarget.PCB_BOARD)
        vm.onGalleryImagePicked(Uri.parse("file:///input.jpg"))

        val pcbState = vm.uiState.first { it.selectedImageUri != null }
        assertEquals(226, pcbState.validation?.width)
        assertEquals(226, pcbState.validation?.height)
        assertTrue(pcbState.validation?.isValid == true)
        assertTrue(pcbState.canStartInspection)

        // Switch to Transistor mode -> 226x226 image and readiness must be preserved
        vm.onTargetSelected(InspectionTarget.TRANSISTOR)
        val transistorState = vm.uiState.first { it.selectedTarget == InspectionTarget.TRANSISTOR }
        assertEquals("file:///test_226x226.jpg", transistorState.selectedImageUri)
        assertEquals(226, transistorState.validation?.width)
        assertEquals(226, transistorState.validation?.height)
        assertTrue(transistorState.canStartInspection)

        // Switch back to PCB mode -> still preserved
        vm.onTargetSelected(InspectionTarget.PCB_BOARD)
        val pcbBackState = vm.uiState.first { it.selectedTarget == InspectionTarget.PCB_BOARD }
        assertEquals("file:///test_226x226.jpg", pcbBackState.selectedImageUri)
        assertTrue(pcbBackState.canStartInspection)

        job.cancel()
    }

    @Test
    fun `stale asynchronous image validation does not overwrite newer image or clearSelectedImage`() = runBlocking {
        val slowValidationGate = CompletableDeferred<Unit>()

        val staggeredStorage = object : ImageStorageManager {
            override suspend fun createCameraCaptureDestination(): Pair<String, Uri> = "" to Uri.EMPTY
            override suspend fun importPickedImageToAppStorage(sourceUri: Uri): Result<String> =
                Result.success(sourceUri.toString())
            override suspend fun materializeSamplePreset(preset: SamplePcbPreset): Result<String> =
                Result.success(preset.boardName)
            override suspend fun validateAndAssessClarity(imageUriString: String, minWidth: Int, minHeight: Int): ImageValidationResult {
                if (imageUriString.contains("slow")) {
                    slowValidationGate.await()
                    return ImageValidationResult(
                        isValid = true,
                        isProcessable = true,
                        width = 226,
                        height = 226,
                        clarityScore = 100.0,
                        lowClarityRisk = false
                    )
                }
                return ImageValidationResult(
                    isValid = true,
                    isProcessable = true,
                    width = 512,
                    height = 512,
                    clarityScore = 150.0,
                    lowClarityRisk = false
                )
            }
            override suspend fun cleanupOrphanedAndEmptyFiles(protectedUris: Set<String>): Int = 0
            override suspend fun deleteAllUnreferencedCaptures(protectedUris: Set<String>): Int = 0
            override suspend fun deleteUnreferencedCaptureIfEmpty(uriString: String?) = Unit
            override suspend fun getImageStorageBytes(): Long = 0L
        }

        val runUseCase = RunInspectionUseCase(
            imageStorageManager = staggeredStorage,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = onnxEngine,
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = patchCoreEngine,
            productionAdvisoryEngine = execuTorchEngine,
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = settingsRepository,
            csvLogger = fakeCsvLogger,
            applicationScope = this,
            defaultDispatcher = Dispatchers.Unconfined
        )

        val vm = InspectViewModel(
            imageStorageManager = staggeredStorage,
            settingsRepository = settingsRepository,
            runInspectionUseCase = runUseCase,
            modelManager = modelManager
        )
        val job = launch { vm.uiState.collect() }

        // 1. Trigger slow validation
        vm.onGalleryImagePicked(Uri.parse("file:///slow_image.jpg"))

        // 2. Clear selected image before slow validation completes
        vm.clearSelectedImage()
        val clearedState = vm.uiState.first { it.selectedImageUri == null && !it.isValidating }
        assertNull(clearedState.selectedImageUri)

        // 3. Let slow validation complete
        slowValidationGate.complete(Unit)

        // Ensure state remains null (not overwritten by the stale slow validation)
        val postGateState = vm.uiState.value
        assertNull(postGateState.selectedImageUri)
        assertFalse(postGateState.isValidating)

        job.cancel()
    }
}
