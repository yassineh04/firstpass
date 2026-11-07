package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.prompt.ReviewOutputSchema
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult

/**
 * A model provider that speaks the OpenAI Chat Completions format (Gemini, Groq, Ollama).
 *
 * Every answer is checked against the output schema. An invalid or cut-off answer gets one
 * repair attempt, with the validator errors; after that, the batch is reported as invalid output.
 */
class OpenAiCompatibleClient internal constructor(
    private val config: ProviderConfig,
    private val transport: ChatTransport,
) : LlmPort {
    constructor(config: ProviderConfig, retry: RetryPolicy = RetryPolicy()) : this(config, ChatTransport(config, retry))

    private val parser = ReviewOutputParser()

    override fun review(request: LlmRequest): LlmResult {
        val messages = initialMessages(request)
        return when (val first = send(messages, request.maxOutputTokens)) {
            is Exchange.Failed -> {
                LlmResult.Failure(first.reason, config.name, first.detail)
            }

            is Exchange.Answered -> {
                when (val evaluation = evaluate(first)) {
                    is Evaluation.Valid -> success(evaluation.review, listOf(first))
                    is Evaluation.Problem -> repair(messages, first, evaluation, request.maxOutputTokens)
                }
            }
        }
    }

    private fun repair(
        messages: List<Message>,
        first: Exchange.Answered,
        problem: Evaluation.Problem,
        maxOutputTokens: Int,
    ): LlmResult {
        val retryMessages =
            messages + Message("assistant", first.content.take(MAX_ECHOED_CHARS)) + Message("user", problem.instruction)
        return when (val second = send(retryMessages, maxOutputTokens)) {
            is Exchange.Failed -> {
                LlmResult.Failure(second.reason, config.name, second.detail)
            }

            is Exchange.Answered -> {
                when (val evaluation = evaluate(second)) {
                    is Evaluation.Valid -> {
                        success(evaluation.review, listOf(first, second))
                    }

                    is Evaluation.Problem -> {
                        LlmResult.Failure(
                            LlmFailure.INVALID_OUTPUT,
                            config.name,
                            "Still invalid after one repair: ${evaluation.detail}",
                        )
                    }
                }
            }
        }
    }

    private fun evaluate(answer: Exchange.Answered): Evaluation {
        if (answer.finishReason == "length") {
            return Evaluation.Problem(CUT_OFF_INSTRUCTION, "the answer was cut off at the output token limit")
        }
        return when (val parsed = parser.parse(answer.content)) {
            is ParsedOutput.Valid -> {
                Evaluation.Valid(parsed.review)
            }

            is ParsedOutput.Invalid -> {
                Evaluation.Problem(
                    instruction =
                        "Your previous answer does not match the required JSON schema:\n" +
                            parsed.errors.joinToString("\n") { "- $it" } +
                            "\nReturn only the corrected JSON object.",
                    detail = parsed.errors.joinToString("; "),
                )
            }
        }
    }

    private fun success(
        review: ModelReview,
        answers: List<Exchange.Answered>,
    ) = LlmResult.Success(
        review = review,
        provider = config.name,
        model = config.model,
        inputTokens = answers.sumOf { it.usage?.promptTokens ?: TokenEstimator.estimate(it.sentChars) },
        outputTokens = answers.sumOf { it.usage?.completionTokens ?: TokenEstimator.estimate(it.content) },
    )

    private fun initialMessages(request: LlmRequest): List<Message> {
        val system =
            if (config.outputMode == OutputMode.JSON_OBJECT) {
                request.prompt.system + "\n\nOUTPUT SCHEMA\n" + ReviewOutputSchema.text
            } else {
                request.prompt.system
            }
        return listOf(Message("system", system), Message("user", request.prompt.user))
    }

    private fun send(
        messages: List<Message>,
        maxOutputTokens: Int,
    ): Exchange {
        val body =
            buildMap {
                put("model", config.model)
                put("messages", messages.map { mapOf("role" to it.role, "content" to it.content) })
                put("temperature", TEMPERATURE)
                put("max_tokens", maxOutputTokens)
                config.reasoningEffort?.let { put("reasoning_effort", it) }
                put("response_format", responseFormat())
            }
        return transport.exchange(JSON.writeValueAsString(body))
    }

    private fun responseFormat(): Map<String, Any> =
        when (config.outputMode) {
            OutputMode.JSON_OBJECT -> {
                mapOf("type" to "json_object")
            }

            OutputMode.JSON_SCHEMA -> {
                mapOf(
                    "type" to "json_schema",
                    "json_schema" to
                        mapOf("name" to SCHEMA_NAME, "schema" to SchemaDialects.schemaFor(SchemaDialect.FULL)),
                )
            }

            OutputMode.JSON_SCHEMA_STRICT -> {
                mapOf(
                    "type" to "json_schema",
                    "json_schema" to
                        mapOf(
                            "name" to SCHEMA_NAME,
                            "strict" to true,
                            "schema" to SchemaDialects.schemaFor(SchemaDialect.STRICT_ALL_REQUIRED),
                        ),
                )
            }
        }

    private data class Message(
        val role: String,
        val content: String,
    )

    private sealed interface Evaluation {
        data class Valid(
            val review: ModelReview,
        ) : Evaluation

        data class Problem(
            val instruction: String,
            val detail: String,
        ) : Evaluation
    }

    private companion object {
        const val TEMPERATURE = 0.2
        const val SCHEMA_NAME = "firstpass_review"
        const val MAX_ECHOED_CHARS = 4000
        const val CUT_OFF_INSTRUCTION =
            "Your previous answer was cut off before the end. Return a complete JSON object that matches " +
                "the schema, with fewer or shorter comments."
    }
}
