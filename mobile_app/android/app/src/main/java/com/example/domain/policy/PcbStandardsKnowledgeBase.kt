package com.example.domain.policy

import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionTarget

/**
 * Authoritative standards-reference layer for PCB and component inspection.
 *
 * Mappings are strictly audited and classified into three verification tiers:
 * 1. AUTHORITATIVELY_VERIFIED: Full standard and clause verified against official specifications.
 * 2. STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED: Standard domain is identified, but specific clause/section
 *    number is not verified in this application knowledge base. Clause-level assertions are removed.
 * 3. NO_RELIABLE_MAPPING: No authoritative standard identified or applicable.
 *
 * Distinguishes bare-board fabrication (IPC-6012), bare-board visual acceptance (IPC-A-600),
 * printed board assembly acceptance (IPC-A-610), electrical continuity testing (IPC-9252),
 * and general visual surface anomaly screening.
 *
 * No copyrighted standard text or fictitious clause numbers are included.
 */
object PcbStandardsKnowledgeBase {

    const val FALLBACK_UNVERIFIED_STANDARD =
        "Specific standard or clause not verified. Refer to the applicable PCB fabrication or assembly acceptance specification."

    enum class VerificationStatus {
        AUTHORITATIVELY_VERIFIED,
        STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
        NO_RELIABLE_MAPPING
    }

    data class StandardAuditEntry(
        val defectKey: String,
        val standardDomain: String,
        val standardName: String,
        val status: VerificationStatus,
        val guidance: String
    ) {
        fun formatExcerpt(): String = when (status) {
            VerificationStatus.AUTHORITATIVELY_VERIFIED ->
                "$standardName ($standardDomain): $guidance"
            VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED ->
                "Standard identified: $standardName ($standardDomain). $FALLBACK_UNVERIFIED_STANDARD"
            VerificationStatus.NO_RELIABLE_MAPPING ->
                FALLBACK_UNVERIFIED_STANDARD
        }
    }

    private val BARE_BOARD_STANDARDS: Map<String, StandardAuditEntry> = mapOf(
        "open" to StandardAuditEntry(
            defectKey = "open",
            standardDomain = "Bare-Board Conductor Integrity & Continuity",
            standardName = "IPC-6012 / IPC-9252",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Conductor discontinuity requires electrical net continuity verification."
        ),
        "short" to StandardAuditEntry(
            defectKey = "short",
            standardDomain = "Conductor Clearance & Bridging",
            standardName = "IPC-6012 (Bare Board) / IPC-A-610 (Assembly Solder Bridging)",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Conductive bridging violates minimum electrical spacing; electrical isolation testing required."
        ),
        "mouse_bite" to StandardAuditEntry(
            defectKey = "mouse_bite",
            standardDomain = "Bare-Board Conductor Edge Imperfections",
            standardName = "IPC-6012 / IPC-A-600",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Edge nicks must not reduce conductor width or clearance below design tolerance."
        ),
        "spur" to StandardAuditEntry(
            defectKey = "spur",
            standardDomain = "Bare-Board Conductor Spacing & Protrusions",
            standardName = "IPC-6012 / IPC-A-600",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Conductor protrusions must not violate minimum electrical clearance."
        ),
        "spurious_copper" to StandardAuditEntry(
            defectKey = "spurious_copper",
            standardDomain = "Bare-Board Residual Copper Isolation",
            standardName = "IPC-6012 / IPC-A-600",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Residual isolated copper islands must maintain minimum dielectric spacing."
        ),
        "missing_copper" to StandardAuditEntry(
            defectKey = "missing_copper",
            standardDomain = "Bare-Board Land and Pattern Completeness",
            standardName = "IPC-6012 / IPC-A-600",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Missing circuit lands or conductor patterns represent fabrication non-conformance."
        ),
        "pin_hole" to StandardAuditEntry(
            defectKey = "pin_hole",
            standardDomain = "Bare-Board Conductor & Land Voids",
            standardName = "IPC-6012 / IPC-A-600",
            status = VerificationStatus.STANDARD_IDENTIFIED_CLAUSE_NOT_VERIFIED,
            guidance = "Pinhole voids within conductor lands must not exceed allowable reduction limits."
        ),
        "copper_burr" to StandardAuditEntry(
            defectKey = "copper_burr",
            standardDomain = "Unclassified Mechanical Screening",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No verified standard mapping available."
        ),
        "hole_breakout" to StandardAuditEntry(
            defectKey = "hole_breakout",
            standardDomain = "Unclassified Annular Ring Screening",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No verified standard mapping available."
        ),
        "scratch" to StandardAuditEntry(
            defectKey = "scratch",
            standardDomain = "Unclassified Surface Screening",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No verified standard mapping available."
        ),
        "copper" to StandardAuditEntry(
            defectKey = "copper",
            standardDomain = "Unverified Dataset Class",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No verified standard mapping available."
        )
    )

    private val ANOMALY_STANDARDS: Map<String, StandardAuditEntry> = mapOf(
        "transistor_anomaly" to StandardAuditEntry(
            defectKey = "transistor_anomaly",
            standardDomain = "PCB Surface Visual Anomaly Screening",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No component-specific or semiconductor standard verified for generic visual anomalies."
        ),
        "anomaly" to StandardAuditEntry(
            defectKey = "anomaly",
            standardDomain = "PCB Surface Visual Anomaly Screening",
            standardName = "None",
            status = VerificationStatus.NO_RELIABLE_MAPPING,
            guidance = "No component-specific or semiconductor standard verified for generic visual anomalies."
        )
    )

    /**
     * Resolves audited reference excerpts for the given detected defects and inspection target.
     * When a defect has no verified standard clause, the mandatory fallback clause is returned.
     */
    fun getVerifiedReferences(
        defects: List<DetectedDefect>,
        target: InspectionTarget
    ): List<String> {
        if (defects.isEmpty()) {
            return listOf("Standard identified: IPC-6012 (Bare-Board Conformance Baseline). $FALLBACK_UNVERIFIED_STANDARD")
        }

        val standardsMap = when (target) {
            InspectionTarget.PCB_BOARD -> BARE_BOARD_STANDARDS
            InspectionTarget.TRANSISTOR -> ANOMALY_STANDARDS
        }

        val distinctKeys = defects.map { it.canonicalClassName.lowercase().trim() }.distinct()
        val results = mutableListOf<String>()

        for (key in distinctKeys) {
            val entry = DefectTaxonomy.resolve(key)
            val audit = standardsMap[entry.normalizedKey]
                ?: standardsMap[DefectTaxonomy.normalizeKey(key)]
                ?: standardsMap[key]

            if (audit != null) {
                results.add(audit.formatExcerpt())
            } else {
                val label = entry.canonicalLabel
                results.add("Defect '$label': $FALLBACK_UNVERIFIED_STANDARD")
            }
        }

        return if (results.isNotEmpty()) results else listOf(FALLBACK_UNVERIFIED_STANDARD)
    }

    /**
     * Retrieves the authoritative audit entry for a defect class.
     */
    fun getAuditEntry(rawOrCanonicalKey: String, target: InspectionTarget): StandardAuditEntry {
        val entry = DefectTaxonomy.resolve(rawOrCanonicalKey)
        val standardsMap = when (target) {
            InspectionTarget.PCB_BOARD -> BARE_BOARD_STANDARDS
            InspectionTarget.TRANSISTOR -> ANOMALY_STANDARDS
        }
        return standardsMap[entry.normalizedKey]
            ?: standardsMap[DefectTaxonomy.normalizeKey(rawOrCanonicalKey)]
            ?: standardsMap[rawOrCanonicalKey.lowercase().trim()]
            ?: StandardAuditEntry(
                defectKey = entry.normalizedKey,
                standardDomain = "Unclassified",
                standardName = "None",
                status = VerificationStatus.NO_RELIABLE_MAPPING,
                guidance = "No reliable mapping available."
            )
    }

    /**
     * Checks if a defect has an identified standard in the knowledge base.
     */
    fun hasIdentifiedStandard(rawOrCanonicalKey: String, target: InspectionTarget): Boolean {
        val audit = getAuditEntry(rawOrCanonicalKey, target)
        return audit.status != VerificationStatus.NO_RELIABLE_MAPPING
    }
}
