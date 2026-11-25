package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType
import com.example.domain.policy.DefectTaxonomy

data class OnnxContractValidationResult(
    val inputWidth: Int,
    val inputHeight: Int,
    val classLabels: List<String>,
    val hasEmbeddedClassMetadata: Boolean,
    val contractVersion: String
)

/**
 * Validates that a loaded ONNX model strictly satisfies the Phase A supervised YOLO 640 FP32
 * contract (float32 [1,3,640,640] -> float32 [1,15,8400], exact 11-class PCB taxonomy).
 */
object OnnxContractValidator {
    const val EXPECTED_INPUT_SIZE = 640
    const val EXPECTED_INPUT_CHANNELS = 3L
    const val EXPECTED_OUTPUT_CHANNELS = 15L
    const val EXPECTED_OUTPUT_ANCHORS = 8400L

    fun validateInputSignature(
        inputName: String,
        inputType: OnnxJavaType,
        inputShape: LongArray
    ) {
        require(inputType == OnnxJavaType.FLOAT) {
            "Unsupported input '$inputName' dtype $inputType; expected FLOAT (FP32) for YOLO 640."
        }
        require(inputShape.size == 4) {
            "Unsupported input '$inputName' rank ${inputShape.size} (${inputShape.contentToString()}); expected 4D [1, 3, 640, 640]."
        }
        require(inputShape[0] == 1L || inputShape[0] == -1L) {
            "Incompatible input batch dimension ${inputShape[0]} in ${inputShape.contentToString()}; expected 1 or dynamic (-1)."
        }
        require(inputShape[1] == EXPECTED_INPUT_CHANNELS) {
            "Incompatible input channels ${inputShape[1]} in ${inputShape.contentToString()}; expected exact 3 RGB channels."
        }
        require(inputShape[2] == EXPECTED_INPUT_SIZE.toLong()) {
            "Incompatible input height ${inputShape[2]} in ${inputShape.contentToString()}; expected exact $EXPECTED_INPUT_SIZE."
        }
        require(inputShape[3] == EXPECTED_INPUT_SIZE.toLong()) {
            "Incompatible input width ${inputShape[3]} in ${inputShape.contentToString()}; expected exact $EXPECTED_INPUT_SIZE."
        }
    }

    fun validateResolvedContract(
        outputName: String,
        outputType: OnnxJavaType,
        resolvedOutputShape: LongArray,
        customMetadata: Map<String, String>
    ): OnnxContractValidationResult {
        require(outputType == OnnxJavaType.FLOAT) {
            "Unsupported output '$outputName' dtype $outputType; expected FLOAT (FP32)."
        }
        require(
            resolvedOutputShape.size == 3 &&
                resolvedOutputShape[0] == 1L &&
                resolvedOutputShape[1] == EXPECTED_OUTPUT_CHANNELS &&
                resolvedOutputShape[2] == EXPECTED_OUTPUT_ANCHORS
        ) {
            "Incompatible output '$outputName' shape ${resolvedOutputShape.contentToString()}; expected [1, $EXPECTED_OUTPUT_CHANNELS, $EXPECTED_OUTPUT_ANCHORS]."
        }

        val rawNames = customMetadata["names"]
        val (classLabels, hasEmbeddedMetadata) = if (!rawNames.isNullOrBlank()) {
            val parsed = parseMetadataClassNames(rawNames)
                ?: throw IllegalArgumentException("Model 'names' metadata is malformed and cannot be verified.")
            validateClassTaxonomyOrder(parsed)
            parsed to true
        } else {
            DefectTaxonomy.expectedDatasetLabelsByIndex to false
        }

        return OnnxContractValidationResult(
            inputWidth = EXPECTED_INPUT_SIZE,
            inputHeight = EXPECTED_INPUT_SIZE,
            classLabels = classLabels,
            hasEmbeddedClassMetadata = hasEmbeddedMetadata,
            contractVersion = "yolo640-fp32-c${classLabels.size}"
        )
    }

    fun parseMetadataClassNames(rawNames: String): List<String>? {
        val trimmed = rawNames.trim()
        if (trimmed.isEmpty()) return null

        // Format 1: Python/JSON dict "{0: 'open', 1: 'short', ...}" or "{\"0\": \"open\", ...}"
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return null
            val dictEntryPattern = """\s*(?:(\d+)|'(\d+)'|"(\d+)")\s*:\s*(?:'([^']*)'|"([^"]*)")\s*"""
            val wholeDictRegex = Regex("""^$dictEntryPattern(?:,$dictEntryPattern)*,?$""")
            if (!wholeDictRegex.matches(inner)) return null

            val entryRegex = Regex(dictEntryPattern)
            val seenIndices = HashSet<Int>()
            val indexed = mutableListOf<Pair<Int, String>>()
            for (match in entryRegex.findAll(inner)) {
                val idxStr = match.groupValues[1].ifEmpty {
                    match.groupValues[2].ifEmpty { match.groupValues[3] }
                }
                val idx = idxStr.toIntOrNull() ?: return null
                val label = match.groupValues[4].ifEmpty { match.groupValues[5] }.trim()
                if (label.isEmpty()) return null
                if (!seenIndices.add(idx)) return null
                indexed.add(idx to label)
            }
            if (indexed.isEmpty()) return null
            indexed.sortBy { it.first }
            val expectedIndices = (0 until indexed.size).toList()
            if (indexed.map { it.first } != expectedIndices) return null
            return indexed.map { it.second }
        }

        // Format 2: JSON/Python array "['open', 'short', ...]" or "[\"open\", \"short\", ...]"
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.isEmpty()) return null
            val arrayItemPattern = """\s*(?:'([^']*)'|"([^"]*)")\s*"""
            val wholeArrayRegex = Regex("""^$arrayItemPattern(?:,$arrayItemPattern)*,?$""")
            if (!wholeArrayRegex.matches(inner)) return null

            val itemRegex = Regex(arrayItemPattern)
            val items = mutableListOf<String>()
            for (match in itemRegex.findAll(inner)) {
                val label = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
                if (label.isEmpty()) return null
                items.add(label)
            }
            if (items.isNotEmpty()) return items
        }

        return null
    }

    fun validateClassTaxonomyOrder(labels: List<String>) {
        val expected = DefectTaxonomy.expectedDatasetLabelsByIndex
        require(labels.size == expected.size) {
            "Incompatible class count ${labels.size}; expected ${expected.size} PCB defect classes."
        }
        labels.forEachIndexed { idx, rawLabel ->
            val resolved = DefectTaxonomy.resolve(rawLabel)
            val expectedKey = expected[idx]
            require(resolved.isKnownClass && resolved.normalizedKey == expectedKey) {
                "Class index $idx label '$rawLabel' does not match expected taxonomy class '$expectedKey'."
            }
        }
    }
}
