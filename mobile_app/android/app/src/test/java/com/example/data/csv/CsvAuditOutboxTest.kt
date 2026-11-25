package com.example.data.csv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.PcbInspectorDatabase
import com.example.data.repository.InspectionRepositoryImpl
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
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CsvAuditOutboxTest {

    private lateinit var context: Context
    private lateinit var db: PcbInspectorDatabase
    private lateinit var repository: InspectionRepositoryImpl
    private lateinit var csvExport: CsvExportManagerImpl
    private lateinit var csvLogger: InspectionCsvLoggerImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PcbInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InspectionRepositoryImpl(db.inspectionDao())
        csvExport = CsvExportManagerImpl()
        csvLogger = InspectionCsvLoggerImpl(context, db.inspectionDao(), repository, csvExport)
        File(csvLogger.getAuditFilePath()).delete()
    }

    @After
    fun teardown() {
        File(csvLogger.getAuditFilePath()).delete()
        db.close()
    }

    private fun sampleRecord(
        id: String,
        boardName: String = "Board-A",
        disposition: InspectionDisposition = InspectionDisposition.PASS,
        defects: List<DetectedDefect> = emptyList(),
        isDemo: Boolean = false,
        rootCause: String? = null,
        target: InspectionTarget = InspectionTarget.PCB_BOARD,
        transistorAnomaly: TransistorAnomalyData? = null,
        yoloMs: Long? = null,
        patchCoreMs: Long? = null,
        totalDurationMs: Long = 15L,
        completedEpochMs: Long? = null
    ): InspectionRecord {
        val cfg = InspectionPolicyConfig()
        return InspectionRecord(
            inspectionId = id,
            timestampEpochMs = 1_760_000_000_000L,
            boardName = boardName,
            sourceType = PcbImageSource.CAMERA,
            imageUri = "file:///tmp/$id.jpg",
            imageWidth = 1200,
            imageHeight = 900,
            lowClarityRisk = false,
            clarityScore = 150.0,
            officialDisposition = disposition,
            policyProfile = cfg.profileName,
            policyVersion = cfg.policyVersion,
            policyConfigSnapshot = cfg,
            effectiveThresholdsSummary = cfg.effectiveThresholdsSummary(),
            policyRationale = "Rationale for $id",
            defects = defects,
            totalDefects = defects.size,
            isDemonstration = isDemo,
            provenance = ModelExecutionProvenance.unavailableProduction("1.22.0"),
            advisory = AiAdvisory(
                availability = if (rootCause == null) AdvisoryAvailability.UNAVAILABLE else AdvisoryAvailability.AVAILABLE,
                isDemonstration = isDemo,
                probableRootCause = rootCause,
                recommendedOperatorAction = "Check board"
            ),
            latencyMetrics = InspectionLatencyMetrics(
                imagePreprocessingMs = 10L,
                yoloInferenceMs = yoloMs,
                patchCoreInferenceMs = patchCoreMs,
                outputDecodingNmsMs = null,
                policyEvaluationMs = 2L,
                llmAdvisoryMs = null,
                totalDurationMs = totalDurationMs
            ),
            target = target,
            transistorAnomaly = transistorAnomaly,
            completedEpochMs = completedEpochMs
        )
    }

    @Test
    fun `rfc4180 escaping handles commas quotes and multiline text cleanly in 26-column v2 format`() {
        val rec = sampleRecord(
            id = "PCB-ESC-1",
            boardName = "Main, \"Rev 2\"\nBoard",
            rootCause = "Line 1, \"quoted\"\r\nLine 2",
            yoloMs = 42L,
            totalDurationMs = 125L,
            completedEpochMs = 1_760_000_000_125L
        )
        val csv = csvExport.serializeRecordsToCsv(listOf(rec))
        val parsed = CsvExportManagerImpl.parseRfc4180Rows(csv)
        assertEquals(2, parsed.size)
        assertEquals(26, parsed[0].size)
        assertEquals(26, parsed[1].size)
        assertEquals("Main, \"Rev 2\"\nBoard", parsed[1][2])
        assertEquals("Line 1, \"quoted\"\nLine 2", parsed[1][15].replace("\r\n", "\n"))
        // yolo_inference_ms (col 19)
        assertEquals("42", parsed[1][19])
        // model_inference_latency_ms (col 24)
        assertEquals("42", parsed[1][24])
        // completed_at_utc (col 25)
        assertEquals(CsvExportManagerImpl.formatIsoTimestamp(1_760_000_000_125L), parsed[1][25])
    }

    @Test
    fun `CSV v2 emits empty completed_at_utc when completedEpochMs is null without fallback`() {
        val rec = sampleRecord(
            id = "PCB-NO-COMPLETE",
            totalDurationMs = 100L,
            completedEpochMs = null
        )
        val csv = csvExport.serializeRecordsToCsv(listOf(rec))
        val parsed = CsvExportManagerImpl.parseRfc4180Rows(csv)
        assertEquals(2, parsed.size)
        // Col 26 (index 25) must be empty, never calculated from timestamp + duration
        assertEquals("", parsed[1][25])
    }

    @Test
    fun `CSV v2 serializes target-aware model latency and never cross-pollinates`() {
        // PCB_BOARD with both timings populated must select yoloMs (45L)
        val pcbRec = sampleRecord(
            id = "PCB-LAT",
            target = InspectionTarget.PCB_BOARD,
            yoloMs = 45L,
            patchCoreMs = 12L
        )
        val pcbCsv = csvExport.serializeRecordsToCsv(listOf(pcbRec))
        val pcbRow = CsvExportManagerImpl.parseRfc4180Rows(pcbCsv)[1]
        assertEquals("45", pcbRow[24])

        // TRANSISTOR with both timings populated must select patchCoreMs (18L)
        val trRec = sampleRecord(
            id = "TR-LAT",
            target = InspectionTarget.TRANSISTOR,
            yoloMs = 45L,
            patchCoreMs = 18L
        )
        val trCsv = csvExport.serializeRecordsToCsv(listOf(trRec))
        val trRow = CsvExportManagerImpl.parseRfc4180Rows(trCsv)[1]
        assertEquals("18", trRow[24])

        // Missing selected timing must be empty, never fallback to the other
        val pcbMissing = sampleRecord(
            id = "PCB-MISS",
            target = InspectionTarget.PCB_BOARD,
            yoloMs = null,
            patchCoreMs = 18L
        )
        val pcbMissRow = CsvExportManagerImpl.parseRfc4180Rows(csvExport.serializeRecordsToCsv(listOf(pcbMissing)))[1]
        assertEquals("", pcbMissRow[24])

        val trMissing = sampleRecord(
            id = "TR-MISS",
            target = InspectionTarget.TRANSISTOR,
            yoloMs = 45L,
            patchCoreMs = null
        )
        val trMissRow = CsvExportManagerImpl.parseRfc4180Rows(csvExport.serializeRecordsToCsv(listOf(trMissing)))[1]
        assertEquals("", trMissRow[24])
    }

    @Test
    fun `transistor CSV exports normalizedDisplayScore instead of defect confidence when they differ`() {
        val defect = DetectedDefect(
            defectId = "d-tr-conf",
            classIndex = 0,
            rawClassName = "transistor_anomaly",
            canonicalClassName = "Transistor Anomaly",
            confidence = 0.4000f, // Intentionally different from normalized display score
            severity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            boundingBox = null
        )
        val anomalyData = TransistorAnomalyData(
            rawAnomalyScore = 41.5f,
            normalizedDisplayScore = 0.8800f,
            isAnomalous = true
        )

        // For TRANSISTOR, CSV must export 0.8800
        val trRec = sampleRecord(
            id = "TR-CONF",
            target = InspectionTarget.TRANSISTOR,
            defects = listOf(defect),
            transistorAnomaly = anomalyData
        )
        val trRow = CsvExportManagerImpl.parseRfc4180Rows(csvExport.serializeRecordsToCsv(listOf(trRec)))[1]
        assertEquals("0.8800", trRow[7])

        // For PCB_BOARD, CSV retains defect.confidence (0.4000)
        val pcbRec = sampleRecord(
            id = "PCB-CONF",
            target = InspectionTarget.PCB_BOARD,
            defects = listOf(defect),
            transistorAnomaly = null
        )
        val pcbRow = CsvExportManagerImpl.parseRfc4180Rows(csvExport.serializeRecordsToCsv(listOf(pcbRec)))[1]
        assertEquals("0.4000", pcbRow[7])
    }

    @Test
    fun `zero defect and multi defect records write expected rows and outbox reconciles crash window`() = runTest {
        val zeroRec = sampleRecord(id = "PCB-ZERO", disposition = InspectionDisposition.PASS, defects = emptyList())
        val multiRec = sampleRecord(
            id = "PCB-MULTI",
            disposition = InspectionDisposition.REJECT,
            isDemo = true,
            defects = listOf(
                DetectedDefect("d1", 0, "open", "Open Circuit", 0.9f, DefectSeverity.CRITICAL, true, BoundingBox(0.1f, 0.1f, 0.2f, 0.2f)),
                DetectedDefect("d2", 3, "spur", "Spur", 0.7f, DefectSeverity.MEDIUM, true, BoundingBox(0.5f, 0.5f, 0.1f, 0.1f))
            )
        )

        repository.saveInspectionAtomically(zeroRec)
        repository.saveInspectionAtomically(multiRec)

        // Simulate crash window: PCB-ZERO already flushed to CSV on disk before Room outbox ack
        val auditFile = File(csvLogger.getAuditFilePath())
        auditFile.parentFile?.mkdirs()
        auditFile.writeText(
            CsvExportManagerImpl.CSV_HEADER + "\r\n" + csvExport.serializeRowsOnly(zeroRec),
            StandardCharsets.UTF_8
        )

        val status = csvLogger.processPendingOutboxEvents()
        assertEquals(0, status.pendingCount)
        assertEquals(2, status.confirmedCount)

        val rows = CsvExportManagerImpl.parseRfc4180Rows(auditFile.readText(StandardCharsets.UTF_8))
        // Header + 1 row for PCB-ZERO (no duplicate!) + 2 rows for PCB-MULTI = 4 rows
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it[0] == "PCB-ZERO" })
        assertEquals(2, rows.count { it[0] == "PCB-MULTI" })
    }

    @Test
    fun `corrupted partial tail triggers deterministic full CSV regeneration from Room`() = runTest {
        val rec = sampleRecord(id = "PCB-RECOVER")
        repository.saveInspectionAtomically(rec)

        val auditFile = File(csvLogger.getAuditFilePath())
        auditFile.parentFile?.mkdirs()
        // Corrupt/truncate partial line simulating process death mid-flush
        auditFile.writeText(CsvExportManagerImpl.CSV_HEADER + "\r\nPCB-RECOVER,truncated,broken", StandardCharsets.UTF_8)

        val status = csvLogger.processPendingOutboxEvents()
        assertEquals(0, status.pendingCount)
        assertEquals(1, status.confirmedCount)

        val (ids, healthy) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(
            auditFile.readText(StandardCharsets.UTF_8)
        )
        assertTrue(healthy)
        assertEquals(setOf("PCB-RECOVER"), ids)
    }

    @Test
    fun `suggested filename sanitizes unsafe characters`() {
        assertEquals(
            "bharat_ai_PCB_2026_01.csv",
            csvExport.suggestedFileNameForRecord("PCB/2026:01")
        )
        assertFalse(csvExport.suggestedFileNameForRecord("../bad").contains("/"))
    }

    @Test
    fun `v1 24-column CSV files remain readable and integrity validation passes`() {
        val v1Csv = """
            ${CsvExportManagerImpl.CSV_HEADER_V1}
            REC-V1-1,2026-11-18T00:00:00.000Z,Board-V1,PASS,0,,,,,,,,,PASS,None,None,None,false,10,,5,2,,17
            REC-V1-2,2026-11-18T00:01:00.000Z,Board-V1,REJECT,1,d1,open,0.9500,CRITICAL,0.1000,0.2000,0.3000,0.4000,REJECT,None,None,None,false,10,35,5,2,,52
        """.trimIndent()

        val (ids, healthy) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(v1Csv)
        assertTrue("v1 CSV must be recognized as healthy", healthy)
        assertEquals(setOf("REC-V1-1", "REC-V1-2"), ids)

        val parsedRows = CsvExportManagerImpl.parseCsvToRows(v1Csv)
        assertNotNull(parsedRows)
        assertEquals(2, parsedRows!!.size)
        assertEquals("REC-V1-1", parsedRows[0].inspectionId)
        assertNull("Zero defect record must parse null bounding box", parsedRows[0].boundingBox)
        assertNull("v1 row must have null model latency", parsedRows[0].modelInferenceLatencyMs)
        assertNull("v1 row must have null completedAtUtc", parsedRows[0].completedAtUtc)

        assertEquals("REC-V1-2", parsedRows[1].inspectionId)
        assertEquals(BoundingBox(0.1f, 0.2f, 0.3f, 0.4f), parsedRows[1].boundingBox)
    }

    @Test
    fun `strict coordinate validation rejects partially empty coordinates under v1 and v2`() {
        // v1 partially empty coordinates (col 10 has 0.1000, but cols 11-13 empty)
        val v1Partial = """
            ${CsvExportManagerImpl.CSV_HEADER_V1}
            REC-V1-PARTIAL,2026-11-18T00:00:00.000Z,Board-V1,REJECT,1,d1,open,0.9500,CRITICAL,0.1000,,,,REJECT,None,None,None,false,10,35,5,2,,52
        """.trimIndent()

        val (idsV1, healthyV1) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(v1Partial)
        assertFalse("Partially empty coordinates must fail integrity validation in v1", healthyV1)
        assertNull("Partially empty coordinates must reject parsing in v1", CsvExportManagerImpl.parseCsvToRows(v1Partial))

        // v2 partially empty coordinates
        val v2Partial = """
            ${CsvExportManagerImpl.CSV_HEADER_V2}
            REC-V2-PARTIAL,2026-11-18T00:00:00.000Z,Board-V2,REJECT,1,d1,open,0.9500,CRITICAL,,0.2000,0.3000,,REJECT,None,None,None,false,10,35,5,2,,52,35,2026-11-18T00:00:52.000Z
        """.trimIndent()

        val (idsV2, healthyV2) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(v2Partial)
        assertFalse("Partially empty coordinates must fail integrity validation in v2", healthyV2)
        assertNull("Partially empty coordinates must reject parsing in v2", CsvExportManagerImpl.parseCsvToRows(v2Partial))
    }

    @Test
    fun `strict coordinate validation rejects invalid numeric coordinates under v1 and v2`() {
        // Non-numeric coordinate string "bad_coord"
        val v1Invalid = """
            ${CsvExportManagerImpl.CSV_HEADER_V1}
            REC-V1-BAD,2026-11-18T00:00:00.000Z,Board-V1,REJECT,1,d1,open,0.9500,CRITICAL,0.1000,bad_coord,0.3000,0.4000,REJECT,None,None,None,false,10,35,5,2,,52
        """.trimIndent()

        val (idsV1, healthyV1) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(v1Invalid)
        assertFalse("Non-numeric coordinates must fail integrity validation in v1", healthyV1)
        assertNull("Non-numeric coordinates must reject parsing in v1", CsvExportManagerImpl.parseCsvToRows(v1Invalid))

        // NaN coordinate string
        val v2Nan = """
            ${CsvExportManagerImpl.CSV_HEADER_V2}
            REC-V2-NAN,2026-11-18T00:00:00.000Z,Board-V2,REJECT,1,d1,open,0.9500,CRITICAL,0.1000,NaN,0.3000,0.4000,REJECT,None,None,None,false,10,35,5,2,,52,35,2026-11-18T00:00:52.000Z
        """.trimIndent()

        val (idsV2, healthyV2) = CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(v2Nan)
        assertFalse("NaN coordinates must fail integrity validation in v2", healthyV2)
        assertNull("NaN coordinates must reject parsing in v2", CsvExportManagerImpl.parseCsvToRows(v2Nan))
    }
}
