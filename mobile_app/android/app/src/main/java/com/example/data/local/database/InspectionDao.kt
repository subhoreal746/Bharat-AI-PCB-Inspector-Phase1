package com.example.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionDao {

    @Transaction
    @Query("SELECT * FROM inspection_records ORDER BY timestampEpochMs DESC")
    fun observeAllRecordsWithDefects(): Flow<List<InspectionRecordWithDefects>>

    @Transaction
    @Query("SELECT * FROM inspection_records WHERE inspectionId = :inspectionId")
    fun observeRecordById(inspectionId: String): Flow<InspectionRecordWithDefects?>

    @Transaction
    @Query("SELECT * FROM inspection_records WHERE inspectionId = :inspectionId")
    suspend fun getRecordById(inspectionId: String): InspectionRecordWithDefects?

    @Transaction
    @Query("SELECT * FROM inspection_records ORDER BY timestampEpochMs ASC")
    suspend fun getAllRecordsAsc(): List<InspectionRecordWithDefects>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: InspectionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDefects(defects: List<DetectedDefectEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboxEvent(event: CsvAuditOutboxEntity)

    @Transaction
    suspend fun saveInspectionWithOutbox(
        record: InspectionRecordEntity,
        defects: List<DetectedDefectEntity>,
        outboxEvent: CsvAuditOutboxEntity
    ) {
        insertRecord(record)
        if (defects.isNotEmpty()) {
            insertDefects(defects)
        }
        insertOutboxEvent(outboxEvent)
    }

    @Query("SELECT imageUri FROM inspection_records")
    suspend fun getAllReferencedImageUris(): List<String>

    @Query("DELETE FROM inspection_records")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM csv_audit_outbox")
    suspend fun deleteAllOutboxEvents()

    @Query("DELETE FROM csv_audit_ledger")
    suspend fun deleteAllLedgerEntries()

    @Transaction
    suspend fun clearAllHistoryAndAudit() {
        deleteAllRecords()
        deleteAllOutboxEvents()
        deleteAllLedgerEntries()
    }

    @Query(
        "SELECT * FROM csv_audit_outbox WHERE status IN ('PENDING', 'FAILED', 'IN_PROGRESS') ORDER BY eventId ASC"
    )
    suspend fun getActionableOutboxEvents(): List<CsvAuditOutboxEntity>

    @Query(
        "UPDATE csv_audit_outbox SET status = :status, updatedEpochMs = :updatedEpochMs WHERE eventId = :eventId"
    )
    suspend fun updateOutboxStatus(eventId: Long, status: String, updatedEpochMs: Long)

    @Query(
        "UPDATE csv_audit_outbox SET status = 'FAILED', retryCount = retryCount + 1, lastError = :error, updatedEpochMs = :updatedEpochMs WHERE eventId = :eventId"
    )
    suspend fun markOutboxFailed(eventId: Long, error: String, updatedEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedgerEntry(entry: CsvAuditLedgerEntity)

    @Query("SELECT * FROM csv_audit_ledger WHERE inspectionId = :inspectionId")
    suspend fun getLedgerEntry(inspectionId: String): CsvAuditLedgerEntity?

    @Query("SELECT inspectionId FROM csv_audit_ledger")
    suspend fun getAllLedgerInspectionIds(): List<String>

    @Transaction
    suspend fun markOutboxCompletedWithLedger(
        eventId: Long,
        ledgerEntry: CsvAuditLedgerEntity,
        updatedEpochMs: Long
    ) {
        upsertLedgerEntry(ledgerEntry)
        updateOutboxStatus(eventId, OutboxStatus.COMPLETED.name, updatedEpochMs)
    }

    @Query("SELECT COUNT(*) FROM csv_audit_outbox WHERE status != 'COMPLETED'")
    suspend fun getPendingOutboxCount(): Int

    @Query("SELECT COUNT(*) FROM csv_audit_outbox WHERE status = 'FAILED'")
    suspend fun getFailedOutboxCount(): Int

    @Query("SELECT COUNT(*) FROM csv_audit_ledger")
    suspend fun getConfirmedLedgerCount(): Int
}
