package com.example.inference.llm

data class GenerationBudget(
    val promptTokens: Int,
    val newTokensBudget: Int,
    val totalSeqLen: Int,
    val isExecutable: Boolean,
    val rejectionReason: String? = null
)

/**
 * Calculates ExecuTorch 1.6.0 generation sequence budget.
 *
 * In ExecuTorch 1.6.0, `LlmModule.generate(prompt, seqLen, callback)` and `LlmGenerationConfig.seqLen`
 * define the TOTAL sequence limit (prompt tokens + new tokens).
 * The native engine resolves: `max_new_tokens = std::min(seq_len, max_context_len) - num_prompt_tokens`.
 * Therefore, to generate [maxNewTokens] new tokens, `totalSeqLen` must be set to `promptTokens + maxNewTokens`.
 */
object GenerationBudgeter {
    const val DEFAULT_MAX_CONTEXT_LEN = 2048
    const val DEFAULT_MAX_NEW_TOKENS = 180

    fun calculate(
        promptTokens: Int,
        maxContextLen: Int = DEFAULT_MAX_CONTEXT_LEN,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS
    ): GenerationBudget {
        if (promptTokens <= 0) {
            return GenerationBudget(
                promptTokens = promptTokens,
                newTokensBudget = 0,
                totalSeqLen = 0,
                isExecutable = false,
                rejectionReason = "Prompt contains zero tokens."
            )
        }

        if (promptTokens >= maxContextLen) {
            return GenerationBudget(
                promptTokens = promptTokens,
                newTokensBudget = 0,
                totalSeqLen = maxContextLen,
                isExecutable = false,
                rejectionReason = "Prompt length ($promptTokens tokens) reaches or exceeds maximum model context capacity ($maxContextLen tokens)."
            )
        }

        val availableTokens = maxContextLen - promptTokens
        val newTokensBudget = minOf(maxNewTokens, availableTokens)

        if (newTokensBudget <= 0) {
            return GenerationBudget(
                promptTokens = promptTokens,
                newTokensBudget = 0,
                totalSeqLen = promptTokens,
                isExecutable = false,
                rejectionReason = "Zero token generation budget available ($availableTokens tokens remaining)."
            )
        }

        val totalSeqLen = promptTokens + newTokensBudget
        return GenerationBudget(
            promptTokens = promptTokens,
            newTokensBudget = newTokensBudget,
            totalSeqLen = totalSeqLen,
            isExecutable = true,
            rejectionReason = null
        )
    }
}
