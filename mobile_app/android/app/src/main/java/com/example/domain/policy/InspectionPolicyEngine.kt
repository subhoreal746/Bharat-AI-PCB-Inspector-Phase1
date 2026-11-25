package com.example.domain.policy

import com.example.domain.model.DefectSeverity
import com.example.domain.model.DetectedDefect
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.PolicyEvaluationResult

/**
 * Pure Kotlin deterministic screening policy engine.
 *
 * Enforces all mandatory safety invariants independently of CV confidence and LLM output.
 */
class InspectionPolicyEngine {

    fun evaluate(
        imageValidation: ImageValidationResult,
        detectionOutcome: DetectionOutcome,
        config: InspectionPolicyConfig
    ): PolicyEvaluationResult {
        val thresholdSummary = config.effectiveThresholdsSummary()

        // 1. Unprocessable image guard (corrupt, decode failure, or below minimum dimensions)
        if (!imageValidation.isProcessable || !imageValidation.isValid ||
            imageValidation.width < config.minImageWidth ||
            imageValidation.height < config.minImageHeight
        ) {
            val reason = imageValidation.errorMessage
                ?: "Image is unprocessable or below minimum dimensions (${imageValidation.width}x${imageValidation.height} < ${config.minImageWidth}x${config.minImageHeight})."
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.INCOMPLETE,
                rationale = "INCOMPLETE: $reason Inspection cannot return PASS without a valid optical frame.",
                evaluatedDefects = emptyList(),
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 2. Inference failure / unloaded production model guard
        if (detectionOutcome is DetectionOutcome.Failure) {
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.INCOMPLETE,
                rationale = "INCOMPLETE (${detectionOutcome.errorType.name}): ${detectionOutcome.errorMessage} Production or runtime failure can never yield PASS.",
                evaluatedDefects = emptyList(),
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        val success = detectionOutcome as DetectionOutcome.Success
        // 3. Resolve every defect strictly from normalized class name (never index, never confidence)
        val normalizedDefects = success.detections.map { rawDefect ->
            val taxonomy = DefectTaxonomy.resolve(rawDefect.rawClassName)
            rawDefect.copy(
                canonicalClassName = taxonomy.canonicalLabel,
                severity = taxonomy.defaultSeverity,
                requiresManualReview = taxonomy.requiresManualReview || !taxonomy.isKnownClass,
                code = rawDefect.code ?: taxonomy.code,
                description = rawDefect.description ?: taxonomy.description,
                boundingBox = rawDefect.boundingBox?.clamped()
            )
        }

        val totalCount = normalizedDefects.size
        val criticalCount = normalizedDefects.count { it.severity == DefectSeverity.CRITICAL }
        val mediumCount = normalizedDefects.count { it.severity == DefectSeverity.MEDIUM }
        val lowCount = normalizedDefects.count { it.severity == DefectSeverity.LOW }
        val manualReviewCount = normalizedDefects.count {
            it.severity == DefectSeverity.MANUAL_REVIEW ||
                it.severity == DefectSeverity.REQUIRES_DATASET_VERIFICATION ||
                it.requiresManualReview
        }
        val unverifiedCopperCount = normalizedDefects.count {
            it.severity == DefectSeverity.REQUIRES_DATASET_VERIFICATION
        }

        val clarityFlag = imageValidation.lowClarityRisk
        val classSummary = normalizedDefects
            .groupingBy { it.canonicalClassName }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key} (${it.value})" }

        // 4. Critical defects never yield PASS
        if (criticalCount > 0) {
            val disposition = if (config.rejectOnCritical) {
                InspectionDisposition.REJECT
            } else {
                InspectionDisposition.REVIEW
            }
            val clarityNote = if (clarityFlag) " Low image clarity risk was also detected." else ""
            val actionNote = if (config.rejectOnCritical) {
                "Auto-reject policy triggered by $criticalCount critical defect(s)."
            } else {
                "Critical auto-rejection is disabled; escalated to REVIEW for mandatory operator sign-off (never PASS)."
            }
            return PolicyEvaluationResult(
                officialDisposition = disposition,
                rationale = "${disposition.name}: Detected $totalCount total defect(s) [$classSummary], including $criticalCount critical defect(s). $actionNote$clarityNote",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 5. Undocumented / unknown / unverified dataset classes require manual review
        val undocumentedOrUnknownCount = normalizedDefects.count {
            it.severity == DefectSeverity.MANUAL_REVIEW ||
                it.severity == DefectSeverity.REQUIRES_DATASET_VERIFICATION
        }
        if (undocumentedOrUnknownCount > 0) {
            val copperNote = if (unverifiedCopperCount > 0) {
                " Includes $unverifiedCopperCount 'Copper' detection(s) requiring dataset verification."
            } else {
                ""
            }
            val clarityNote = if (clarityFlag) " Low image clarity risk also flagged." else ""
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.REVIEW,
                rationale = "REVIEW: Detected $totalCount total defect(s) [$classSummary] with $undocumentedOrUnknownCount undocumented, unknown, or dataset-unverified class(es) requiring manual review.$copperNote$clarityNote",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 6. Medium severity gating
        if (mediumCount > 0 && config.reviewOnMedium) {
            val clarityNote = if (clarityFlag) " Low image clarity risk also flagged." else ""
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.REVIEW,
                rationale = "REVIEW: Detected $totalCount total defect(s) [$classSummary], including $mediumCount medium-severity defect(s) requiring operator review.$clarityNote",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 7. Low severity gating
        if (lowCount > 0 && config.reviewOnLow) {
            val clarityNote = if (clarityFlag) " Low image clarity risk also flagged." else ""
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.REVIEW,
                rationale = "REVIEW: Detected $totalCount total defect(s) [$classSummary] with low-severity review enabled.$clarityNote",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 8. Poor image clarity gate (zero detections + poor clarity NEVER yields PASS)
        if (clarityFlag) {
            val gateNote = if (config.reviewOnLowClarityZeroDefects) {
                "Low image clarity review gate triggered."
            } else {
                "Low-clarity toggle is off, but policy safety invariant prevents PASS when zero detections occur on poor-clarity frames."
            }
            val countPhrase = if (totalCount == 0) {
                "0 localized defects"
            } else {
                "$totalCount tolerated defect(s) [$classSummary]"
            }
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.REVIEW,
                rationale = "REVIEW: Observed $countPhrase on a frame flagged with low clarity risk. $gateNote",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 9. Adequate image clarity with tolerated low/medium-off defects -> PASS with exact count!
        if (totalCount > 0) {
            return PolicyEvaluationResult(
                officialDisposition = InspectionDisposition.PASS,
                rationale = "PASS (${config.profileName}): Detected $totalCount localized defect(s) [$classSummary]; none exceeded active rejection or review thresholds at adequate image clarity (${imageValidation.width}x${imageValidation.height}). Screening profile result only; uncertified without verified standard acceptance criteria.",
                evaluatedDefects = normalizedDefects,
                policyProfile = config.profileName,
                policyVersion = config.policyVersion,
                configSnapshot = config,
                effectiveThresholdsSummary = thresholdSummary
            )
        }

        // 10. Adequate image clarity + zero localized defects -> PASS (screening profile)
        return PolicyEvaluationResult(
            officialDisposition = InspectionDisposition.PASS,
            rationale = "PASS (${config.profileName}): 0 defects localized across ${imageValidation.width}x${imageValidation.height} frame with adequate image clarity. Screening profile result only; uncertified without verified standard acceptance criteria.",
            evaluatedDefects = emptyList(),
            policyProfile = config.profileName,
            policyVersion = config.policyVersion,
            configSnapshot = config,
            effectiveThresholdsSummary = thresholdSummary
        )
    }
}
