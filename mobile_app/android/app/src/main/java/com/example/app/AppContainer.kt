package com.example.app

import android.content.Context
import com.example.data.csv.CsvExportManagerImpl
import com.example.data.csv.InspectionCsvLoggerImpl
import com.example.data.local.database.PcbInspectorDatabase
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.data.local.preferences.pcbSettingsDataStore
import com.example.data.repository.InspectionRepositoryImpl
import com.example.domain.policy.InspectionPolicyEngine
import com.example.domain.repository.CsvExportManager
import com.example.domain.repository.ImageStorageManager
import com.example.domain.repository.InspectionCsvLogger
import com.example.domain.repository.InspectionRepository
import com.example.domain.repository.ModelManager
import com.example.domain.repository.SettingsRepository
import com.example.domain.repository.StorageAccessManager
import com.example.domain.usecase.ComputeDashboardMetricsUseCase
import com.example.domain.usecase.RunInspectionUseCase
import com.example.inference.ModelManagerImpl
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.demo.DemoAdvisoryEngine
import com.example.inference.demo.DemoPcbDetectionEngine
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import com.example.storage.ImageStorageManagerImpl
import com.example.storage.StorageAccessManagerImpl
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: PcbInspectorDatabase = PcbInspectorDatabase.build(appContext)
    val settingsRepositoryImpl: SettingsRepositoryImpl =
        SettingsRepositoryImpl(appContext.pcbSettingsDataStore)
    val settingsRepository: SettingsRepository = settingsRepositoryImpl

    val inspectionRepository: InspectionRepository =
        InspectionRepositoryImpl(database.inspectionDao())

    val policyEngine: InspectionPolicyEngine = InspectionPolicyEngine()
    val imageStorageManager: ImageStorageManager = ImageStorageManagerImpl(appContext)

    val onnxDetectionEngine: OnnxPcbDetectionEngine = OnnxPcbDetectionEngine(appContext)
    val patchCoreDetectionEngine: PatchCoreTransistorEngine = PatchCoreTransistorEngine(appContext)
    val demoDetectionEngine: DemoPcbDetectionEngine = DemoPcbDetectionEngine()
    val execuTorchAdvisoryEngine: ExecuTorchAdvisoryEngine = ExecuTorchAdvisoryEngine().apply {
        debugOutputDir = appContext.filesDir
    }
    val demoAdvisoryEngine: DemoAdvisoryEngine = DemoAdvisoryEngine()

    val modelManager: ModelManager = ModelManagerImpl(
        context = appContext,
        settingsRepository = settingsRepositoryImpl,
        onnxEngine = onnxDetectionEngine,
        execuTorchEngine = execuTorchAdvisoryEngine,
        patchCoreEngine = patchCoreDetectionEngine
    )

    val csvExportManagerImpl: CsvExportManagerImpl = CsvExportManagerImpl()
    val csvExportManager: CsvExportManager = csvExportManagerImpl
    val csvLogger: InspectionCsvLogger = InspectionCsvLoggerImpl(
        context = appContext,
        dao = database.inspectionDao(),
        repository = inspectionRepository,
        csvExportManager = csvExportManagerImpl
    )
    val storageAccessManager: StorageAccessManager = StorageAccessManagerImpl(appContext)

    val runInspectionUseCase: RunInspectionUseCase = RunInspectionUseCase(
        imageStorageManager = imageStorageManager,
        policyEngine = policyEngine,
        productionDetectionEngine = onnxDetectionEngine,
        demoDetectionEngine = demoDetectionEngine,
        patchCoreEngine = patchCoreDetectionEngine,
        productionAdvisoryEngine = execuTorchAdvisoryEngine,
        demoAdvisoryEngine = demoAdvisoryEngine,
        inspectionRepository = inspectionRepository,
        settingsRepository = settingsRepository,
        csvLogger = csvLogger,
        applicationScope = applicationScope
    )

    val computeDashboardMetricsUseCase: ComputeDashboardMetricsUseCase = ComputeDashboardMetricsUseCase()

    fun runStartupMaintenance() {
        applicationScope.launch {
            runCatching {
                val artifacts = settingsRepositoryImpl.storedModelArtifacts.first()
                val advisoryModelFile = artifacts.advisoryModelPath?.let(::File)
                    ?.takeIf { it.exists() && it.length() > 0L }
                    ?: File(appContext.filesDir, "models/active_advisory.pte").takeIf { it.exists() && it.length() > 0L }
                    ?: File(appContext.filesDir, "models/active_smollm2_advisory.pte").takeIf { it.exists() && it.length() > 0L }

                val advisoryTokFile = artifacts.advisoryTokenizerPath?.let(::File)
                    ?.takeIf { it.exists() && it.length() > 0L }
                    ?: File(appContext.filesDir, "models/active_tokenizer.json").takeIf { it.exists() && it.length() > 0L }

                onnxDetectionEngine.setConfiguredArtifact(
                    artifacts.detectionModelPath?.let(::File)
                )
                execuTorchAdvisoryEngine.setConfiguredArtifacts(
                    modelFile = advisoryModelFile,
                    tokenizerFile = advisoryTokFile
                )
                patchCoreDetectionEngine.setConfiguredArtifact(
                    artifacts.transistorModelPath?.let(::File)
                )
            }
            runCatching { csvLogger.processPendingOutboxEvents() }
            runCatching {
                val referenced = inspectionRepository.getReferencedImageUris()
                imageStorageManager.cleanupOrphanedAndEmptyFiles(referenced)
            }
        }
    }
}
