package com.example.data.csv

import com.example.domain.model.BoundingBox
import com.example.domain.model.InspectionRecord
import com.example.domain.model.InspectionTarget
import com.example.domain.repository.CsvExportManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure RFC 4180 CSV serializer preserving audit contracts across CSV v1 (24 cols) and v2 (26 cols).
 *
 * - One row per detection; zero-detection and INCOMPLETE records emit one row with empty defect columns.
 * - Missing/unexecuted latencies remain empty strings, never "0".
 * - Null bounding-box coordinates serialize as empty strings in columns 10-13.
 * - Completed timestamp uses actual completedEpochMs without fallback calculation.
 * - Model latency is strictly target-aware (PCB_BOARD -> yoloInferenceMs, TRANSISTOR -> patchCoreInferenceMs).
 * - Transistor confidence exports normalizedDisplayScore.
 */
class CsvExportManagerImpl : CsvExportManager {

    override fun serializeRecordsToCsv(records: List<InspectionRecord>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (record in records) {
            appendRecordRows(sb, record)
        }
        return sb.toString()
    }

    fun serializeRowsOnly(record: InspectionRecord): String {
        val sb = StringBuilder()
        appendRecordRows(sb, record)
        return sb.toString()
    }

    fun appendRecordRows(sb: StringBuilder, record: InspectionRecord) {
        val isoTimestamp = formatIsoTimestamp(record.timestampEpochMs)
        val prepMs = record.latencyMetrics.imagePreprocessingMs?.toString().orEmpty()
        val yoloMs = record.latencyMetrics.yoloInferenceMs?.toString().orEmpty()
        val decodeMs = record.latencyMetrics.outputDecodingNmsMs?.toString().orEmpty()
        val policyMs = record.latencyMetrics.policyEvaluationMs?.toString().orEmpty()
        val llmMs = record.latencyMetrics.llmAdvisoryMs?.toString().orEmpty()
        val totalMs = record.latencyMetrics.totalDurationMs?.toString().orEmpty()
        val modelLatencyMs = record.modelInferenceLatencyMs?.toString().orEmpty()
        val completedAtUtc = record.completedEpochMs?.let { formatIsoTimestamp(it) }.orEmpty()

        val rootCause = record.advisory.probableRootCause.orEmpty()
        val operatorAction = record.advisory.recommendedOperatorAction.orEmpty()
        val standard = record.advisory.relevantStandard.orEmpty()
        val policyDisposition = record.officialDisposition.name

        if (record.defects.isEmpty()) {
            val row = listOf(
                record.inspectionId,
                isoTimestamp,
                record.boardName,
                record.officialDisposition.name,
                record.totalDefects.toString(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                policyDisposition,
                standard,
                rootCause,
                operatorAction,
                record.isDemonstration.toString(),
                prepMs,
                yoloMs,
                decodeMs,
                policyMs,
                llmMs,
                totalMs,
                modelLatencyMs,
                completedAtUtc
            )
            sb.append(row.joinToString(",") { escapeRfc4180(it) }).append("\r\n")
        } else {
            for (defect in record.defects) {
                val bboxX = defect.boundingBox?.let { String.format(Locale.US, "%.4f", it.x) }.orEmpty()
                val bboxY = defect.boundingBox?.let { String.format(Locale.US, "%.4f", it.y) }.orEmpty()
                val bboxW = defect.boundingBox?.let { String.format(Locale.US, "%.4f", it.width) }.orEmpty()
                val bboxH = defect.boundingBox?.let { String.format(Locale.US, "%.4f", it.height) }.orEmpty()

                val confidenceValue = when (record.target) {
                    InspectionTarget.TRANSISTOR -> {
                        record.transistorAnomaly?.normalizedDisplayScore ?: defect.confidence
                    }
                    InspectionTarget.PCB_BOARD -> {
                        defect.confidence
                    }
                }
                val confidenceStr = String.format(Locale.US, "%.4f", confidenceValue)

                val row = listOf(
                    record.inspectionId,
                    isoTimestamp,
                    record.boardName,
                    record.officialDisposition.name,
                    record.totalDefects.toString(),
                    defect.defectId,
                    defect.canonicalClassName,
                    confidenceStr,
                    defect.severity.name,
                    bboxX,
                    bboxY,
                    bboxW,
                    bboxH,
                    policyDisposition,
                    standard,
                    rootCause,
                    operatorAction,
                    record.isDemonstration.toString(),
                    prepMs,
                    yoloMs,
                    decodeMs,
                    policyMs,
                    llmMs,
                    totalMs,
                    modelLatencyMs,
                    completedAtUtc
                )
                sb.append(row.joinToString(",") { escapeRfc4180(it) }).append("\r\n")
            }
        }
    }

    override fun suggestedFileNameForAll(): String = DEFAULT_AUDIT_FILENAME

    override fun suggestedFileNameForRecord(inspectionId: String): String {
        val sanitized = inspectionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "bharat_ai_${sanitized}.csv"
    }

    companion object {
        const val DEFAULT_AUDIT_FILENAME = "bharat_ai_inspection_logs.csv"

        const val CSV_HEADER_V1 =
            "inspection_id,timestamp,board_name,overall_disposition,total_defects," +
                "defect_id,defect_class,confidence,severity,bbox_x,bbox_y,bbox_width,bbox_height," +
                "policy_disposition,inspection_standard,root_cause,operator_action," +
                "is_demonstration,image_preprocessing_ms,yolo_inference_ms,output_decoding_nms_ms," +
                "policy_evaluation_ms,llm_advisory_ms,total_duration_ms"

        const val CSV_HEADER_V2 =
            "inspection_id,timestamp,board_name,overall_disposition,total_defects," +
                "defect_id,defect_class,confidence,severity,bbox_x,bbox_y,bbox_width,bbox_height," +
                "policy_disposition,inspection_standard,root_cause,operator_action," +
                "is_demonstration,image_preprocessing_ms,yolo_inference_ms,output_decoding_nms_ms," +
                "policy_evaluation_ms,llm_advisory_ms,total_duration_ms," +
                "model_inference_latency_ms,completed_at_utc"

        const val CSV_HEADER = CSV_HEADER_V2

        fun escapeRfc4180(value: String): String {
            val needsQuotes = value.contains(',') ||
                value.contains('"') ||
                value.contains('\n') ||
                value.contains('\r')
            return if (needsQuotes) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
        }

        fun formatIsoTimestamp(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(epochMs))
        }

        sealed interface CoordinateParseResult {
            data class ValidBox(val box: BoundingBox) : CoordinateParseResult
            data object ValidNull : CoordinateParseResult
            data object Invalid : CoordinateParseResult
        }

        fun parseCoordinates(xStr: String, yStr: String, wStr: String, hStr: String): CoordinateParseResult {
            val xTrim = xStr.trim()
            val yTrim = yStr.trim()
            val wTrim = wStr.trim()
            val hTrim = hStr.trim()

            val allBlank = xTrim.isEmpty() && yTrim.isEmpty() && wTrim.isEmpty() && hTrim.isEmpty()
            if (allBlank) {
                return CoordinateParseResult.ValidNull
            }
            if (xTrim.isEmpty() || yTrim.isEmpty() || wTrim.isEmpty() || hTrim.isEmpty()) {
                return CoordinateParseResult.Invalid
            }

            val x = xTrim.toFloatOrNull() ?: return CoordinateParseResult.Invalid
            val y = yTrim.toFloatOrNull() ?: return CoordinateParseResult.Invalid
            val w = wTrim.toFloatOrNull() ?: return CoordinateParseResult.Invalid
            val h = hTrim.toFloatOrNull() ?: return CoordinateParseResult.Invalid

            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) {
                return CoordinateParseResult.Invalid
            }

            return CoordinateParseResult.ValidBox(BoundingBox(x, y, w, h))
        }

        /**
         * Parses first-column inspection_id values cleanly from an RFC 4180 CSV document
         * supporting both CSV v1 (24 columns) and CSV v2 (26 columns).
         * Performs strict coordinate integrity validation (rejects partially empty or non-numeric coords).
         */
        fun extractInspectionIdsAndValidateIntegrity(csvText: String): Pair<Set<String>, Boolean> {
            if (csvText.isBlank()) return emptySet<String>() to false
            val rows = parseRfc4180Rows(csvText)
            if (rows.isEmpty()) return emptySet<String>() to false

            val headerJoined = rows.first().joinToString(",")
            val isV1 = headerJoined == CSV_HEADER_V1
            val isV2 = headerJoined == CSV_HEADER_V2
            if (!isV1 && !isV2) {
                return emptySet<String>() to false
            }

            val expectedCols = if (isV1) 24 else 26
            val ids = linkedSetOf<String>()
            for (i in 1 until rows.size) {
                val row = rows[i]
                if (row.size != expectedCols) {
                    // Partial / truncated row detected (e.g., crash during flush)
                    return ids to false
                }
                // Strict coordinate validation on columns 10-13 (row indices 9, 10, 11, 12)
                val coordResult = parseCoordinates(row[9], row[10], row[11], row[12])
                if (coordResult is CoordinateParseResult.Invalid) {
                    return ids to false
                }
                val id = row[0]
                if (id.isNotBlank()) ids.add(id)
            }
            return ids to true
        }

        fun parseRfc4180Rows(input: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            val currentRow = mutableListOf<String>()
            val currentField = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                if (inQuotes) {
                    if (ch == '"') {
                        if (i + 1 < input.length && input[i + 1] == '"') {
                            currentField.append('"')
                            i += 2
                            continue
                        } else {
                            inQuotes = false
                        }
                    } else {
                        currentField.append(ch)
                    }
                } else {
                    when (ch) {
                        '"' -> inQuotes = true
                        ',' -> {
                            currentRow.add(currentField.toString())
                            currentField.setLength(0)
                        }
                        '\r' -> {
                            if (i + 1 < input.length && input[i + 1] == '\n') i++
                            currentRow.add(currentField.toString())
                            currentField.setLength(0)
                            if (currentRow.any { it.isNotEmpty() }) {
                                rows.add(currentRow.toList())
                            }
                            currentRow.clear()
                        }
                        '\n' -> {
                            currentRow.add(currentField.toString())
                            currentField.setLength(0)
                            if (currentRow.any { it.isNotEmpty() }) {
                                rows.add(currentRow.toList())
                            }
                            currentRow.clear()
                        }
                        else -> currentField.append(ch)
                    }
                }
                i++
            }
            if (inQuotes) {
                // Unterminated quote -> truncated row
                currentRow.add(currentField.toString())
                rows.add(currentRow.toList())
            } else if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
                currentRow.add(currentField.toString())
                rows.add(currentRow.toList())
            }
            return rows
        }

        data class ParsedCsvRow(
            val inspectionId: String,
            val timestampUtc: String,
            val boardName: String,
            val overallDisposition: String,
            val totalDefects: Int,
            val defectId: String?,
            val defectClass: String?,
            val confidence: Float?,
            val severity: String?,
            val boundingBox: BoundingBox?,
            val policyDisposition: String,
            val inspectionStandard: String?,
            val rootCause: String?,
            val operatorAction: String?,
            val isDemonstration: Boolean,
            val imagePreprocessingMs: Long?,
            val yoloInferenceMs: Long?,
            val outputDecodingNmsMs: Long?,
            val policyEvaluationMs: Long?,
            val llmAdvisoryMs: Long?,
            val totalDurationMs: Long?,
            val modelInferenceLatencyMs: Long?,
            val completedAtUtc: String?
        )

        fun parseCsvToRows(csvText: String): List<ParsedCsvRow>? {
            if (csvText.isBlank()) return null
            val rows = parseRfc4180Rows(csvText)
            if (rows.isEmpty()) return null

            val headerJoined = rows.first().joinToString(",")
            val isV1 = headerJoined == CSV_HEADER_V1
            val isV2 = headerJoined == CSV_HEADER_V2
            if (!isV1 && !isV2) return null

            val expectedCols = if (isV1) 24 else 26
            val result = mutableListOf<ParsedCsvRow>()

            for (i in 1 until rows.size) {
                val row = rows[i]
                if (row.size != expectedCols) return null

                // Strict coordinate parsing: 4 empty -> valid null; 4 valid numbers -> valid box; otherwise reject row
                val coordResult = parseCoordinates(row[9], row[10], row[11], row[12])
                val bbox = when (coordResult) {
                    is CoordinateParseResult.ValidBox -> coordResult.box
                    is CoordinateParseResult.ValidNull -> null
                    is CoordinateParseResult.Invalid -> return null
                }

                val modelLatency = if (isV2) row[24].toLongOrNull() else null
                val completedAt = if (isV2) row[25].ifBlank { null } else null

                result.add(
                    ParsedCsvRow(
                        inspectionId = row[0],
                        timestampUtc = row[1],
                        boardName = row[2],
                        overallDisposition = row[3],
                        totalDefects = row[4].toIntOrNull() ?: 0,
                        defectId = row[5].ifBlank { null },
                        defectClass = row[6].ifBlank { null },
                        confidence = row[7].toFloatOrNull(),
                        severity = row[8].ifBlank { null },
                        boundingBox = bbox,
                        policyDisposition = row[13],
                        inspectionStandard = row[14].ifBlank { null },
                        rootCause = row[15].ifBlank { null },
                        operatorAction = row[16].ifBlank { null },
                        isDemonstration = row[17].toBooleanStrictOrNull() ?: false,
                        imagePreprocessingMs = row[18].toLongOrNull(),
                        yoloInferenceMs = row[19].toLongOrNull(),
                        outputDecodingNmsMs = row[20].toLongOrNull(),
                        policyEvaluationMs = row[21].toLongOrNull(),
                        llmAdvisoryMs = row[22].toLongOrNull(),
                        totalDurationMs = row[23].toLongOrNull(),
                        modelInferenceLatencyMs = modelLatency,
                        completedAtUtc = completedAt
                    )
                )
            }
            return result
        }
    }
}
