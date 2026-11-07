package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.review.LlmFailure
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

internal data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
)

/** The result of one chat completion call, after the retries. */
internal sealed interface Exchange {
    data class Answered(
        val content: String,
        val finishReason: String,
        val usage: Usage?,
        val sentChars: Int,
    ) : Exchange

    data class Failed(
        val reason: LlmFailure,
        val detail: String,
    ) : Exchange
}

/**
 * Sends one chat completion request with the JDK HTTP client, retrying rate limits, server
 * errors and network failures as the [RetryPolicy] allows. Error details never contain the
 * request, the code under review or the key.
 */
internal class ChatTransport(
    private val config: ProviderConfig,
    private val retry: RetryPolicy,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT.toJavaDuration()).build(),
) {
    private val endpoint = URI.create(config.baseUrl.removeSuffix("/") + "/chat/completions")

    fun exchange(body: String): Exchange {
        var attempt = 1
        var outcome = send(body)
        while (outcome.retryable && attempt < retry.maxAttempts) {
            val delay = retry.delay(attempt, outcome.retryAfter) ?: break
            retry.sleeper.sleep(delay)
            attempt++
            outcome = send(body)
        }
        return outcome.toExchange(attempt, body.length)
    }

    private fun send(body: String): HttpOutcome {
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(config.requestTimeout.toJavaDuration())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .apply { config.apiKey?.let { header("Authorization", "Bearer ${it.value}") } }
                .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val retryAfter = RetryPolicy.parseRetryAfter(response.headers().firstValue("retry-after").orElse(null))
            HttpOutcome.Response(response.statusCode(), retryAfter, response.body())
        } catch (exception: IOException) {
            HttpOutcome.NetworkError(exception.javaClass.simpleName)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            HttpOutcome.NetworkError(exception.javaClass.simpleName)
        }
    }

    private fun HttpOutcome.toExchange(
        attempts: Int,
        sentChars: Int,
    ): Exchange =
        when (this) {
            is HttpOutcome.NetworkError -> {
                Exchange.Failed(LlmFailure.UNAVAILABLE, "$error after $attempts attempt(s)")
            }

            is HttpOutcome.Response -> {
                when {
                    status in HTTP_OK_RANGE -> {
                        parseCompletion(body, sentChars)
                    }

                    status == HTTP_TOO_MANY_REQUESTS -> {
                        Exchange.Failed(
                            LlmFailure.RATE_LIMITED,
                            "HTTP 429 after $attempts attempt(s)",
                        )
                    }

                    status >= HTTP_SERVER_ERROR -> {
                        Exchange.Failed(
                            LlmFailure.UNAVAILABLE,
                            "HTTP $status after $attempts attempt(s)",
                        )
                    }

                    else -> {
                        Exchange.Failed(LlmFailure.REJECTED, "HTTP $status: ${body.take(MAX_ERROR_CHARS)}")
                    }
                }
            }
        }

    private fun parseCompletion(
        body: String,
        sentChars: Int,
    ): Exchange =
        try {
            val root = JSON.readTree(body)
            val choice = root.path("choices").path(0)
            if (choice.path("message").isMissingNode) {
                Exchange.Failed(LlmFailure.UNAVAILABLE, "the provider returned no chat completion")
            } else {
                Exchange.Answered(
                    content = choice.path("message").path("content").asString(""),
                    finishReason = choice.path("finish_reason").asString(""),
                    usage = usageOf(root.path("usage")),
                    sentChars = sentChars,
                )
            }
        } catch (_: JacksonException) {
            Exchange.Failed(LlmFailure.UNAVAILABLE, "the provider returned a body that is not JSON")
        }

    private fun usageOf(node: JsonNode): Usage? =
        if (node.isMissingNode || node.isNull) {
            null
        } else {
            Usage(node.path("prompt_tokens").asInt(), node.path("completion_tokens").asInt())
        }

    private sealed interface HttpOutcome {
        val retryable: Boolean
        val retryAfter: Duration?

        data class Response(
            val status: Int,
            override val retryAfter: Duration?,
            val body: String,
        ) : HttpOutcome {
            override val retryable: Boolean
                get() = status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR
        }

        data class NetworkError(
            val error: String,
        ) : HttpOutcome {
            override val retryable = true
            override val retryAfter: Duration? = null
        }
    }

    private companion object {
        val CONNECT_TIMEOUT = 10.seconds
        val HTTP_OK_RANGE = 200..299
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
        const val MAX_ERROR_CHARS = 300
    }
}
