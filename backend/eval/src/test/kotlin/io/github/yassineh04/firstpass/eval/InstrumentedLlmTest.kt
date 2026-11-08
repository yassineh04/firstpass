package io.github.yassineh04.firstpass.eval

import io.github.yassineh04.firstpass.core.batch.TokenEstimator
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.prompt.Prompt
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.github.yassineh04.firstpass.llm.Sleeper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class InstrumentedLlmTest {
    private val slept = mutableListOf<Duration>()
    private val sleeper = Sleeper { slept += it }

    @Test
    fun `pauses between calls, never before the first one`() {
        val pacer = Pacer(4.seconds, sleeper)

        repeat(3) { pacer.beforeCall() }

        slept shouldContainExactly listOf(4.seconds, 4.seconds)
    }

    @Test
    fun `does not sleep at all with a zero pause`() {
        val pacer = Pacer(Duration.ZERO, sleeper)

        repeat(3) { pacer.beforeCall() }

        slept.shouldBeEmpty()
    }

    @Test
    fun `records the estimated and the reported input tokens, and passes the result on`() {
        val calls = mutableListOf<CallRecord>()
        val success =
            LlmResult.Success(
                ModelReview("Fine.", emptyList()),
                "gemini",
                "m",
                inputTokens = 130,
                outputTokens = 5,
            )
        val failure = LlmResult.Failure(LlmFailure.UNAVAILABLE, "gemini", "down")
        val answers = ArrayDeque(listOf<LlmResult>(success, failure))
        val llm = InstrumentedLlm("gemini", LlmPort { answers.removeFirst() }, Pacer(Duration.ZERO, sleeper), calls)
        val request = LlmRequest(Prompt(system = "s".repeat(40), user = "u".repeat(60), version = "1"), 100)
        val estimate = TokenEstimator.estimate("s".repeat(40)) + TokenEstimator.estimate("u".repeat(60))

        llm.review(request) shouldBe success
        llm.review(request) shouldBe failure

        calls shouldContainExactly
            listOf(CallRecord("gemini", estimate, 130), CallRecord("gemini", estimate, null))
    }
}
