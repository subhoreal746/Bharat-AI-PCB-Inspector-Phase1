package com.example.inference.llm

import com.example.domain.model.AiAdvisory
import com.example.domain.model.DetectedDefect
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.ModelInfo
import com.example.inference.cv.ModelOperationResult
import kotlinx.coroutines.flow.StateFlow

data class AdvisoryRequest(
    val inspectionId: String,
    val boardName: String,
    val officialDisposition: InspectionDisposition,
    val policyRationale: String,
    val defects: List<DetectedDefect>,
    val imageWidth: Int,
    val imageHeight: Int,
    val lowClarityRisk: Boolean,
    val isDemonstration: Boolean = false,
    val verifiedReferenceExcerpts: List<String> = emptyList()
)

interface AdvisoryEngine {
    val engineLabel: String
    val isDemonstrationEngine: Boolean
    val modelInfo: StateFlow<ModelInfo>

    suspend fun initialize(): ModelOperationResult
    suspend fun generateAdvisory(request: AdvisoryRequest): AiAdvisory
    suspend fun unload()
    suspend fun close()
}
