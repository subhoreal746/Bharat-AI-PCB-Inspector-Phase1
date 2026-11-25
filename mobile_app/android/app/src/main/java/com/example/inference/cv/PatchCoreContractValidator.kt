package com.example.inference.cv

import ai.onnxruntime.OnnxJavaType

data class PatchCoreContractValidationResult(
    val inputName: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val inputBatchSize: Long,
    val predScoreOutputName: String,
    val anomalyMapOutputName: String,
    val contractVersion: String
)

/**
 * Validates that a loaded ONNX model strictly satisfies the unsupervised PatchCore
 * anomaly detection contract:
 * - Input: FLOAT, shape exact [2, 3, 256, 256], name "input" (strict batch 2; dynamic batch -1 is rejected)
 * - Output pred_score: FLOAT, shape exact [2]
 * - Output anomaly_map: FLOAT, shape exact [2, 1, 256, 256]
 *
 * Resolves outputs by name, never assuming fixed positional ordering.
 * Ignores pred_label and pred_mask for official anomaly decisions.
 */
object PatchCoreContractValidator {
    const val EXPECTED_INPUT_NAME = "input"
    const val EXPECTED_INPUT_CHANNELS = 3L
    const val EXPECTED_INPUT_HEIGHT = 256
    const val EXPECTED_INPUT_WIDTH = 256
    const val EXPECTED_BATCH_SIZE = 2L

    const val OUTPUT_PRED_SCORE = "pred_score"
    const val OUTPUT_ANOMALY_MAP = "anomaly_map"
    const val OUTPUT_PRED_LABEL = "pred_label"
    const val OUTPUT_PRED_MASK = "pred_mask"

    const val CONTRACT_VERSION = "patchcore-256-fp32-v1"

    fun validateInputSignature(
        inputName: String,
        inputType: OnnxJavaType,
        inputShape: LongArray
    ) {
        require(inputName == EXPECTED_INPUT_NAME) {
            "Incompatible input name '$inputName'; expected '$EXPECTED_INPUT_NAME'."
        }
        require(inputType == OnnxJavaType.FLOAT) {
            "Unsupported input '$inputName' dtype $inputType; expected FLOAT (FP32)."
        }
        require(inputShape.size == 4) {
            "Unsupported input '$inputName' rank ${inputShape.size} (${inputShape.contentToString()}); expected 4D [2, 3, 256, 256]."
        }
        // Strict batch size 2: dynamic batch dimension (-1) is rejected
        require(inputShape[0] == EXPECTED_BATCH_SIZE) {
            "Incompatible input batch dimension ${inputShape[0]} in ${inputShape.contentToString()}; expected exact $EXPECTED_BATCH_SIZE (dynamic batch -1 is rejected)."
        }
        require(inputShape[1] == EXPECTED_INPUT_CHANNELS) {
            "Incompatible input channels ${inputShape[1]} in ${inputShape.contentToString()}; expected exact $EXPECTED_INPUT_CHANNELS RGB channels."
        }
        require(inputShape[2] == EXPECTED_INPUT_HEIGHT.toLong()) {
            "Incompatible input height ${inputShape[2]} in ${inputShape.contentToString()}; expected exact $EXPECTED_INPUT_HEIGHT."
        }
        require(inputShape[3] == EXPECTED_INPUT_WIDTH.toLong()) {
            "Incompatible input width ${inputShape[3]} in ${inputShape.contentToString()}; expected exact $EXPECTED_INPUT_WIDTH."
        }
    }

    /**
     * Resolves outputs by name from output info maps.
     * Throws IllegalArgumentException if required outputs are missing or incompatible.
     */
    fun validateOutputSignatures(
        outputTypes: Map<String, OnnxJavaType>,
        outputShapes: Map<String, LongArray>
    ): PatchCoreContractValidationResult {
        // Validate pred_score
        require(outputTypes.containsKey(OUTPUT_PRED_SCORE)) {
            "Missing required output '$OUTPUT_PRED_SCORE'. Available outputs: ${outputTypes.keys}."
        }
        val scoreType = outputTypes.getValue(OUTPUT_PRED_SCORE)
        require(scoreType == OnnxJavaType.FLOAT) {
            "Unsupported output '$OUTPUT_PRED_SCORE' dtype $scoreType; expected FLOAT."
        }
        val scoreShape = outputShapes.getValue(OUTPUT_PRED_SCORE)
        require(scoreShape.size == 1 && scoreShape[0] == EXPECTED_BATCH_SIZE) {
            "Incompatible output '$OUTPUT_PRED_SCORE' shape ${scoreShape.contentToString()}; expected exact 1D [$EXPECTED_BATCH_SIZE]."
        }

        // Validate anomaly_map
        require(outputTypes.containsKey(OUTPUT_ANOMALY_MAP)) {
            "Missing required output '$OUTPUT_ANOMALY_MAP'. Available outputs: ${outputTypes.keys}."
        }
        val mapType = outputTypes.getValue(OUTPUT_ANOMALY_MAP)
        require(mapType == OnnxJavaType.FLOAT) {
            "Unsupported output '$OUTPUT_ANOMALY_MAP' dtype $mapType; expected FLOAT."
        }
        val mapShape = outputShapes.getValue(OUTPUT_ANOMALY_MAP)
        require(
            mapShape.size == 4 &&
                mapShape[0] == EXPECTED_BATCH_SIZE &&
                mapShape[1] == 1L &&
                mapShape[2] == EXPECTED_INPUT_HEIGHT.toLong() &&
                mapShape[3] == EXPECTED_INPUT_WIDTH.toLong()
        ) {
            "Incompatible output '$OUTPUT_ANOMALY_MAP' shape ${mapShape.contentToString()}; expected exact 4D [$EXPECTED_BATCH_SIZE, 1, $EXPECTED_INPUT_HEIGHT, $EXPECTED_INPUT_WIDTH]."
        }

        return PatchCoreContractValidationResult(
            inputName = EXPECTED_INPUT_NAME,
            inputWidth = EXPECTED_INPUT_WIDTH,
            inputHeight = EXPECTED_INPUT_HEIGHT,
            inputBatchSize = EXPECTED_BATCH_SIZE,
            predScoreOutputName = OUTPUT_PRED_SCORE,
            anomalyMapOutputName = OUTPUT_ANOMALY_MAP,
            contractVersion = CONTRACT_VERSION
        )
    }
}
