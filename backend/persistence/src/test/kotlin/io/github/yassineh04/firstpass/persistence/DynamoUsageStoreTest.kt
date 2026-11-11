package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DynamoUsageStoreTest {
    private val table = DynamoLocal.freshTable()
    private val store = DynamoUsageStore(table, providers = listOf("gemini", "groq", "ollama"))
    private val day = "2026-11-09"

    @Test
    fun `reserves reviews up to the limit, per installation and per day`() {
        List(3) { store.reserveReview(42, day, limit = 2) } shouldBe listOf(true, true, false)

        store.reserveReview(43, day, limit = 2) shouldBe true
        store.reserveReview(42, "2026-11-10", limit = 2) shouldBe true
    }

    @Test
    fun `never lets two jobs running at once take the same last review`() {
        val pool = Executors.newFixedThreadPool(8)
        val answers =
            try {
                pool.invokeAll(List(20) { Callable { store.reserveReview(44, day, limit = 5) } }).map { it.get() }
            } finally {
                pool.shutdown()
            }

        answers.count { it } shouldBe 5
    }

    @Test
    fun `adds the calls and tokens of each review to the installation, to each provider and to everyone`() {
        store.addConsumption(42, day, listOf(ProviderUsage("gemini", "model-a", 2, 1000, 200)))
        store.addConsumption(
            43,
            day,
            listOf(ProviderUsage("gemini", "model-a", 1, 300, 50), ProviderUsage("groq", "model-b", 3, 900, 100)),
        )

        store.globalLlmCalls(day) shouldBe 6
        store.providerTokens(day) shouldBe mapOf("gemini" to 1550, "groq" to 1000)
        val installation = table.items("USAGE#43").single()
        installation.counter("llmCalls") shouldBe 4
        installation.counter("tokensIn") shouldBe 1200
        installation.counter("tokensOut") shouldBe 150
    }

    @Test
    fun `starts each day at zero and writes nothing for a review without model calls`() {
        store.addConsumption(42, day, emptyList())

        store.globalLlmCalls(day) shouldBe 0
        store.providerTokens(day).shouldBeEmpty()
        table.items("USAGE#42").shouldBeEmpty()
    }

    @Test
    fun `keeps the counters 35 days, from the first change of the day`() {
        store.reserveReview(45, day, limit = 5)
        store.reserveReview(45, day, limit = 5)
        store.addConsumption(45, day, listOf(ProviderUsage("gemini", null, 1, 10, 1)))

        val expiry = Instant.parse("2026-12-14T00:00:00Z").epochSecond
        table.items("USAGE#45").single().long("expiresAt") shouldBe expiry
        table.items("USAGE#PROVIDER#gemini").single().long("expiresAt") shouldBe expiry
    }
}
