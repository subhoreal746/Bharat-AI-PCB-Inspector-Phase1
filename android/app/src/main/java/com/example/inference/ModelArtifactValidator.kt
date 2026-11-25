package com.example.inference

import com.example.inference.cv.ModelOperationResult
import java.io.File

/**
 * Validates model candidate artifacts before promotion to active runtime storage.
 * Distinguishes basic file-system integrity checks from model-specific size contracts
 * (e.g. Llama 3.2 3B ExecuTorch .pte vs SmolLM2 360M .pte).
 */
object ModelArtifactValidator {

    const val LLAMA_3_2_3B_EXPECTED_SIZE = 2_379_319_808L
    const val LLAMA_3_2_3B_EXPECTED_SHA256 = "8369298bf081eeed73cf23ac9ec9113f508ca672cf11ba4cb876104f751298cb"
    const val MIN_PTE_FILE_SIZE = 1024L

    /**
     * Determines whether a candidate artifact represents the Llama 3.2 3B Instruct ExecuTorch model
     * based on display name or size signature.
     */
    fun isLlama3_2_3B(displayName: String?, declaredSize: Long?, actualSize: Long?): Boolean {
        val name = displayName?.lowercase().orEmpty()
        if (name.contains("llama") || (name.contains("3.2") && name.contains("3b"))) return true
        if (declaredSize == LLAMA_3_2_3B_EXPECTED_SIZE || actualSize == LLAMA_3_2_3B_EXPECTED_SIZE) return true
        return false
    }

    /**
     * Validates a staged .pte candidate file.
     *
     * @param file The staged candidate file on disk.
     * @param displayName The resolved source file display name.
     * @param declaredSourceSize Size in bytes reported by ContentResolver/OpenableColumns.SIZE (if available).
     * @param bytesCopied Actual number of bytes transferred during the stream copy.
     */
    fun validateStagedPte(
        file: File,
        displayName: String?,
        declaredSourceSize: Long? = null,
        bytesCopied: Long? = null
    ): ModelOperationResult {
        if (!file.exists() || !file.canRead()) {
            return ModelOperationResult(false, "Staged model candidate is missing or unreadable.")
        }

        val actualSize = file.length()
        if (actualSize < MIN_PTE_FILE_SIZE) {
            return ModelOperationResult(
                false,
                "Staged model file is too small to be a valid ExecuTorch .pte file ($actualSize bytes; minimum is $MIN_PTE_FILE_SIZE bytes)."
            )
        }

        // Verify that bytes copied matches actual disk length
        if (bytesCopied != null && bytesCopied != actualSize) {
            return ModelOperationResult(
                false,
                "Model copy mismatch: stream wrote $bytesCopied bytes, but staged file has $actualSize bytes on disk."
            )
        }

        // Verify against declared ContentResolver source size if provided
        if (declaredSourceSize != null && declaredSourceSize > 0L) {
            if (actualSize != declaredSourceSize) {
                return ModelOperationResult(
                    false,
                    "Incomplete model copy: source declared $declaredSourceSize bytes, but staged file has $actualSize bytes ($bytesCopied bytes copied)."
                )
            }
        }

        // Model-specific contract: If identified as Llama 3.2 3B, enforce minimum full model size
        if (isLlama3_2_3B(displayName, declaredSourceSize, actualSize)) {
            if (actualSize < LLAMA_3_2_3B_EXPECTED_SIZE) {
                val sizeMib = actualSize / (1024 * 1024)
                val expectedMib = LLAMA_3_2_3B_EXPECTED_SIZE / (1024 * 1024)
                return ModelOperationResult(
                    false,
                    "Llama 3.2 3B model is truncated: expected $LLAMA_3_2_3B_EXPECTED_SIZE bytes (~$expectedMib MiB), got $actualSize bytes (~$sizeMib MiB). Please re-transfer the full model file."
                )
            }
        }

        return ModelOperationResult(
            true,
            "Model artifact pre-promotion validation passed ($actualSize bytes). Note: Basic file checks do not guarantee internal PTE graph validity."
        )
    }
}
