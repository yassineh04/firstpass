package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.prompt.Prompt

/** What the review needs from a model provider. The llm adapter implements it; core never sees HTTP or JSON. */
fun interface LlmPort {
    fun review(request: LlmRequest): LlmResult
}

data class LlmRequest(
    val prompt: Prompt,
    val maxOutputTokens: Int,
)

/** Why a provider could not give a usable answer. */
enum class LlmFailure {
    /** HTTP 429 or a daily quota: another provider may still answer. */
    RATE_LIMITED,

    /** Network error, timeout or HTTP 5xx after the retries. */
    UNAVAILABLE,

    /** The answer did not match the output schema, even after one repair attempt. */
    INVALID_OUTPUT,

    /** The provider refused the request (HTTP 4xx other than 429): a configuration problem. */
    REJECTED,
}

sealed interface LlmResult {
    data class Success(
        val review: ModelReview,
        val provider: String,
        val model: String,
        val inputTokens: Int,
        val outputTokens: Int,
    ) : LlmResult

    /** [detail] is safe to log: it never contains the prompt, the code or a key. */
    data class Failure(
        val reason: LlmFailure,
        val provider: String,
        val detail: String,
    ) : LlmResult
}
