package com.example.domain.policy

import com.example.domain.model.DefectSeverity

data class TaxonomyEntry(
    val normalizedKey: String,
    val canonicalLabel: String,
    val defaultSeverity: DefectSeverity,
    val requiresManualReview: Boolean,
    val code: String?,
    val description: String,
    val isKnownClass: Boolean
)

object DefectTaxonomy {
    const val CLASS_MAPPING_VERSION = "pcb-11-v1"

    /**
     * Authoritative 11-class dataset label table.
     * Severity is ALWAYS resolved via normalized canonical class name, NEVER class index.
     * Classifications are provisional screening policies, not independently verified IPC standards.
     */
    val canonicalEntries: List<TaxonomyEntry> = listOf(
        TaxonomyEntry(
            normalizedKey = "open",
            canonicalLabel = "Open Circuit",
            defaultSeverity = DefectSeverity.CRITICAL,
            requiresManualReview = true,
            code = "PCB-OPEN",
            description = "Discontinuity or break in conductive copper trace (provisional critical screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "short",
            canonicalLabel = "Short Circuit",
            defaultSeverity = DefectSeverity.CRITICAL,
            requiresManualReview = true,
            code = "PCB-SHORT",
            description = "Unintended conductive bridge between adjacent traces or pads (provisional critical screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "mouse_bite",
            canonicalLabel = "Mouse Bite",
            defaultSeverity = DefectSeverity.MEDIUM,
            requiresManualReview = true,
            code = "PCB-MBITE",
            description = "Small indentations or notches along copper trace edge (provisional medium screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "spur",
            canonicalLabel = "Spur",
            defaultSeverity = DefectSeverity.MEDIUM,
            requiresManualReview = true,
            code = "PCB-SPUR",
            description = "Protruding copper spike extending from a trace or pad (provisional medium screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "spurious_copper",
            canonicalLabel = "Spurious Copper",
            defaultSeverity = DefectSeverity.MEDIUM,
            requiresManualReview = true,
            code = "PCB-SPURCU",
            description = "Residual isolated copper island left on substrate (provisional medium screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "missing_copper",
            canonicalLabel = "Missing Copper",
            defaultSeverity = DefectSeverity.CRITICAL,
            requiresManualReview = true,
            code = "PCB-MISSCU",
            description = "Absent pad, land, or trace copper conductor (provisional critical screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "copper_burr",
            canonicalLabel = "Copper Burr",
            defaultSeverity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            code = "PCB-CUBURR",
            description = "Undocumented severity in provisional screening; requires operator manual review.",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "pin_hole",
            canonicalLabel = "Pin Hole",
            defaultSeverity = DefectSeverity.MEDIUM,
            requiresManualReview = true,
            code = "PCB-PINHOLE",
            description = "Small void or perforation within copper pad or trace (provisional medium screening).",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "hole_breakout",
            canonicalLabel = "Hole Breakout",
            defaultSeverity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            code = "PCB-HBREAK",
            description = "Undocumented severity in provisional screening; requires operator manual review.",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "scratch",
            canonicalLabel = "Scratch",
            defaultSeverity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            code = "PCB-SCRATCH",
            description = "Undocumented severity in provisional screening; requires operator manual review.",
            isKnownClass = true
        ),
        TaxonomyEntry(
            normalizedKey = "copper",
            canonicalLabel = "Copper",
            defaultSeverity = DefectSeverity.REQUIRES_DATASET_VERIFICATION,
            requiresManualReview = true,
            code = "PCB-COPPER-UNVERIFIED",
            description = "Requires dataset verification: cannot assume defect or normal reference copper without dataset definitions.",
            isKnownClass = true
        )
    )

    /**
     * Authoritative PatchCore transistor anomaly screening label table.
     */
    val transistorEntries: List<TaxonomyEntry> = listOf(
        TaxonomyEntry(
            normalizedKey = "transistor_anomaly",
            canonicalLabel = "Transistor Anomaly",
            defaultSeverity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            code = "TR-ANOMALY-REVIEW",
            description = "PatchCore anomaly detection flag; requires operator manual review screening.",
            isKnownClass = true
        )
    )

    private val lookupByKey: Map<String, TaxonomyEntry> = buildMap {
        canonicalEntries.forEach { entry ->
            put(entry.normalizedKey, entry)
            put(normalizeKey(entry.canonicalLabel), entry)
        }
        transistorEntries.forEach { entry ->
            put(entry.normalizedKey, entry)
            put(normalizeKey(entry.canonicalLabel), entry)
        }
        // Known synonyms that map directly to the exact canonical class
        put("open_circuit", getValue("open"))
        put("short_circuit", getValue("short"))
    }

    fun normalizeKey(rawName: String): String {
        return rawName
            .trim()
            .lowercase()
            .replace(Regex("[\\s\\-]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    fun resolve(rawClassName: String): TaxonomyEntry {
        val normalized = normalizeKey(rawClassName)
        return lookupByKey[normalized] ?: TaxonomyEntry(
            normalizedKey = normalized.ifBlank { "unknown" },
            canonicalLabel = rawClassName.trim().ifBlank { "Unknown Class" },
            defaultSeverity = DefectSeverity.MANUAL_REVIEW,
            requiresManualReview = true,
            code = "PCB-UNKNOWN",
            description = "Unknown or unsupported class label; retained without remapping and escalated for manual review.",
            isKnownClass = false
        )
    }

    val expectedDatasetLabelsByIndex: List<String> = listOf(
        "open",
        "short",
        "mouse_bite",
        "spur",
        "spurious_copper",
        "missing_copper",
        "copper_burr",
        "pin_hole",
        "hole_breakout",
        "scratch",
        "copper"
    )
}
