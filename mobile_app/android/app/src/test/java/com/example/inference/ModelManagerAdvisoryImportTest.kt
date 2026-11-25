package com.example.inference

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.preferences.SettingsRepositoryImpl
import com.example.domain.model.ModelRuntimeState
import com.example.inference.cv.OnnxPcbDetectionEngine
import com.example.inference.cv.PatchCoreTransistorEngine
import com.example.inference.llm.ExecuTorchAdvisoryEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ModelManagerAdvisoryImportTest {

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
        val dataStoreFile = tempFolder.newFile("test_settings_${System.currentTimeMillis()}.preferences_pb")
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
    fun `1 - import of a complete model succeeds and updates path in settings`() = runBlocking {
        // Generic valid PTE model (> 1024 bytes)
        val completeModel = tempFolder.newFile("generic_model.pte").apply {
            writeBytes(ByteArray(8192) { 0x1A })
        }
        val result = modelManager.importAdvisoryModel(Uri.fromFile(completeModel))

        assertTrue("Import must succeed: ${result.message}", result.success)
        val stored = settingsRepository.storedModelArtifacts.first()
        val path = checkNotNull(stored.advisoryModelPath)
        assertTrue(path.endsWith(ModelManagerImpl.ACTIVE_ADVISORY_MODEL_NAME))
        val activeFile = File(path)
        assertTrue(activeFile.exists())
        assertEquals(8192L, activeFile.length())
    }

    @Test
    fun `2 - rejection of truncated Llama 3_2 3B artifact produces actionable error`() = runBlocking {
        // Artifact identified as Llama 3.2 3B by filename, but size is 211,849,216 bytes (simulating the crash condition)
        val truncatedLlama = tempFolder.newFile("llama_3.2_3b.pte").apply {
            writeBytes(ByteArray(2048) { 0x00 }) // smaller than 2,379,319,808 bytes
        }

        val result = modelManager.importAdvisoryModel(Uri.fromFile(truncatedLlama))

        assertFalse("Truncated Llama 3.2 3B artifact must be rejected", result.success)
        assertTrue("Error message must mention truncation", result.message.contains("truncated"))
        assertTrue("Error message must specify expected size", result.message.contains(ModelArtifactValidator.LLAMA_3_2_3B_EXPECTED_SIZE.toString()))

        // Verify active file was not created or corrupted
        val stored = settingsRepository.storedModelArtifacts.first()
        assertEquals(null, stored.advisoryModelPath)
    }

    @Test
    fun `3 - copy byte-count mismatch and too small files are rejected`() {
        val dummyFile = tempFolder.newFile("dummy.pte").apply {
            writeBytes(ByteArray(500) { 0x01 }) // < 1024 bytes
        }
        val validation = ModelArtifactValidator.validateStagedPte(
            file = dummyFile,
            displayName = "dummy.pte",
            declaredSourceSize = 500L,
            bytesCopied = 500L
        )
        assertFalse(validation.success)
        assertTrue(validation.message.contains("too small"))

        val declaredMismatch = ModelArtifactValidator.validateStagedPte(
            file = tempFolder.newFile("large.pte").apply { writeBytes(ByteArray(4096)) },
            displayName = "large.pte",
            declaredSourceSize = 8192L,
            bytesCopied = 4096L
        )
        assertFalse(declaredMismatch.success)
        assertTrue(declaredMismatch.message.contains("Incomplete model copy"))
    }

    @Test
    fun `4 - promotion failure preserves existing active model intact`() = runBlocking {
        // First establish a healthy active model
        val existingActive = File(context.filesDir, "models/${ModelManagerImpl.ACTIVE_ADVISORY_MODEL_NAME}").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4096) { 0xAA.toByte() })
        }
        settingsRepository.setAdvisoryModelPath(existingActive.absolutePath)

        // Attempt to import a truncated Llama candidate
        val badCandidate = tempFolder.newFile("llama_truncated.pte").apply {
            writeBytes(ByteArray(2048) { 0xBB.toByte() })
        }

        val result = modelManager.importAdvisoryModel(Uri.fromFile(badCandidate))

        assertFalse(result.success)
        // Existing active model must remain completely intact
        assertTrue(existingActive.exists())
        assertEquals(4096L, existingActive.length())
        assertEquals(0xAA.toByte(), existingActive.readBytes()[0])
        assertEquals(existingActive.absolutePath, settingsRepository.storedModelArtifacts.first().advisoryModelPath)
    }

    @Test
    fun `5 - correct persisted model path in settings after promotion`() = runBlocking {
        val candidate = tempFolder.newFile("custom_advisory.pte").apply {
            writeBytes(ByteArray(5120) { 0x77 })
        }

        val result = modelManager.importAdvisoryModel(Uri.fromFile(candidate))
        assertTrue(result.success)

        val stored = settingsRepository.storedModelArtifacts.first()
        val expectedTarget = File(context.filesDir, "models/${ModelManagerImpl.ACTIVE_ADVISORY_MODEL_NAME}")
        assertEquals(expectedTarget.absolutePath, stored.advisoryModelPath)
    }

    @Test
    fun `6 - tokenizer and model association during separate imports`() = runBlocking {
        // Import tokenizer first
        val tokFile = tempFolder.newFile("tokenizer.json").apply {
            writeText("{\"bos_token\": \"<|begin_of_text|>\"}")
        }
        val tokResult = modelManager.importAdvisoryTokenizer(Uri.fromFile(tokFile))
        assertTrue(tokResult.success)

        // Import model next
        val modelFile = tempFolder.newFile("model.pte").apply {
            writeBytes(ByteArray(4096) { 0x33 })
        }
        val modelResult = modelManager.importAdvisoryModel(Uri.fromFile(modelFile))
        assertTrue(modelResult.success)

        val stored = settingsRepository.storedModelArtifacts.first()
        assertTrue(stored.advisoryModelPath?.endsWith("active_advisory.pte") == true)
        assertTrue(stored.advisoryTokenizerPath?.endsWith("active_tokenizer.json") == true)
    }

    @Test
    fun `7 - cleanup of temporary files after failure`() = runBlocking {
        val stagingDir = File(context.filesDir, "models/staging").apply { mkdirs() }
        val beforeFiles = stagingDir.listFiles()?.toList() ?: emptyList()

        val badModel = tempFolder.newFile("bad_llama.pte").apply {
            writeBytes(ByteArray(2048) { 0x11 }) // truncated Llama
        }
        modelManager.importAdvisoryModel(Uri.fromFile(badModel))

        val afterFiles = stagingDir.listFiles()?.toList() ?: emptyList()
        assertEquals(
            "Staging directory must not leave orphaned files on failure",
            beforeFiles.size,
            afterFiles.size
        )
    }

    @Test
    fun `8 - concurrent import prevention executes sequentially without collision`() = runBlocking {
        val model1 = tempFolder.newFile("model_one.pte").apply { writeBytes(ByteArray(4096) { 0x01 }) }
        val model2 = tempFolder.newFile("model_two.pte").apply { writeBytes(ByteArray(8192) { 0x02 }) }

        val job1 = async(Dispatchers.Default) {
            modelManager.importAdvisoryModel(Uri.fromFile(model1))
        }
        val job2 = async(Dispatchers.Default) {
            modelManager.importAdvisoryModel(Uri.fromFile(model2))
        }

        val res1 = job1.await()
        val res2 = job2.await()

        assertTrue(res1.success)
        assertTrue(res2.success)

        val activeFile = File(context.filesDir, "models/${ModelManagerImpl.ACTIVE_ADVISORY_MODEL_NAME}")
        assertTrue(activeFile.exists())
        // Active file must have size of one of the models (not corrupted or intermediate)
        assertTrue(activeFile.length() == 4096L || activeFile.length() == 8192L)
    }

    @Test
    fun `9 - existing SmolLM2 model remains compatible and is not falsely rejected`() = runBlocking {
        // SmolLM2 model is ~240MB, not Llama
        val smolModel = tempFolder.newFile("smollm2_360m.pte").apply {
            writeBytes(ByteArray(16384) { 0x55 }) // generic non-Llama size
        }
        val result = modelManager.importAdvisoryModel(Uri.fromFile(smolModel))
        assertTrue("SmolLM2 must not be rejected by Llama-specific rules", result.success)

        val stored = settingsRepository.storedModelArtifacts.first()
        assertTrue(stored.advisoryModelPath?.endsWith("active_advisory.pte") == true)
    }
}
