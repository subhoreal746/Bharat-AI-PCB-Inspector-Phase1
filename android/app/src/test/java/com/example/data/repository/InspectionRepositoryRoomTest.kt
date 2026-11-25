package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.PcbInspectorDatabase
import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.AiAdvisory
import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.InspectionTarget
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.PcbImageSource
import com.example.domain.model.TransistorAnomalyData
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InspectionRepositoryRoomTest {

    private lateinit var context: Context
    private lateinit var db: PcbInspectorDatabase
    private lateinit var repository: InspectionRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PcbInspectorDatabase::class.java)
            .addMigrations(PcbInspectorDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        repository = InspectionRepositoryImpl(db.inspectionDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `saveInspectionAtomically persists record defects provenance and pending outbox event`() = runTest {
        val snapshot = InspectionPolicyConfig(
            profileName = "Line A Snapshot",
            policyVersion = "1.2.0",
            rejectOnCritical = true,
            minImageWidth = 320,
            minImageHeight = 240
        )
        val record = InspectionRecord(
            inspectionId = "PCB-1001",
            timestampEpochMs = 1_760_000_000_000L,
            boardName = "Power Supply Rev C",
            sourceType = PcbImageSource.CAMERA,
            imageUri = "file:///data/user/0/pcb1001.jpg",
            imageWidth = 1280,
            imageHeight = 960,
            lowClarityRisk = false,
            clarityScore = 180.5,
            officialDisposition = InspectionDisposition.REJECT,
            policyProfile = snapshot.profileName,
            policyVersion = snapshot.policyVersion,
            policyConfigSnapshot = snapshot,
            effectiveThresholdsSummary = snapshot.effectiveThresholdsSummary(),
            policyRationale = "REJECT: Short circuit found.",
            defects = listOf(
                DetectedDefect(
                    defectId = "def-1",
                    classIndex = 1,
                    rawClassName = "short",
                    canonicalClassName = "Short Circuit",
                    confidence = 0.91f,
                    severity = DefectSeverity.CRITICAL,
                    requiresManualReview = true,
                    boundingBox = BoundingBox(0.2f, 0.3f, 0.1f, 0.12f)
                )
            ),
            totalDefects = 1,
            isDemonstration = false,
            provenance = ModelExecutionProvenance(
                inferenceEngine = "OnnxPcbDetectionEngine",
                modelIdentity = "yolov8n-pcb.onnx",
                modelSha256 = "abc123sha",
                modelContractVersion = "onnx-yolo-v1",
                preprocessingVersion = "letterbox-rgb-v1",
                decoderVersion = "yolo-nms-v1",
                classMappingVersion = "pcb-11-v1",
                runtimeVersion = "ort-1.21.0"
            ),
            advisory = AiAdvisory(
                availability = AdvisoryAvailability.UNAVAILABLE,
                isDemonstration = false,
                unavailableReason = "ExecuTorch model not loaded"
            ),
            latencyMetrics = InspectionLatencyMetrics(
                imagePreprocessingMs = 12L,
                yoloInferenceMs = 45L,
                outputDecodingNmsMs = 6L,
                policyEvaluationMs = 2L,
                llmAdvisoryMs = null,
                totalDurationMs = 65L
            )
        )

        repository.saveInspectionAtomically(record)

        val loaded = repository.getRecordById("PCB-1001")
        assertNotNull(loaded)
        assertEquals("Line A Snapshot", loaded!!.policyProfile)
        assertEquals(320, loaded.policyConfigSnapshot.minImageWidth)
        assertEquals("abc123sha", loaded.provenance.modelSha256)
        assertNull(loaded.latencyMetrics.llmAdvisoryMs)
        assertEquals(1, loaded.defects.size)
        assertEquals(InspectionTarget.PCB_BOARD, loaded.target)
        assertNotNull(loaded.defects.first().boundingBox)
        assertEquals(0.2f, loaded.defects.first().boundingBox!!.x, 0.0001f)
        assertEquals(1, repository.getPendingOutboxCount())
        assertTrue(repository.getReferencedImageUris().contains("file:///data/user/0/pcb1001.jpg"))

        repository.clearAllHistory()
        assertTrue(repository.observeAllRecords().first().isEmpty())
        assertEquals(0, repository.getPendingOutboxCount())
    }

    @Test
    fun `saveInspectionAtomically supports null bounding box and transistor inspection target`() = runTest {
        val snapshot = InspectionPolicyConfig()
        val record = InspectionRecord(
            inspectionId = "TR-2001",
            timestampEpochMs = 1_760_000_100_000L,
            boardName = "Transistor TO-92 Batch 4",
            sourceType = PcbImageSource.GALLERY,
            imageUri = "file:///data/user/0/tr2001.jpg",
            imageWidth = 256,
            imageHeight = 256,
            lowClarityRisk = false,
            clarityScore = 210.0,
            officialDisposition = InspectionDisposition.REVIEW,
            policyProfile = snapshot.profileName,
            policyVersion = snapshot.policyVersion,
            policyConfigSnapshot = snapshot,
            effectiveThresholdsSummary = snapshot.effectiveThresholdsSummary(),
            policyRationale = "REVIEW: Transistor anomaly flagged.",
            defects = listOf(
                DetectedDefect(
                    defectId = "tr-def-1",
                    classIndex = 0,
                    rawClassName = "transistor_anomaly",
                    canonicalClassName = "Transistor Anomaly",
                    confidence = 0.88f,
                    severity = DefectSeverity.MANUAL_REVIEW,
                    requiresManualReview = true,
                    boundingBox = null // Non-localized anomaly
                )
            ),
            totalDefects = 1,
            isDemonstration = false,
            provenance = ModelExecutionProvenance(
                inferenceEngine = "PatchCoreTransistorEngine",
                modelIdentity = "patchcore_transistor_single.onnx",
                modelSha256 = "544af0939a22ce9cf2f64460f1e3e8323ec4be2c91d0d914f8c4e788c4f44e95",
                modelContractVersion = "patchcore-v1",
                preprocessingVersion = "warp-256-rgb-v1",
                decoderVersion = "patchcore-decoder-v1",
                classMappingVersion = "transistor-1-v1",
                runtimeVersion = "ort-1.22.0"
            ),
            advisory = AiAdvisory(
                availability = AdvisoryAvailability.NOT_REQUESTED,
                isDemonstration = false
            ),
            latencyMetrics = InspectionLatencyMetrics(
                imagePreprocessingMs = 5L,
                patchCoreInferenceMs = 28L,
                outputDecodingNmsMs = 3L,
                policyEvaluationMs = 1L,
                totalDurationMs = 37L
            ),
            target = InspectionTarget.TRANSISTOR,
            transistorAnomaly = TransistorAnomalyData(
                rawAnomalyScore = 41.25f,
                normalizedDisplayScore = 0.88f,
                isAnomalous = true,
                affectedAreaPercent = null,
                boundingBox = null
            )
        )

        repository.saveInspectionAtomically(record)

        val loaded = repository.getRecordById("TR-2001")
        assertNotNull(loaded)
        assertEquals(InspectionTarget.TRANSISTOR, loaded!!.target)
        assertNotNull(loaded.transistorAnomaly)
        assertEquals(41.25f, loaded.transistorAnomaly!!.rawAnomalyScore, 0.001f)
        assertEquals(0.88f, loaded.transistorAnomaly!!.normalizedDisplayScore, 0.001f)
        assertTrue(loaded.transistorAnomaly!!.isAnomalous)
        assertEquals(1, loaded.defects.size)
        assertNull("Loaded defect must have null bounding box", loaded.defects.first().boundingBox)
        assertEquals(28L, loaded.latencyMetrics.patchCoreInferenceMs)
        assertEquals(28L, loaded.modelInferenceLatencyMs)
    }

    @Test
    fun `MIGRATION_1_2 migrates v1 database non-destructively preserving historical records and coordinates`() = runTest {
        val dbFile = context.getDatabasePath("test_migration_v1_to_v2.db")
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a genuine SQLite v1 database using raw SQLite helper
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_v1_to_v2.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(sqLiteDb: SupportSQLiteDatabase) {
                        sqLiteDb.execSQL("""
                            CREATE TABLE IF NOT EXISTS `inspection_records` (
                                `inspectionId` TEXT NOT NULL,
                                `timestampEpochMs` INTEGER NOT NULL,
                                `boardName` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL,
                                `imageUri` TEXT NOT NULL,
                                `imageWidth` INTEGER NOT NULL,
                                `imageHeight` INTEGER NOT NULL,
                                `lowClarityRisk` INTEGER NOT NULL,
                                `clarityScore` REAL,
                                `officialDisposition` TEXT NOT NULL,
                                `policyProfile` TEXT NOT NULL,
                                `policyVersion` TEXT NOT NULL,
                                `policyConfigSnapshotJson` TEXT NOT NULL,
                                `effectiveThresholdsSummary` TEXT NOT NULL,
                                `policyRationale` TEXT NOT NULL,
                                `totalDefects` INTEGER NOT NULL,
                                `isDemonstration` INTEGER NOT NULL,
                                `inferenceEngine` TEXT NOT NULL,
                                `modelIdentity` TEXT NOT NULL,
                                `modelSha256` TEXT NOT NULL,
                                `modelContractVersion` TEXT NOT NULL,
                                `preprocessingVersion` TEXT NOT NULL,
                                `decoderVersion` TEXT NOT NULL,
                                `classMappingVersion` TEXT NOT NULL,
                                `runtimeVersion` TEXT NOT NULL,
                                `advisoryAvailability` TEXT NOT NULL,
                                `probableRootCause` TEXT,
                                `recommendedOperatorAction` TEXT,
                                `relevantStandard` TEXT,
                                `suggestedDisposition` TEXT,
                                `advisoryLimitations` TEXT,
                                `advisoryUnavailableReason` TEXT,
                                `advisoryProvenanceSummary` TEXT,
                                `imagePreprocessingMs` INTEGER,
                                `yoloInferenceMs` INTEGER,
                                `outputDecodingNmsMs` INTEGER,
                                `policyEvaluationMs` INTEGER,
                                `llmAdvisoryMs` INTEGER,
                                `totalDurationMs` INTEGER,
                                PRIMARY KEY(`inspectionId`)
                            )
                        """.trimIndent())
                        sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_inspection_records_timestampEpochMs` ON `inspection_records` (`timestampEpochMs`)")
                        sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_inspection_records_officialDisposition` ON `inspection_records` (`officialDisposition`)")

                        sqLiteDb.execSQL("""
                            CREATE TABLE IF NOT EXISTS `detected_defects` (
                                `defectId` TEXT NOT NULL,
                                `inspectionId` TEXT NOT NULL,
                                `classIndex` INTEGER NOT NULL,
                                `rawClassName` TEXT NOT NULL,
                                `canonicalClassName` TEXT NOT NULL,
                                `confidence` REAL NOT NULL,
                                `severity` TEXT NOT NULL,
                                `requiresManualReview` INTEGER NOT NULL,
                                `bboxX` REAL NOT NULL,
                                `bboxY` REAL NOT NULL,
                                `bboxWidth` REAL NOT NULL,
                                `bboxHeight` REAL NOT NULL,
                                `code` TEXT,
                                `description` TEXT,
                                PRIMARY KEY(`defectId`),
                                FOREIGN KEY(`inspectionId`) REFERENCES `inspection_records`(`inspectionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                            )
                        """.trimIndent())
                        sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_defects_inspectionId` ON `detected_defects` (`inspectionId`)")

                        sqLiteDb.execSQL("""
                            CREATE TABLE IF NOT EXISTS `csv_audit_outbox` (
                                `eventId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `inspectionId` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `retryCount` INTEGER NOT NULL,
                                `lastError` TEXT,
                                `createdEpochMs` INTEGER NOT NULL,
                                `updatedEpochMs` INTEGER NOT NULL
                            )
                        """.trimIndent())
                        sqLiteDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_csv_audit_outbox_inspectionId` ON `csv_audit_outbox` (`inspectionId`)")
                        sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS `index_csv_audit_outbox_status` ON `csv_audit_outbox` (`status`)")

                        sqLiteDb.execSQL("""
                            CREATE TABLE IF NOT EXISTS `csv_audit_ledger` (
                                `inspectionId` TEXT NOT NULL,
                                `rowDigestSha256` TEXT NOT NULL,
                                `csvRowCount` INTEGER NOT NULL,
                                `writtenEpochMs` INTEGER NOT NULL,
                                PRIMARY KEY(`inspectionId`)
                            )
                        """.trimIndent())
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val v1Db = v1Helper.writableDatabase

        // 2. Insert historical v1 records
        v1Db.execSQL("""
            INSERT INTO `inspection_records` (
                `inspectionId`, `timestampEpochMs`, `boardName`, `sourceType`, `imageUri`, `imageWidth`, `imageHeight`,
                `lowClarityRisk`, `clarityScore`, `officialDisposition`, `policyProfile`, `policyVersion`,
                `policyConfigSnapshotJson`, `effectiveThresholdsSummary`, `policyRationale`, `totalDefects`,
                `isDemonstration`, `inferenceEngine`, `modelIdentity`, `modelSha256`, `modelContractVersion`,
                `preprocessingVersion`, `decoderVersion`, `classMappingVersion`, `runtimeVersion`, `advisoryAvailability`
            ) VALUES (
                'HIST-101', 1760000000000, 'Legacy Board', 'CAMERA', 'file:///img.jpg', 1280, 720,
                0, 190.0, 'REJECT', 'Default', '1.0', '{}', 'critical=REJECT', 'Short circuit found', 1,
                0, 'OnnxPcbDetectionEngine', 'yolov8n.onnx', 'sha123', 'v1', 'prep1', 'dec1', 'pcb-11-v1', 'ort', 'UNAVAILABLE'
            )
        """.trimIndent())

        v1Db.execSQL("""
            INSERT INTO `detected_defects` (
                `defectId`, `inspectionId`, `classIndex`, `rawClassName`, `canonicalClassName`,
                `confidence`, `severity`, `requiresManualReview`, `bboxX`, `bboxY`, `bboxWidth`, `bboxHeight`,
                `code`, `description`
            ) VALUES (
                'd-legacy-1', 'HIST-101', 1, 'short', 'Short Circuit',
                0.92, 'CRITICAL', 1, 0.1234, 0.2345, 0.3456, 0.4567, 'PCB-SHORT', 'Test defect'
            )
        """.trimIndent())

        v1Db.close()

        // 3. Open migrated database using Room with MIGRATION_1_2
        val migratedDb = Room.databaseBuilder(context, PcbInspectorDatabase::class.java, "test_migration_v1_to_v2.db")
            .addMigrations(PcbInspectorDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val migratedRepo = InspectionRepositoryImpl(migratedDb.inspectionDao())

        // 4. Assert historical record survived and coordinates match exactly
        val migratedRecord = migratedRepo.getRecordById("HIST-101")
        assertNotNull("Historical record must survive migration", migratedRecord)
        assertEquals("Legacy Board", migratedRecord!!.boardName)
        assertEquals(InspectionTarget.PCB_BOARD, migratedRecord.target)
        assertNull(migratedRecord.transistorAnomaly)
        assertEquals(1, migratedRecord.defects.size)
        val defect = migratedRecord.defects.first()
        assertEquals("d-legacy-1", defect.defectId)
        assertNotNull(defect.boundingBox)
        assertEquals(0.1234f, defect.boundingBox!!.x, 0.00001f)
        assertEquals(0.2345f, defect.boundingBox!!.y, 0.00001f)
        assertEquals(0.3456f, defect.boundingBox!!.width, 0.00001f)
        assertEquals(0.4567f, defect.boundingBox!!.height, 0.00001f)

        // 5. Assert new records with null bounding boxes can be saved and retrieved on migrated DB
        val newRecord = InspectionRecord(
            inspectionId = "MIGRATED-NEW-1",
            timestampEpochMs = 1760000050000,
            boardName = "Transistor Migrated Test",
            sourceType = PcbImageSource.GALLERY,
            imageUri = "file:///tr.jpg",
            imageWidth = 256,
            imageHeight = 256,
            lowClarityRisk = false,
            clarityScore = 200.0,
            officialDisposition = InspectionDisposition.REVIEW,
            policyProfile = "Default",
            policyVersion = "1.0",
            policyConfigSnapshot = InspectionPolicyConfig(),
            effectiveThresholdsSummary = "summary",
            policyRationale = "REVIEW: Anomaly",
            defects = listOf(
                DetectedDefect(
                    defectId = "d-tr-migrated",
                    classIndex = 0,
                    rawClassName = "transistor_anomaly",
                    canonicalClassName = "Transistor Anomaly",
                    confidence = 0.85f,
                    severity = DefectSeverity.MANUAL_REVIEW,
                    requiresManualReview = true,
                    boundingBox = null
                )
            ),
            totalDefects = 1,
            isDemonstration = false,
            provenance = ModelExecutionProvenance.unavailableProduction("1.22.0"),
            advisory = AiAdvisory(AdvisoryAvailability.UNAVAILABLE, false),
            latencyMetrics = InspectionLatencyMetrics(),
            target = InspectionTarget.TRANSISTOR
        )

        migratedRepo.saveInspectionAtomically(newRecord)
        val loadedNew = migratedRepo.getRecordById("MIGRATED-NEW-1")
        assertNotNull(loadedNew)
        assertEquals(InspectionTarget.TRANSISTOR, loadedNew!!.target)
        assertNull("Bounding box must be null", loadedNew.defects.first().boundingBox)

        migratedDb.close()
        dbFile.delete()
    }
}
