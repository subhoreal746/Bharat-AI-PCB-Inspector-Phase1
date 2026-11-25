package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.csv.CsvExportManagerImpl
import com.example.data.csv.InspectionCsvLoggerImpl
import com.example.data.local.database.PcbInspectorDatabase
import com.example.data.repository.InspectionRepositoryImpl
import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionTarget
import com.example.domain.model.PcbImageSource
import com.example.domain.policy.InspectionPolicyEngine
import com.example.domain.repository.SettingsRepository
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.demo.DemoAdvisoryEngine
import com.example.inference.demo.DemoPcbDetectionEngine
import com.example.inference.demo.SamplePcbCatalog
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import com.example.storage.ImageStorageManagerImpl
import com.example.ui.theme.AppThemeMode
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RunInspectionUseCaseTest {

    private lateinit var context: Context
    private lateinit var db: PcbInspectorDatabase
    private lateinit var repository: InspectionRepositoryImpl
    private lateinit var imageStorageManager: ImageStorageManagerImpl
    private lateinit var csvLogger: InspectionCsvLoggerImpl
    private lateinit var appScope: CoroutineScope
    private lateinit var fakeSettings: FakeSettingsRepository
    private lateinit var useCase: RunInspectionUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PcbInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InspectionRepositoryImpl(db.inspectionDao())
        imageStorageManager = ImageStorageManagerImpl(context, Dispatchers.Unconfined)
        val csvExport = CsvExportManagerImpl()
        csvLogger = InspectionCsvLoggerImpl(
            context = context,
            dao = db.inspectionDao(),
            repository = repository,
            csvExportManager = csvExport,
            ioDispatcher = Dispatchers.Unconfined
        )
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fakeSettings = FakeSettingsRepository()

        useCase = RunInspectionUseCase(
            imageStorageManager = imageStorageManager,
            policyEngine = InspectionPolicyEngine(),
            productionDetectionEngine = OnnxPcbDetectionEngine(context, Dispatchers.Unconfined),
            demoDetectionEngine = DemoPcbDetectionEngine(),
            patchCoreEngine = PatchCoreTransistorEngine(context, Dispatchers.Unconfined),
            productionAdvisoryEngine = ExecuTorchAdvisoryEngine(Dispatchers.Unconfined),
            demoAdvisoryEngine = DemoAdvisoryEngine(),
            inspectionRepository = repository,
            settingsRepository = fakeSettings,
            csvLogger = csvLogger,
            applicationScope = appScope,
            defaultDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
    }

    @Test
    fun realGalleryImageWithUnloadedOnnxModelYieldsIncompleteAndNeverFallsBackToDemo() = runBlocking {
        val sharpPng = createTexturedPng("real_pcb_test.png", 640, 480)
        val record = useCase.execute(
            imageUriString = Uri.fromFile(sharpPng).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "Real Line Board 01"
        )

        assertEquals(InspectionDisposition.INCOMPLETE, record.officialDisposition)
        assertFalse(record.isDemonstration)
        assertTrue(record.defects.isEmpty())
        assertEquals("OnnxPcbDetectionEngine", record.provenance.inferenceEngine)
        assertEquals("NONE", record.provenance.modelSha256)
        assertEquals(AdvisoryAvailability.UNAVAILABLE, record.advisory.availability)

        val stored = repository.getRecordById(record.inspectionId)
        assertEquals(InspectionDisposition.INCOMPLETE, stored?.officialDisposition)
        assertFalse(stored?.isDemonstration ?: true)
    }

    @Test
    fun samplePresetExecutesThroughDemoEngineAndPersistsAtomically() = runBlocking {
        val preset = SamplePcbCatalog.presets.first { it.id == "sample_qfp100" }
        val materializedUri = imageStorageManager.materializeSamplePreset(preset).getOrThrow()

        val record = useCase.execute(
            imageUriString = materializedUri,
            sourceType = PcbImageSource.SAMPLE,
            boardName = preset.boardName,
            sampleScenarioHint = preset.scenarioHint
        )

        assertTrue(record.isDemonstration)
        assertEquals(InspectionDisposition.REJECT, record.officialDisposition)
        assertTrue(record.defects.isNotEmpty())
        assertEquals(AdvisoryAvailability.AVAILABLE, record.advisory.availability)
        assertTrue(record.advisory.isDemonstration)

        val all = repository.observeAllRecords().first()
        assertEquals(1, all.size)
        assertEquals(record.inspectionId, all.first().inspectionId)
    }

    @Test
    fun unreadableRealImageYieldsIncompleteSafely() = runBlocking {
        val missingUri = "file:///nonexistent_path/missing_board.png"
        val record = useCase.execute(
            imageUriString = missingUri,
            sourceType = PcbImageSource.CAMERA,
            boardName = "Missing Capture"
        )

        assertEquals(InspectionDisposition.INCOMPLETE, record.officialDisposition)
        assertFalse(record.isDemonstration)
        assertTrue(record.defects.isEmpty())
    }

    @Test
    fun realTransistorImageRoutesToPatchCoreAndReportsIncompleteWhenUnloadedWithoutFallingBackToYoloOrDemo() = runBlocking {
        val sharpPng = createTexturedPng("real_transistor_test.png", 640, 480)
        val record = useCase.execute(
            imageUriString = Uri.fromFile(sharpPng).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "Transistor Unit 101",
            target = InspectionTarget.TRANSISTOR
        )

        assertEquals(InspectionTarget.TRANSISTOR, record.target)
        assertEquals(InspectionDisposition.INCOMPLETE, record.officialDisposition)
        assertFalse(record.isDemonstration)
        assertTrue(record.defects.isEmpty())
        assertEquals("PatchCoreTransistorEngine", record.provenance.inferenceEngine)
        assertEquals("NONE", record.provenance.modelSha256)
        assertTrue(record.inspectionId.startsWith("TR-"))
        assertTrue(record.completedEpochMs != null)

        val stored = repository.getRecordById(record.inspectionId)
        assertEquals(InspectionTarget.TRANSISTOR, stored?.target)
        assertEquals(InspectionDisposition.INCOMPLETE, stored?.officialDisposition)
    }

    @Test
    fun sampleTransistorInputExplicitlyRejectsSampleDemoSimulation() = runBlocking {
        val sharpPng = createTexturedPng("sample_transistor_attempt.png", 640, 480)
        val record = useCase.execute(
            imageUriString = Uri.fromFile(sharpPng).toString(),
            sourceType = PcbImageSource.SAMPLE,
            boardName = "Sample Transistor",
            target = InspectionTarget.TRANSISTOR
        )

        assertEquals(InspectionTarget.TRANSISTOR, record.target)
        assertEquals(InspectionDisposition.INCOMPLETE, record.officialDisposition)
        assertTrue(record.policyRationale.contains("UNSUPPORTED_REAL_IMAGE_FALLBACK"))
        assertEquals("DemoTransistorUnsupported", record.provenance.inferenceEngine)
    }

    @Test
    fun executeAccepts226x226ImageForBothPcbAndTransistorModes() = runBlocking {
        val img226 = createTexturedPng("valid_226x226_frame.png", 226, 226)

        // 1. PCB mode: 226x226 is accepted and proceeds past optical frame validation
        val pcbRecord = useCase.execute(
            imageUriString = Uri.fromFile(img226).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "PCB-226-Test",
            target = InspectionTarget.PCB_BOARD
        )
        assertFalse(pcbRecord.policyRationale.contains("UNPROCESSABLE_IMAGE"))
        assertEquals(226, pcbRecord.imageWidth)
        assertEquals(226, pcbRecord.imageHeight)

        // 2. Transistor mode: 226x226 is accepted and proceeds past optical frame validation
        val trRecord = useCase.execute(
            imageUriString = Uri.fromFile(img226).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "TR-226-Test",
            target = InspectionTarget.TRANSISTOR
        )
        assertFalse(trRecord.policyRationale.contains("UNPROCESSABLE_IMAGE"))
        assertEquals(226, trRecord.imageWidth)
        assertEquals(226, trRecord.imageHeight)
    }

    @Test
    fun executeRejects223x223ImageAsUnprocessableForBothModes() = runBlocking {
        val img223 = createTexturedPng("subscale_223x223_frame.png", 223, 223)

        val pcbRecord = useCase.execute(
            imageUriString = Uri.fromFile(img223).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "PCB-223-Test",
            target = InspectionTarget.PCB_BOARD
        )
        assertEquals(InspectionDisposition.INCOMPLETE, pcbRecord.officialDisposition)
        assertTrue(pcbRecord.policyRationale.contains("below minimum"))

        val trRecord = useCase.execute(
            imageUriString = Uri.fromFile(img223).toString(),
            sourceType = PcbImageSource.GALLERY,
            boardName = "TR-223-Test",
            target = InspectionTarget.TRANSISTOR
        )
        assertEquals(InspectionDisposition.INCOMPLETE, trRecord.officialDisposition)
        assertTrue(trRecord.policyRationale.contains("below minimum"))
    }

    private fun createTexturedPng(name: String, width: Int, height: Int): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(22, 85, 45))
        for (y in 20 until height - 20 step 8) {
            for (x in 20 until width - 20 step 8) {
                bmp.setPixel(x, y, Color.rgb(225, 195, 90))
                bmp.setPixel(x + 1, y, Color.rgb(30, 30, 30))
            }
        }
        val outFile = File(context.cacheDir, name)
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return outFile
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _theme = MutableStateFlow(AppThemeMode.SYSTEM)
        private val _policy = MutableStateFlow(InspectionPolicyConfig())
        override val themeMode: Flow<AppThemeMode> = _theme
        override val policyConfig: Flow<InspectionPolicyConfig> = _policy
        override suspend fun setThemeMode(mode: AppThemeMode) { _theme.value = mode }
        override suspend fun updatePolicyConfig(config: InspectionPolicyConfig) { _policy.value = config }
        override suspend fun resetPolicyDefaults() { _policy.value = InspectionPolicyConfig() }
    }
}
