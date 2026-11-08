package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.llm.ApiKey
import io.github.yassineh04.firstpass.llm.FakeLlmClient
import io.github.yassineh04.firstpass.llm.OpenAiCompatibleClient
import io.github.yassineh04.firstpass.llm.ProviderConfig
import io.github.yassineh04.firstpass.llm.Providers

/**
 * The providers named on the command line, built from the local settings. Gemini gets Groq as
 * fallback when a Groq key is set, as in production; the other providers come alone. `--model`
 * only changes the main provider.
 */
internal object ProviderChain {
    private const val FAKE = "fake"
    private const val GEMINI_KEY = "GEMINI_API_KEY"
    private const val GROQ_KEY = "GROQ_API_KEY"

    /** Same batch budget as Gemini, so that a local run with the fake provider plans like production. */
    private val FAKE_BUDGET = ProviderBudget(inputTokensPerBatch = 20_000, maxBatches = 6)

    fun build(
        provider: String,
        model: String?,
        settings: Map<String, String>,
    ): List<ReviewProvider> =
        if (provider == FAKE) {
            listOf(ReviewProvider(FAKE, FakeLlmClient(), FAKE_BUDGET, tokensPerDay = Int.MAX_VALUE))
        } else {
            configs(provider, model, settings).map {
                ReviewProvider(it.name, OpenAiCompatibleClient(it), it.budget, it.tokensPerDay)
            }
        }

    fun configs(
        provider: String,
        model: String?,
        settings: Map<String, String>,
    ): List<ProviderConfig> =
        when (provider) {
            "gemini" -> listOfNotNull(gemini(apiKey(settings, GEMINI_KEY), model), groqFallback(settings))
            "groq" -> listOf(groq(apiKey(settings, GROQ_KEY), model))
            "ollama" -> listOf(ollama(settings, model))
            else -> throw CliError("Unknown provider: $provider. Use fake, ollama, gemini or groq.")
        }

    private fun gemini(
        key: ApiKey,
        model: String?,
    ) = if (model == null) Providers.gemini(key) else Providers.gemini(key, model)

    private fun groq(
        key: ApiKey,
        model: String?,
    ) = if (model == null) Providers.groq(key) else Providers.groq(key, model)

    private fun groqFallback(settings: Map<String, String>): ProviderConfig? =
        setting(settings, GROQ_KEY)?.let { Providers.groq(ApiKey(it)) }

    private fun ollama(
        settings: Map<String, String>,
        model: String?,
    ): ProviderConfig {
        val name =
            model ?: setting(settings, "OLLAMA_MODEL")
                ?: throw CliError("Ollama needs a model: pass --model, or set OLLAMA_MODEL in the .env file.")
        val baseUrl = setting(settings, "OLLAMA_BASE_URL")
        return if (baseUrl == null) Providers.ollama(name) else Providers.ollama(name, baseUrl)
    }

    private fun apiKey(
        settings: Map<String, String>,
        name: String,
    ): ApiKey =
        setting(settings, name)?.let(::ApiKey)
            ?: throw CliError(
                "$name is not set: add it to the .env file at the repository root, or set it in the environment.",
            )

    private fun setting(
        settings: Map<String, String>,
        name: String,
    ): String? = settings[name]?.trim()?.takeIf { it.isNotEmpty() }
}
