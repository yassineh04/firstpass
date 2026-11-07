package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.prompt.Prompt
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.LlmRequest
import io.github.yassineh04.firstpass.core.review.LlmResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class FakeLlmClientTest {
    private val request =
        LlmRequest(Prompt(system = "You are FirstPass.", user = "Review this.", version = "1"), maxOutputTokens = 1500)

    @Test
    fun `answers with an empty review by default, without any network call`() {
        val result = FakeLlmClient().review(request).shouldBeInstanceOf<LlmResult.Success>()

        result.review.comments.shouldBeEmpty()
        result.provider shouldBe "fake"
        result.inputTokens shouldBeGreaterThan 0
    }

    @Test
    fun `returns the scripted answer`() {
        val answer =
            """{"summary": "s", "comments": [{"path": "a.kt", "line": 3, "severity": "major", "category": "bug",
            |"title": "t", "body": "b", "confidence": 0.9}]}
            """.trimMargin()

        val result = FakeLlmClient { answer }.review(request).shouldBeInstanceOf<LlmResult.Success>()

        result.review.comments
            .single()
            .line shouldBe 3
    }

    @Test
    fun `reports a scripted answer that breaks the schema as invalid output`() {
        val result = FakeLlmClient { """{"summary": 42}""" }.review(request).shouldBeInstanceOf<LlmResult.Failure>()

        result.reason shouldBe LlmFailure.INVALID_OUTPUT
    }
}
