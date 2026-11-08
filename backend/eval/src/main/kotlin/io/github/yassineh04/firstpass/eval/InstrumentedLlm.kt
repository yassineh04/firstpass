package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.llm.Sleeper
import kotlin.time.Duration

/**
 * One model call: our estimate of the input tokens, and the count reported by the provider
 * (null when the call failed). The reported count also includes any repair attempt.
 */
internal data class CallRecord(
    val provider: String,
    val estimatedInputTokens: Int,
    val reportedInputTokens: Int?,
)

/** Waits [pause] between two model calls, to stay under the provider's requests-per-minute limit. */
internal class Pacer(
    private val pause: Duration,
    private val sleeper: Sleeper = Sleeper.THREAD,
) {
    private var calls = 0

    fun beforeCall() {
        if (calls > 0 && pause > Duration.ZERO) sleeper.sleep(pause)
        calls++
    }
}

/** Wraps a provider to pace its calls and record the token estimate next to the reported count. */
internal class InstrumentedLlm(
    private val provider: String,
    private val inner: LlmPort,
    private val pacer: Pacer,
    private val calls: MutableList<CallRecord>,
) : LlmPort {
    override fun review(request: LlmRequest): LlmResult {
        pacer.beforeCall()
        val result = inner.review(request)
        val estimate = TokenEstimator.estimate(request.prompt.system) + TokenEstimator.estimate(request.prompt.user)
        calls += CallRecord(provider, estimate, (result as? LlmResult.Success)?.inputTokens)
        return result
    }
}
