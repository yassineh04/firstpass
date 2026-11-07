package io.github.yassineh04.firstpass.llm

import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    private val policy = RetryPolicy()

    @Test
    fun `doubles the wait after each attempt and adds up to one base delay of jitter`() {
        val first = policy.delay(attempt = 1, retryAfter = null).shouldNotBeNull()
        val second = policy.delay(attempt = 2, retryAfter = null).shouldNotBeNull()

        first shouldBeGreaterThanOrEqualTo 1.seconds
        first shouldBeLessThan 2.seconds
        second shouldBeGreaterThanOrEqualTo 2.seconds
        second shouldBeLessThan 3.seconds
    }

    @Test
    fun `follows retry-after up to 30 seconds, and refuses to wait longer`() {
        policy.delay(attempt = 1, retryAfter = 30.seconds) shouldBe 30.seconds
        policy.delay(attempt = 1, retryAfter = 31.seconds).shouldBeNull()
    }

    @Test
    fun `reads retry-after in seconds and ignores the HTTP date form`() {
        RetryPolicy.parseRetryAfter("2") shouldBe 2.seconds
        RetryPolicy.parseRetryAfter("1.5") shouldBe 1.5.seconds
        RetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT").shouldBeNull()
        RetryPolicy.parseRetryAfter(null).shouldBeNull()
    }
}
