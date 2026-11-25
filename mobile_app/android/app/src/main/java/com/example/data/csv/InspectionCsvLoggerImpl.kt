package com.example.data.csv

import android.content.Context
import com.example.data.local.database.CsvAuditLedgerEntity
import com.example.data.local.database.InspectionDao
import com.example.data.local.database.OutboxStatus
import com.example.domain.model.InspectionRecord
import com.example.domain.repository.CsvAuditStatus
import com.example.domain.repository.InspectionCsvLogger
import com.example.domain.repository.InspectionRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Automated internal audit CSV worker backed by the Room outbox and deduplication ledger.
 *
 * Crash-window guarantees:
 * - Provides at-least-once outbox processing with idempotent inspection_id deduplication and
 *   deterministic atomic full regeneration from Room whenever partial tail corruption or
 *   ledger/file desync is detected.
 * - Never blocks or fails inspection completion if CSV file I/O fails.
 */
class InspectionCsvLoggerImpl(
    private val context: Context,
    private val dao: InspectionDao,
    private val repository: InspectionRepository,
    private val csvExportManager: CsvExportManagerImpl,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : InspectionCsvLogger {

    private val fileMutex = Mutex()

    private val auditDir: File
        get() = File(context.filesDir, "audit_logs").apply { mkdirs() }

    private val auditFile: File
        get() = File(auditDir, CsvExportManagerImpl.DEFAULT_AUDIT_FILENAME)

    override suspend fun processPendingOutboxEvents(): CsvAuditStatus =
        fileMutex.withLock {
            withContext(ioDispatcher) {
                var lastError: String? = null
                try {
                    ensureAuditHeaderAndReconcileIfNeeded()

                    val actionable = dao.getActionableOutboxEvents()
                    if (actionable.isEmpty()) {
                        return@withContext currentStatus(null)
                    }

                    val existingText = if (auditFile.exists()) {
                        auditFile.readText(StandardCharsets.UTF_8)
                    } else {
                        ""
                    }
                    val (existingCsvIds, fileHealthy) =
                        CsvExportManagerImpl.extractInspectionIdsAndValidateIntegrity(existingText)

                    if (!fileHealthy) {
                        // Corruption or partial tail write detected: regenerate deterministically from Room
                        regenerateInternalLocked()
                    } else {
                        val confirmedIds = dao.getAllLedgerInspectionIds().toSet()
                        val idsOnDisk = existingCsvIds.toMutableSet()

                        for (event in actionable) {
                            val now = System.currentTimeMillis()
                            try {
                                dao.updateOutboxStatus(event.eventId, OutboxStatus.IN_PROGRESS.name, now)
                                val record = repository.getRecordById(event.inspectionId)
                                if (record == null) {
                                    // Record was cleared; complete orphan outbox event safely
                                    dao.updateOutboxStatus(event.eventId, OutboxStatus.COMPLETED.name, now)
                                    continue
                                }

                                val rowsPayload = csvExportManager.serializeRowsOnly(record)
                                val digest = sha256(rowsPayload)
                                val expectedRowCount = record.defects.size.coerceAtLeast(1)

                                if (idsOnDisk.contains(event.inspectionId) || confirmedIds.contains(event.inspectionId)) {
                                    // Crash-window reconciliation: row was already flushed on disk before Room ack
                                    dao.markOutboxCompletedWithLedger(
                                        eventId = event.eventId,
                                        ledgerEntry = CsvAuditLedgerEntity(
                                            inspectionId = event.inspectionId,
                                            rowDigestSha256 = digest,
                                            csvRowCount = expectedRowCount,
                                            writtenEpochMs = now
                                        ),
                                        updatedEpochMs = now
                                    )
                                } else {
                                    appendAndSync(rowsPayload)
                                    idsOnDisk.add(event.inspectionId)
                                    dao.markOutboxCompletedWithLedger(
                                        eventId = event.eventId,
                                        ledgerEntry = CsvAuditLedgerEntity(
                                            inspectionId = event.inspectionId,
                                            rowDigestSha256 = digest,
                                            csvRowCount = expectedRowCount,
                                            writtenEpochMs = now
                                        ),
                                        updatedEpochMs = now
                                    )
                                }
                            } catch (e: Throwable) {
                                lastError = e.message ?: "CSV append failed"
                                dao.markOutboxFailed(
                                    eventId = event.eventId,
                                    error = lastError!!,
                                    updatedEpochMs = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                } catch (e: Throwable) {
                    lastError = e.message ?: "CSV audit reconciliation failed"
                }
                currentStatus(lastError)
            }
        }

    override suspend fun regenerateAuditCsvFromRoom(): Result<String> =
        fileMutex.withLock {
            withContext(ioDispatcher) {
                runCatching {
                    regenerateInternalLocked()
                    auditFile.absolutePath
                }
            }
        }

    override suspend fun clearAuditLogAndLedger() {
        fileMutex.withLock {
            withContext(ioDispatcher) {
                if (auditFile.exists()) {
                    auditFile.delete()
                }
                ensureHeaderFileCreated()
            }
        }
    }

    override suspend fun getAuditFileSizeBytes(): Long = withContext(ioDispatcher) {
        if (auditFile.exists()) auditFile.length() else 0L
    }

    override fun getAuditFilePath(): String = auditFile.absolutePath

    private suspend fun ensureAuditHeaderAndReconcileIfNeeded() {
        if (!auditFile.exists() || auditFile.length() == 0L) {
            if (dao.getConfirmedLedgerCount() > 0) {
                regenerateInternalLocked()
            } else {
                ensureHeaderFileCreated()
            }
        }
    }

    private fun ensureHeaderFileCreated() {
        FileOutputStream(auditFile, false).use { fos ->
            fos.write((CsvExportManagerImpl.CSV_HEADER + "\r\n").toByteArray(StandardCharsets.UTF_8))
            fos.fd.sync()
        }
    }

    private fun appendAndSync(payload: String) {
        FileOutputStream(auditFile, true).use { fos ->
            fos.write(payload.toByteArray(StandardCharsets.UTF_8))
            fos.fd.sync()
        }
    }

    private suspend fun regenerateInternalLocked() {
        val allEntities = dao.getAllRecordsAsc()
        val domainRecords = mutableListOf<InspectionRecord>()
        for (entity in allEntities) {
            repository.getRecordById(entity.record.inspectionId)?.let { domainRecords.add(it) }
        }

        val fullCsv = csvExportManager.serializeRecordsToCsv(domainRecords)
        val tempFile = File(auditDir, "${CsvExportManagerImpl.DEFAULT_AUDIT_FILENAME}.tmp")
        FileOutputStream(tempFile, false).use { fos ->
            fos.write(fullCsv.toByteArray(StandardCharsets.UTF_8))
            fos.fd.sync()
        }
        if (auditFile.exists()) {
            auditFile.delete()
        }
        check(tempFile.renameTo(auditFile)) {
            "Atomic CSV rename failed during deterministic regeneration."
        }

        val now = System.currentTimeMillis()
        val pendingById = dao.getActionableOutboxEvents().associateBy { it.inspectionId }
        for (rec in domainRecords) {
            val rowsPayload = csvExportManager.serializeRowsOnly(rec)
            val ledger = CsvAuditLedgerEntity(
                inspectionId = rec.inspectionId,
                rowDigestSha256 = sha256(rowsPayload),
                csvRowCount = rec.defects.size.coerceAtLeast(1),
                writtenEpochMs = now
            )
            val outbox = pendingById[rec.inspectionId]
            if (outbox != null) {
                dao.markOutboxCompletedWithLedger(outbox.eventId, ledger, now)
            } else {
                dao.upsertLedgerEntry(ledger)
            }
        }
    }

    private suspend fun currentStatus(lastError: String?): CsvAuditStatus = CsvAuditStatus(
        pendingCount = dao.getPendingOutboxCount(),
        confirmedCount = dao.getConfirmedLedgerCount(),
        failedCount = dao.getFailedOutboxCount(),
        lastError = lastError
    )

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
