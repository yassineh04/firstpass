package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.llm.ApiKey
import io.github.yassineh04.firstpass.llm.Providers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ProviderChainTest {
    @Test
    fun `the fake provider needs no key and answers locally`() {
        ProviderChain.build("fake", model = null, settings = emptyMap()).map { it.name } shouldContainExactly
            listOf("fake")
    }

    @Test
    fun `Gemini comes alone, or with Groq as fallback when a Groq key is set`() {
        ProviderChain.configs("gemini", null, mapOf(GEMINI to KEY)).map { it.name } shouldContainExactly
            listOf("gemini")
        ProviderChain.configs("gemini", null, mapOf(GEMINI to KEY, GROQ to KEY)).map { it.name } shouldContainExactly
            listOf("gemini", "groq")
        ProviderChain.configs("gemini", null, mapOf(GEMINI to KEY, GROQ to " ")).map { it.name } shouldContainExactly
            listOf("gemini")
    }

    @Test
    fun `keeps the provider defaults and applies --model to the main provider only`() {
        val configs = ProviderChain.configs("gemini", "gemini-other", mapOf(GEMINI to KEY, GROQ to KEY))

        configs.map { it.model } shouldContainExactly listOf("gemini-other", Providers.groq(API_KEY).model)
        configs.first().budget shouldBe Providers.gemini(API_KEY).budget
        ProviderChain.configs("groq", null, mapOf(GROQ to KEY)).map { it.name } shouldContainExactly listOf("groq")
        ProviderChain.build("gemini", null, mapOf(GEMINI to KEY)).single().budget shouldBe
            Providers.gemini(API_KEY).budget
    }

    @Test
    fun `Ollama takes its model and address from the option or the settings`() {
        val fromSettings =
            ProviderChain
                .configs(
                    "ollama",
                    null,
                    mapOf(
                        "OLLAMA_MODEL" to "qwen-coder",
                        "OLLAMA_BASE_URL" to "http://ollama:11434/v1/",
                    ),
                ).single()

        fromSettings.model shouldBe "qwen-coder"
        fromSettings.baseUrl shouldBe "http://ollama:11434/v1/"
        ProviderChain.configs("ollama", "llama-x", emptyMap()).single().baseUrl shouldBe Providers.ollama("m").baseUrl
        shouldThrow<CliError> { ProviderChain.configs("ollama", null, emptyMap()) }.message shouldContain "--model"
    }

    @Test
    fun `explains a missing key or an unknown provider`() {
        shouldThrow<CliError> { ProviderChain.configs("gemini", null, emptyMap()) }.message.let {
            it shouldContain GEMINI
            it shouldContain ".env"
        }
        shouldThrow<CliError> { ProviderChain.configs("groq", null, mapOf(GROQ to "")) }.message shouldContain GROQ
        shouldThrow<CliError> { ProviderChain.build("openai", null, emptyMap()) }.message shouldContain
            "fake, ollama, gemini or groq"
    }

    private companion object {
        const val GEMINI = "GEMINI_API_KEY"
        const val GROQ = "GROQ_API_KEY"
        const val KEY = "not-a-real-key"
        val API_KEY = ApiKey(KEY)
    }
}
