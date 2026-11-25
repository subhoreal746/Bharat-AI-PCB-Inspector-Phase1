package com.example.inference.llm

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.AiAdvisory
import com.example.domain.model.InspectionDisposition
import com.example.domain.policy.PcbStandardsKnowledgeBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RawAdvisoryJson(
    val probable_root_cause: String? = null,
    val recommended_operator_action: String? = null,
    val relevant_standard: String? = null,
    val limitations: String? = null
)

object AdvisoryOutputParser {
    private const val MAX_FIELD_LEN = 1200
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Strictly validates whether a candidate JSON string contains all four required advisory fields
     * with non-blank, non-placeholder values and valid syntax.
     */
    fun validateAdvisoryFields(candidateJson: String): Boolean {
        val parsed = runCatching { json.decodeFromString<RawAdvisoryJson>(candidateJson) }.getOrNull()
            ?: return false

        val rc = parsed.probable_root_cause?.trim().orEmpty()
        val action = parsed.recommended_operator_action?.trim().orEmpty()
        val std = parsed.relevant_standard?.trim().orEmpty()
        val limits = parsed.limitations?.trim().orEmpty()

        if (rc.isBlank() || action.isBlank() || std.isBlank() || limits.isBlank()) {
            return false
        }

        val placeholders = listOf("<1", "<concise", "<verified", "<explicit", "placeholder", "todo")
        if (placeholders.any {
            rc.contains(it, ignoreCase = true) ||
            action.contains(it, ignoreCase = true) ||
            std.contains(it, ignoreCase = true) ||
            limits.contains(it, ignoreCase = true)
        }) {
            return false
        }

        return true
    }

    /**
     * Strictly parses and validates structured JSON from the on-device LLM.
     * Any invalid JSON, missing required fields, overly long text, or ungrounded
     * compliance/standard fabrication returns UNAVAILABLE.
     */
    fun parseAndValidate(
        rawText: String,
        officialDisposition: InspectionDisposition,
        hasVerifiedReferences: Boolean,
        isDemonstration: Boolean,
        latencyMs: Long?,
        provenanceSummary: String,
        hasZeroDefects: Boolean = (officialDisposition == InspectionDisposition.PASS)
    ): AiAdvisory {
        val candidateJson = extractJsonObject(rawText)
            ?: return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM output did not contain a valid JSON object.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )

        val parsed = runCatching { json.decodeFromString<RawAdvisoryJson>(candidateJson) }.getOrNull()
            ?: return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM output failed strict JSON schema validation.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )

        val rootCause = parsed.probable_root_cause?.trim().orEmpty()
        val action = parsed.recommended_operator_action?.trim().orEmpty()
        val rawStandard = parsed.relevant_standard?.trim().orEmpty()
        val limitations = parsed.limitations?.trim().orEmpty()

        if (rootCause.isBlank() || action.isBlank() || rawStandard.isBlank() || limitations.isBlank()) {
            return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM JSON omitted required advisory fields: all four fields (probable_root_cause, recommended_operator_action, relevant_standard, limitations) must be present and non-blank.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )
        }

        // Reject unpopulated template placeholders
        val placeholders = listOf("<1", "<concise", "<verified", "<explicit", "placeholder", "todo")
        if (placeholders.any {
                rootCause.contains(it, ignoreCase = true) ||
                action.contains(it, ignoreCase = true) ||
                rawStandard.contains(it, ignoreCase = true) ||
                limitations.contains(it, ignoreCase = true)
            }
        ) {
            return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM output contained unpopulated placeholder templates.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )
        }

        if (rootCause.length > MAX_FIELD_LEN ||
            action.length > MAX_FIELD_LEN ||
            rawStandard.length > 400 ||
            limitations.length > MAX_FIELD_LEN
        ) {
            return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM advisory exceeded maximum validated field length.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )
        }

        // Strict zero-defect validation: never allow model to hallucinate defect causes when 0 defects exist
        if (hasZeroDefects) {
            val lowerRc = rootCause.lowercase()
            val fabricatedDefectKeywords = listOf(
                "short circuit", "solder bridge", "copper spur", "mouse bite",
                "missing component", "pin hole", "open circuit"
            )
            if (fabricatedDefectKeywords.any { lowerRc.contains(it) }) {
                return unavailable(
                    isDemonstration = isDemonstration,
                    reason = "LLM fabricated defects on a clean board with zero localized detections.",
                    latencyMs = latencyMs,
                    provenanceSummary = provenanceSummary
                )
            }
        }

        // Guard against ungrounded standard citations or compliance claims
        val sanitizedStandard = if (!hasVerifiedReferences) {
            PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD
        } else {
            val lower = rawStandard.lowercase()
            if (rawStandard.isBlank() ||
                rawStandard.equals("None verified", ignoreCase = true) ||
                lower.contains("jedec") || lower.contains("jesd") || lower.contains("j-std-020")
            ) {
                PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD
            } else {
                rawStandard
            }
        }

        val lowerCombined = "$rootCause $action".lowercase()
        if (lowerCombined.contains("certified compliant") || lowerCombined.contains("official ipc certification")) {
            return unavailable(
                isDemonstration = isDemonstration,
                reason = "LLM output contained an ungrounded certification claim and was rejected.",
                latencyMs = latencyMs,
                provenanceSummary = provenanceSummary
            )
        }

        return AiAdvisory(
            availability = AdvisoryAvailability.AVAILABLE,
            isDemonstration = isDemonstration,
            severityClassification = officialDisposition.name,
            probableRootCause = rootCause,
            recommendedOperatorAction = action,
            relevantStandard = sanitizedStandard,
            suggestedDisposition = officialDisposition,
            limitations = limitations,
            unavailableReason = null,
            advisoryLatencyMs = latencyMs,
            provenanceSummary = provenanceSummary
        )
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        val cleaned = if (trimmed.startsWith("```json", ignoreCase = true)) {
            trimmed.removePrefix("```json").removePrefix("```JSON").removeSuffix("```").trim()
        } else if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed
        }
        val start = cleaned.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escape = false
        var end = -1

        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }

        if (end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    private fun unavailable(
        isDemonstration: Boolean,
        reason: String,
        latencyMs: Long?,
        provenanceSummary: String
    ): AiAdvisory = AiAdvisory(
        availability = AdvisoryAvailability.UNAVAILABLE,
        isDemonstration = isDemonstration,
        unavailableReason = reason,
        advisoryLatencyMs = latencyMs,
        provenanceSummary = provenanceSummary
    )
}
