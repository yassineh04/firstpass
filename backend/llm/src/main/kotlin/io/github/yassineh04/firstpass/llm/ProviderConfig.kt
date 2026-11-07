package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An API key that never shows its value when printed, so it cannot leak into logs by accident. */
class ApiKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "An API key cannot be blank" }
    }

    override fun toString(): String = "ApiKey(****)"
}

/** How the provider is asked for JSON. */
enum class OutputMode {
    /** `response_format` with the full JSON Schema (Gemini). */
    JSON_SCHEMA,

    /** `response_format` with `strict: true` and the all-required schema (Groq). */
    JSON_SCHEMA_STRICT,

    /** Plain JSON mode; the schema is appended to the system prompt (Ollama). */
    JSON_OBJECT,
}

data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: ApiKey?,
    val outputMode: OutputMode,
    val budget: ProviderBudget,
    val tokensPerDay: Int,
    val reasoningEffort: String?,
    val requestTimeout: Duration,
)

/**
 * Default configurations, checked against the providers' documentation and the free-tier limits
 * shown in Google AI Studio on 2026-11-07. Keys come from SSM in production and from `.env` locally.
 */
object Providers {
    private val REMOTE_TIMEOUT = 90.seconds
    private val LOCAL_TIMEOUT = 300.seconds

    /**
     * Gemini 3 models always reason before answering, and reasoning tokens use the output budget:
     * the output limit is higher than the size of the JSON answer, and the effort is kept low.
     * No daily token cap is shown for the free tier; its 500 requests a day are above the global
     * daily call limit.
     */
    fun gemini(
        apiKey: ApiKey,
        model: String = "gemini-3.5-flash-lite",
    ) = ProviderConfig(
        name = "gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        model = model,
        apiKey = apiKey,
        outputMode = OutputMode.JSON_SCHEMA,
        budget = ProviderBudget(inputTokensPerBatch = 20_000, maxBatches = 6, outputTokens = 4_096),
        tokensPerDay = Int.MAX_VALUE,
        reasoningEffort = "low",
        requestTimeout = REMOTE_TIMEOUT,
    )

    /** Groq free tier for gpt-oss-120b: 8,000 tokens a minute and 200,000 a day, hence small batches. */
    fun groq(
        apiKey: ApiKey,
        model: String = "openai/gpt-oss-120b",
    ) = ProviderConfig(
        name = "groq",
        baseUrl = "https://api.groq.com/openai/v1/",
        model = model,
        apiKey = apiKey,
        outputMode = OutputMode.JSON_SCHEMA_STRICT,
        budget = ProviderBudget(inputTokensPerBatch = 5_000, maxBatches = 3, outputTokens = 1_500),
        tokensPerDay = 200_000,
        reasoningEffort = null,
        requestTimeout = REMOTE_TIMEOUT,
    )

    /** A local model through Ollama: no key, no quota, but slower answers. */
    fun ollama(
        model: String,
        baseUrl: String = "http://localhost:11434/v1/",
    ) = ProviderConfig(
        name = "ollama",
        baseUrl = baseUrl,
        model = model,
        apiKey = null,
        outputMode = OutputMode.JSON_OBJECT,
        budget = ProviderBudget(inputTokensPerBatch = 8_000, maxBatches = 6, outputTokens = 1_500),
        tokensPerDay = Int.MAX_VALUE,
        reasoningEffort = null,
        requestTimeout = LOCAL_TIMEOUT,
    )
}
