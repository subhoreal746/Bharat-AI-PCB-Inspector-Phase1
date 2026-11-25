package com.example.data.repository

import com.example.data.local.database.CsvAuditOutboxEntity
import com.example.data.local.database.DetectedDefectEntity
import com.example.data.local.database.InspectionDao
import com.example.data.local.database.InspectionRecordEntity
import com.example.data.local.database.InspectionRecordWithDefects
import com.example.data.local.database.OutboxStatus
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
import com.example.domain.repository.InspectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InspectionRepositoryImpl(
    private val dao: InspectionDao,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : InspectionRepository {

    override fun observeAllRecords(): Flow<List<InspectionRecord>> =
        dao.observeAllRecordsWithDefects().map { list -> list.map { it.toDomain() } }

    override fun observeRecordById(inspectionId: String): Flow<InspectionRecord?> =
        dao.observeRecordById(inspectionId).map { it?.toDomain() }

    override suspend fun getRecordById(inspectionId: String): InspectionRecord? =
        dao.getRecordById(inspectionId)?.toDomain()

    override suspend fun saveInspectionAtomically(record: InspectionRecord) {
        val now = System.currentTimeMillis()
        val entity = record.toEntity(json)
        val defectEntities = record.defects.map { it.toEntity(record.inspectionId) }
        val outbox = CsvAuditOutboxEntity(
            inspectionId = record.inspectionId,
            status = OutboxStatus.PENDING.name,
            retryCount = 0,
            lastError = null,
            createdEpochMs = now,
            updatedEpochMs = now
        )
        dao.saveInspectionWithOutbox(entity, defectEntities, outbox)
    }

    override suspend fun clearAllHistory() {
        dao.clearAllHistoryAndAudit()
    }

    override suspend fun getReferencedImageUris(): Set<String> =
        dao.getAllReferencedImageUris().toSet()

    override suspend fun getPendingOutboxCount(): Int = dao.getPendingOutboxCount()

    override suspend fun getConfirmedCsvLedgerCount(): Int = dao.getConfirmedLedgerCount()

    private fun InspectionRecordWithDefects.toDomain(): InspectionRecord {
        val snapshot = runCatching {
            json.decodeFromString<InspectionPolicyConfig>(record.policyConfigSnapshotJson)
        }.getOrElse {
            InspectionPolicyConfig(
                profileName = record.policyProfile,
                policyVersion = record.policyVersion
            )
        }
        return InspectionRecord(
            inspectionId = record.inspectionId,
            timestampEpochMs = record.timestampEpochMs,
            boardName = record.boardName,
            sourceType = enumOrDefault(record.sourceType, PcbImageSource.CAMERA),
            imageUri = record.imageUri,
            imageWidth = record.imageWidth,
            imageHeight = record.imageHeight,
            lowClarityRisk = record.lowClarityRisk,
            clarityScore = record.clarityScore,
            officialDisposition = enumOrDefault(
                record.officialDisposition,
                InspectionDisposition.INCOMPLETE
            ),
            policyProfile = record.policyProfile,
            policyVersion = record.policyVersion,
            policyConfigSnapshot = snapshot,
            effectiveThresholdsSummary = record.effectiveThresholdsSummary,
            policyRationale = record.policyRationale,
            defects = defects.map { it.toDomain() },
            totalDefects = record.totalDefects,
            isDemonstration = record.isDemonstration,
            provenance = ModelExecutionProvenance(
                inferenceEngine = record.inferenceEngine,
                modelIdentity = record.modelIdentity,
                modelSha256 = record.modelSha256,
                modelContractVersion = record.modelContractVersion,
                preprocessingVersion = record.preprocessingVersion,
                decoderVersion = record.decoderVersion,
                classMappingVersion = record.classMappingVersion,
                runtimeVersion = record.runtimeVersion
            ),
            advisory = AiAdvisory(
                availability = enumOrDefault(
                    record.advisoryAvailability,
                    AdvisoryAvailability.UNAVAILABLE
                ),
                isDemonstration = record.isDemonstration,
                probableRootCause = record.probableRootCause,
                recommendedOperatorAction = record.recommendedOperatorAction,
                relevantStandard = record.relevantStandard,
                suggestedDisposition = record.suggestedDisposition?.let {
                    runCatching { InspectionDisposition.valueOf(it) }.getOrNull()
                },
                limitations = record.advisoryLimitations,
                unavailableReason = record.advisoryUnavailableReason,
                advisoryLatencyMs = record.llmAdvisoryMs,
                provenanceSummary = record.advisoryProvenanceSummary
            ),
            latencyMetrics = InspectionLatencyMetrics(
                imagePreprocessingMs = record.imagePreprocessingMs,
                yoloInferenceMs = record.yoloInferenceMs,
                patchCoreInferenceMs = record.patchCoreInferenceMs,
                outputDecodingNmsMs = record.outputDecodingNmsMs,
                policyEvaluationMs = record.policyEvaluationMs,
                llmAdvisoryMs = record.llmAdvisoryMs,
                totalDurationMs = record.totalDurationMs
            ),
            target = enumOrDefault(record.target, InspectionTarget.PCB_BOARD),
            transistorAnomaly = if (record.transistorRawScore != null && record.transistorDisplayScore != null && record.transistorIsAnomalous != null) {
                TransistorAnomalyData(
                    rawAnomalyScore = record.transistorRawScore,
                    normalizedDisplayScore = record.transistorDisplayScore,
                    isAnomalous = record.transistorIsAnomalous,
                    affectedAreaPercent = record.transistorAffectedAreaPercent,
                    boundingBox = defects.firstOrNull()?.let { d ->
                        if (d.bboxX != null && d.bboxY != null && d.bboxWidth != null && d.bboxHeight != null) {
                            BoundingBox(d.bboxX, d.bboxY, d.bboxWidth, d.bboxHeight)
                        } else null
                    }
                )
            } else null,
            completedEpochMs = record.completedEpochMs
        )
    }

    private fun DetectedDefectEntity.toDomain(): DetectedDefect =
        DetectedDefect(
            defectId = defectId,
            classIndex = classIndex,
            rawClassName = rawClassName,
            canonicalClassName = canonicalClassName,
            confidence = confidence,
            severity = enumOrDefault(severity, DefectSeverity.MANUAL_REVIEW),
            requiresManualReview = requiresManualReview,
            boundingBox = if (bboxX != null && bboxY != null && bboxWidth != null && bboxHeight != null) {
                BoundingBox(bboxX, bboxY, bboxWidth, bboxHeight)
            } else {
                null
            },
            code = code,
            description = description
        )

    private fun InspectionRecord.toEntity(json: Json): InspectionRecordEntity =
        InspectionRecordEntity(
            inspectionId = inspectionId,
            timestampEpochMs = timestampEpochMs,
            boardName = boardName,
            sourceType = sourceType.name,
            imageUri = imageUri,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            lowClarityRisk = lowClarityRisk,
            clarityScore = clarityScore,
            officialDisposition = officialDisposition.name,
            policyProfile = policyProfile,
            policyVersion = policyVersion,
            policyConfigSnapshotJson = json.encodeToString(policyConfigSnapshot),
            effectiveThresholdsSummary = effectiveThresholdsSummary,
            policyRationale = policyRationale,
            totalDefects = totalDefects,
            isDemonstration = isDemonstration,
            inferenceEngine = provenance.inferenceEngine,
            modelIdentity = provenance.modelIdentity,
            modelSha256 = provenance.modelSha256,
            modelContractVersion = provenance.modelContractVersion,
            preprocessingVersion = provenance.preprocessingVersion,
            decoderVersion = provenance.decoderVersion,
            classMappingVersion = provenance.classMappingVersion,
            runtimeVersion = provenance.runtimeVersion,
            advisoryAvailability = advisory.availability.name,
            probableRootCause = advisory.probableRootCause,
            recommendedOperatorAction = advisory.recommendedOperatorAction,
            relevantStandard = advisory.relevantStandard,
            suggestedDisposition = advisory.suggestedDisposition?.name,
            advisoryLimitations = advisory.limitations,
            advisoryUnavailableReason = advisory.unavailableReason,
            advisoryProvenanceSummary = advisory.provenanceSummary,
            imagePreprocessingMs = latencyMetrics.imagePreprocessingMs,
            yoloInferenceMs = latencyMetrics.yoloInferenceMs,
            patchCoreInferenceMs = latencyMetrics.patchCoreInferenceMs,
            outputDecodingNmsMs = latencyMetrics.outputDecodingNmsMs,
            policyEvaluationMs = latencyMetrics.policyEvaluationMs,
            llmAdvisoryMs = latencyMetrics.llmAdvisoryMs,
            totalDurationMs = latencyMetrics.totalDurationMs,
            target = target.name,
            transistorRawScore = transistorAnomaly?.rawAnomalyScore,
            transistorDisplayScore = transistorAnomaly?.normalizedDisplayScore,
            transistorIsAnomalous = transistorAnomaly?.isAnomalous,
            transistorAffectedAreaPercent = transistorAnomaly?.affectedAreaPercent,
            completedEpochMs = completedEpochMs
        )

    private fun DetectedDefect.toEntity(inspectionId: String): DetectedDefectEntity =
        DetectedDefectEntity(
            defectId = defectId,
            inspectionId = inspectionId,
            classIndex = classIndex,
            rawClassName = rawClassName,
            canonicalClassName = canonicalClassName,
            confidence = confidence,
            severity = severity.name,
            requiresManualReview = requiresManualReview,
            bboxX = boundingBox?.x,
            bboxY = boundingBox?.y,
            bboxWidth = boundingBox?.width,
            bboxHeight = boundingBox?.height,
            code = code,
            description = description
        )

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
        runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
}
