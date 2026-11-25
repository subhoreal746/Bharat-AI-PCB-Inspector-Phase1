package com.example.data.local.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "inspection_records",
    indices = [
        Index(value = ["timestampEpochMs"]),
        Index(value = ["officialDisposition"])
    ]
)
data class InspectionRecordEntity(
    @PrimaryKey val inspectionId: String,
    val timestampEpochMs: Long,
    val boardName: String,
    val sourceType: String,
    val imageUri: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val lowClarityRisk: Boolean,
    val clarityScore: Double?,
    val officialDisposition: String,
    val policyProfile: String,
    val policyVersion: String,
    val policyConfigSnapshotJson: String,
    val effectiveThresholdsSummary: String,
    val policyRationale: String,
    val totalDefects: Int,
    val isDemonstration: Boolean,
    // Model execution provenance
    val inferenceEngine: String,
    val modelIdentity: String,
    val modelSha256: String,
    val modelContractVersion: String,
    val preprocessingVersion: String,
    val decoderVersion: String,
    val classMappingVersion: String,
    val runtimeVersion: String,
    // Advisory snapshot
    val advisoryAvailability: String,
    val probableRootCause: String?,
    val recommendedOperatorAction: String?,
    val relevantStandard: String?,
    val suggestedDisposition: String?,
    val advisoryLimitations: String?,
    val advisoryUnavailableReason: String?,
    val advisoryProvenanceSummary: String?,
    // Nullable stage timings
    val imagePreprocessingMs: Long?,
    val yoloInferenceMs: Long?,
    val patchCoreInferenceMs: Long? = null,
    val outputDecodingNmsMs: Long?,
    val policyEvaluationMs: Long?,
    val llmAdvisoryMs: Long?,
    val totalDurationMs: Long?,
    // v2 additions
    val target: String = "PCB_BOARD",
    val transistorRawScore: Float? = null,
    val transistorDisplayScore: Float? = null,
    val transistorIsAnomalous: Boolean? = null,
    val transistorAffectedAreaPercent: Float? = null,
    val completedEpochMs: Long? = null
)

@Entity(
    tableName = "detected_defects",
    foreignKeys = [
        ForeignKey(
            entity = InspectionRecordEntity::class,
            parentColumns = ["inspectionId"],
            childColumns = ["inspectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["inspectionId"])]
)
data class DetectedDefectEntity(
    @PrimaryKey val defectId: String,
    val inspectionId: String,
    val classIndex: Int,
    val rawClassName: String,
    val canonicalClassName: String,
    val confidence: Float,
    val severity: String,
    val requiresManualReview: Boolean,
    val bboxX: Float?,
    val bboxY: Float?,
    val bboxWidth: Float?,
    val bboxHeight: Float?,
    val code: String?,
    val description: String?
)

enum class OutboxStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

@Entity(
    tableName = "csv_audit_outbox",
    indices = [
        Index(value = ["inspectionId"], unique = true),
        Index(value = ["status"])
    ]
)
data class CsvAuditOutboxEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val inspectionId: String,
    val status: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdEpochMs: Long,
    val updatedEpochMs: Long
)

@Entity(tableName = "csv_audit_ledger")
data class CsvAuditLedgerEntity(
    @PrimaryKey val inspectionId: String,
    val rowDigestSha256: String,
    val csvRowCount: Int,
    val writtenEpochMs: Long
)

data class InspectionRecordWithDefects(
    @Embedded val record: InspectionRecordEntity,
    @Relation(
        parentColumn = "inspectionId",
        entityColumn = "inspectionId"
    )
    val defects: List<DetectedDefectEntity>
)
