package com.example.inference.cv

import com.example.domain.model.DetectionOutcome
import com.example.domain.model.ModelInfo
import com.example.domain.model.PcbImageInput
import kotlinx.coroutines.flow.StateFlow

data class ModelOperationResult(
    val success: Boolean,
    val message: String
)

interface PcbDetectionEngine {
    val engineLabel: String
    val requiresBitmap: Boolean
    val isDemonstrationEngine: Boolean
    val modelInfo: StateFlow<ModelInfo>

    suspend fun initialize(): ModelOperationResult
    suspend fun detect(input: PcbImageInput): DetectionOutcome
    suspend fun unload()
    suspend fun close()
}
