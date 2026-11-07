package io.github.yassineh04.firstpass.llm

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ProvidersTest {
    @Test
    fun `never shows an API key when printed, even inside a configuration`() {
        val key = ApiKey("super-secret-value")

        key.toString() shouldNotContain "super-secret-value"
        Providers.gemini(key).toString() shouldNotContain "super-secret-value"
        key.value shouldBe "super-secret-value"
    }

    @Test
    fun `configures Gemini as the main provider, with the full schema`() {
        val gemini = Providers.gemini(ApiKey("k"))

        gemini.name shouldBe "gemini"
        gemini.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta/openai/"
        gemini.model shouldBe "gemini-3.5-flash-lite"
        gemini.outputMode shouldBe OutputMode.JSON_SCHEMA
        gemini.budget.inputTokensPerBatch shouldBe 20_000
        gemini.budget.maxBatches shouldBe 6
    }

    @Test
    fun `configures Groq with strict mode and its tighter budget`() {
        val groq = Providers.groq(ApiKey("k"))

        groq.baseUrl shouldBe "https://api.groq.com/openai/v1/"
        groq.model shouldBe "openai/gpt-oss-120b"
        groq.outputMode shouldBe OutputMode.JSON_SCHEMA_STRICT
        groq.budget.inputTokensPerBatch shouldBe 5_000
        groq.budget.maxBatches shouldBe 3
        groq.tokensPerDay shouldBe 200_000
    }

    @Test
    fun `configures a local Ollama model without a key`() {
        val ollama = Providers.ollama(model = "qwen2.5-coder:7b")

        ollama.baseUrl shouldBe "http://localhost:11434/v1/"
        ollama.apiKey.shouldBeNull()
        ollama.outputMode shouldBe OutputMode.JSON_OBJECT
    }
}
