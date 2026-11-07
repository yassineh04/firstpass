package io.github.yassineh04.firstpass.llm

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Waits between attempts; replaced by a recorder in tests, so that no test really sleeps. */
fun interface Sleeper {
    fun sleep(duration: Duration)

    companion object {
        val THREAD = Sleeper { Thread.sleep(it.inWholeMilliseconds) }
    }
}

/**
 * When to try again after a rate limit or a server error. The provider's `retry-after` is
 * followed up to [maxRetryAfter]; beyond that, waiting is pointless and the caller can switch to
 * another provider. Without `retry-after`, the wait doubles at each attempt, plus random jitter so
 * that several workers do not retry at the same instant.
 */
class RetryPolicy(
    val maxAttempts: Int = 2,
    private val baseDelay: Duration = 1.seconds,
    private val maxRetryAfter: Duration = 30.seconds,
    private val random: Random = Random.Default,
    val sleeper: Sleeper = Sleeper.THREAD,
) {
    /** The wait before the attempt after [attempt], or null when the caller should not wait. */
    fun delay(
        attempt: Int,
        retryAfter: Duration?,
    ): Duration? =
        when {
            retryAfter == null -> baseDelay * (1 shl (attempt - 1)) + baseDelay * random.nextDouble()
            retryAfter <= maxRetryAfter -> retryAfter
            else -> null
        }

    companion object {
        /** `retry-after` in seconds; the HTTP-date form is ignored and the backoff applies instead. */
        fun parseRetryAfter(value: String?): Duration? =
            value
                ?.trim()
                ?.toDoubleOrNull()
                ?.takeIf { it >= 0 }
                ?.seconds
    }
}
