package com.example.inference.llm

import com.example.domain.model.BoundingBox
import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.policy.DefectTaxonomy
import com.example.domain.policy.PcbStandardsKnowledgeBase
import java.util.Locale

object AdvisoryPromptBuilder {

    const val PROMPT_VERSION = "pcb-advisory-json-v2"
    const val ZERO_DEFECT_ROOT_CAUSE = "No localized defects detected; no defect-specific root cause can be established."
    const val UNVERIFIED_STANDARD = PcbStandardsKnowledgeBase.FALLBACK_UNVERIFIED_STANDARD

    data class DefectSummaryGroup(
        val canonicalName: String,
        val defectCode: String?,
        val severity: DefectSeverity,
        val count: Int,
        val avgConfidencePct: Float,
        val boxesSummary: String
    )

    fun buildGroundedPrompt(
        request: AdvisoryRequest,
        isLlama: Boolean = false
    ): String {
        val hasZeroDefects = request.defects.isEmpty()

        val defectGroups = groupDefects(request.defects)
        val defectSummary = if (hasZeroDefects) {
            "None (0 localized detections)."
        } else {
            defectGroups.joinToString("\n") { g ->
                val codeStr = if (g.defectCode != null) "code=${g.defectCode}, " else ""
                val boxStr = if (g.boxesSummary.isNotBlank()) ", boxes=[${g.boxesSummary}]" else ""
                "- Defect: ${g.canonicalName} (${codeStr}severity=${g.severity.name}, count=${g.count}, avgConfidence=${String.format(Locale.US, "%.1f%%", g.avgConfidencePct)}$boxStr)"
            }
        }

        val referenceContext = if (request.verifiedReferenceExcerpts.isEmpty()) {
            UNVERIFIED_STANDARD
        } else {
            request.verifiedReferenceExcerpts.joinToString("\n")
        }

        val zeroDefectConstraint = if (hasZeroDefects) {
            "\nCRITICAL: 0 defects were detected. \"probable_root_cause\" MUST be EXACTLY: \"$ZERO_DEFECT_ROOT_CAUSE\". Never hypothesize or invent defect causes for clean boards."
        } else {
            ""
        }

        val cleanRationale = request.policyRationale
            .replace("Transistor Anomaly", "Anomaly", ignoreCase = true)
            .replace("transistor_anomaly", "Anomaly", ignoreCase = true)

        val systemPrompt = """
You are an offline PCB quality engineering assistant. Output EXACTLY ONE JSON object with exact keys: "probable_root_cause", "recommended_operator_action", "relevant_standard", "limitations".
Rules:
1. Output ONLY the raw JSON object starting immediately with '{' and ending with '}'. No Markdown code fences, no preamble, and no trailing text.
2. Ground all fields strictly in the Localized Detections and Reference Context.$zeroDefectConstraint
3. "probable_root_cause": 1-2 concise technical sentences formulating a plausible root-cause hypothesis grounded strictly in the detected anomaly and visible evidence. Do NOT hypothesize or assume unverified component categories, unverified board assembly steps, or unverified fabrication stages without explicit evidence. Present root cause as a hypothesis, not a confirmed fact. For generic "Anomaly" detections, formulate a generic visual pattern or surface irregularity hypothesis.
4. "recommended_operator_action": 1-3 concise inspection or verification steps appropriate for the detected defect. For generic "Anomaly" detections, instruct the operator to perform localized optical magnification inspection of the anomalous region and verify pattern/surface integrity, without assuming an unverified component category.
5. "relevant_standard": Use verified standard reference from Reference Context if available. Do NOT select or cite unverified semiconductor-package standards for general PCB anomalies. If no verified standard or clause is provided, output EXACTLY: "$UNVERIFIED_STANDARD". Never fabricate standard identifiers or clause numbers.
6. "limitations": 1-3 concise sentences stating visual inspection limitations: 2D optical analysis cannot confirm electrical continuity/isolation, cannot inspect internal layers, and requires physical DMM/ICT verification.
7. Terminology & Evidence Constraints:
   - For generic anomalies, use the term "Anomaly" only. Never use component-specific terms or unverified component areas.
   - Never infer a component type, semiconductor package, or unverified assembly process without explicit evidence.
8. Never alter the official policy disposition (${request.officialDisposition.name}). Never claim official certification.

JSON Schema:
{
  "probable_root_cause": "<1-2 concise technical sentences formulating an evidence-grounded hypothesis>",
  "recommended_operator_action": "<1-3 concise inspection or verification steps for the anomalous area>",
  "relevant_standard": "<verified standard or '$UNVERIFIED_STANDARD'>",
  "limitations": "<1-3 concise statements on optical limits>"
}
""".trimIndent()

        val userPrompt = """
Board Name: ${request.boardName}
Image Resolution: ${request.imageWidth}x${request.imageHeight} (lowClarityRisk=${request.lowClarityRisk})
Official Policy Disposition: ${request.officialDisposition.name}
Policy Rationale: $cleanRationale
Localized Detections:
$defectSummary
Reference Context:
$referenceContext
""".trimIndent()

        return if (isLlama) {
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$systemPrompt<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$userPrompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        } else {
            "<|im_start|>system\n$systemPrompt\n<|im_end|>\n<|im_start|>user\n$userPrompt\n<|im_end|>\n<|im_start|>assistant\n"
        }
    }

    private fun groupDefects(defects: List<DetectedDefect>): List<DefectSummaryGroup> {
        if (defects.isEmpty()) return emptyList()

        return defects.groupBy { it.canonicalClassName.lowercase().trim() }
            .map { (key, group) ->
                val first = group.first()
                val entry = DefectTaxonomy.resolve(key)
                val canonicalName = when {
                    key.contains("transistor", ignoreCase = true) || key.contains("anomaly", ignoreCase = true) -> "Anomaly"
                    entry.canonicalLabel.contains("transistor", ignoreCase = true) -> "Anomaly"
                    else -> entry.canonicalLabel
                }
                val code = when {
                    entry.code == "TR-ANOMALY-REVIEW" -> "ANOMALY-REVIEW"
                    entry.code?.startsWith("TR-") == true -> entry.code.replace("TR-", "PCB-")
                    else -> entry.code
                }
                val severity = entry.defaultSeverity
                val count = group.size
                val avgConf = (group.sumOf { it.confidence.toDouble() } / count).toFloat() * 100f
                val boxes = group.mapNotNull { it.boundingBox }
                    .take(3)
                    .joinToString("; ") { b ->
                        "x=${fmt(b.x)}, y=${fmt(b.y)}, w=${fmt(b.width)}, h=${fmt(b.height)}"
                    }
                DefectSummaryGroup(
                    canonicalName = canonicalName,
                    defectCode = code,
                    severity = severity,
                    count = count,
                    avgConfidencePct = avgConf,
                    boxesSummary = boxes
                )
            }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v)
}
