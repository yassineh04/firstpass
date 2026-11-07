package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult

/**
 * A provider for tests and local development: it returns scripted answers, without any network
 * call, through the same parser and schema checks as the real providers.
 */
class FakeLlmClient(
    private val answer: (LlmRequest) -> String = { EMPTY_REVIEW },
) : LlmPort {
    private val parser = ReviewOutputParser()

    override fun review(request: LlmRequest): LlmResult {
        val content = answer(request)
        return when (val parsed = parser.parse(content)) {
            is ParsedOutput.Valid -> {
                LlmResult.Success(
                    review = parsed.review,
                    provider = PROVIDER,
                    model = MODEL,
                    inputTokens =
                        TokenEstimator.estimate(request.prompt.system) + TokenEstimator.estimate(request.prompt.user),
                    outputTokens = TokenEstimator.estimate(content),
                )
            }

            is ParsedOutput.Invalid -> {
                LlmResult.Failure(LlmFailure.INVALID_OUTPUT, PROVIDER, parsed.errors.joinToString("; "))
            }
        }
    }

    companion object {
        const val PROVIDER = "fake"
        const val MODEL = "fake"
        const val EMPTY_REVIEW = """{"summary": "The fake provider found nothing to report.", "comments": []}"""
    }
}
