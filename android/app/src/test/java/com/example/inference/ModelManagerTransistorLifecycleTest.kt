package com.example.inference

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ModelSlot
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ModelManagerTransistorLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var onnxEngine: OnnxPcbDetectionEngine
    private lateinit var execuTorchEngine: ExecuTorchAdvisoryEngine
    private lateinit var patchCoreEngine: PatchCoreTransistorEngine
    private lateinit var modelManager: ModelManagerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    }

    @Test
    fun `transistorModelInfo exposes PatchCore slot initial state`() {
        val info = modelManager.transistorModelInfo.value
        assertEquals(ModelSlot.TRANSISTOR_ANOMALY, info.slot)
        assertEquals(ModelRuntimeState.NOT_LOADED, info.state)
        assertTrue(info.detailMessage.contains("Not loaded"))
    }

    @Test
    fun `loadTransistorModel fails cleanly when no model is configured`() = runBlocking {
        val result = modelManager.loadTransistorModel()
        assertFalse(result.success)
        assertTrue(result.message.contains("No imported PatchCore model found"))
    }

    @Test
    fun `importTransistorModel rejects non-onnx files with clear error`() = runBlocking {
        val textFile = tempFolder.newFile("model.txt").apply { writeText("not an onnx file") }
        val result = modelManager.importTransistorModel(Uri.fromFile(textFile))
        assertFalse(result.success)
        assertTrue(result.message.contains("expected a .onnx PatchCore artifact"))
    }

    @Test
    fun `importTransistorModel rejects invalid SHA256 model and preserves previous state`() = runBlocking {
        val invalidFile = tempFolder.newFile("bad_patchcore.onnx").apply { writeBytes("corrupt content".toByteArray()) }
        val result = modelManager.importTransistorModel(Uri.fromFile(invalidFile))
        assertFalse(result.success)
        assertTrue(result.message.contains("SHA-256 mismatch") || result.message.contains("validation failed"))

        // Staging file must be cleaned up
        val stagingDir = File(File(context.filesDir, "models"), "staging")
        val stagedFiles = stagingDir.listFiles()?.filter { it.name.startsWith("stage_patchcore") || it.name.startsWith("stage_transistor") } ?: emptyList()
        assertTrue("Staged files must be cleaned up on failure", stagedFiles.isEmpty())

        // Path in settings must remain null
        val stored = settingsRepository.storedModelArtifacts.first()
        assertEquals(null, stored.transistorModelPath)
    }

    @Test
    fun `unloadTransistorModel delegates to engine and reports success`() = runBlocking {
        val result = modelManager.unloadTransistorModel()
        assertTrue(result.success)
        assertTrue(result.message.contains("PatchCore anomaly detection session unloaded"))
    }

    @Test
    fun `inspectModelMetadata delegates correctly for TRANSISTOR_ANOMALY slot`() {
        val text = modelManager.inspectModelMetadata(ModelSlot.TRANSISTOR_ANOMALY)
        assertTrue(text.contains("Slot: Unsupervised Anomaly Detection (PatchCore)"))
        assertTrue(text.contains("State: NOT_LOADED"))
        assertTrue(text.contains("Runtime: ONNX Runtime Android 1.22.0"))
        assertTrue(text.contains("Decoder: patchcore-anomaly-v1"))
        assertTrue(text.contains("Class Mapping: pcb-anomaly-v1"))
    }

    @Test
    fun `loadTransistorModel transparently migrates legacy active_transistor_patchcore file`() = runBlocking {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val legacyFile = File(modelsDir, "active_transistor_patchcore.onnx").apply {
            writeBytes("mock onnx model bytes".toByteArray())
        }
        val targetFile = File(modelsDir, "active_patchcore.onnx")
        if (targetFile.exists()) targetFile.delete()

        settingsRepository.setTransistorModelPath(legacyFile.absolutePath)

        // Attempting to load will trigger migration
        modelManager.loadTransistorModel()

        // Verify targetFile was created by migration
        assertTrue("active_patchcore.onnx must exist after migration", targetFile.exists())
        assertEquals(legacyFile.length(), targetFile.length())
        assertEquals(targetFile.absolutePath, settingsRepository.storedModelArtifacts.first().transistorModelPath)
    }
}
