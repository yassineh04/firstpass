package io.github.yassineh04.firstpass.core.finding

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TextSanitizerTest {
    @Test
    fun `wraps mentions in backticks so that GitHub notifies nobody`() {
        TextSanitizer.clean("Ping @octocat and @my-org/reviewers, mail dev@example.com, keep `@done`", MAX) shouldBe
            "Ping `@octocat` and `@my-org/reviewers`, mail dev@example.com, keep `@done`"
    }

    @Test
    fun `removes images, comments and dangerous HTML but keeps generics in code`() {
        val text =
            "![tracker](https://evil.example/p.png)Use `Map<String, Int>` here." +
                "<img src=x onerror=alert(1)><script>steal()</script><!-- hidden --> " +
                "<a href=\"https://evil.example\">See docs</a>."

        TextSanitizer.clean(text, MAX) shouldBe "Use `Map<String, Int>` here. See docs."
    }

    @Test
    fun `cuts text that is too long and marks the cut`() {
        TextSanitizer.clean("x".repeat(100), 10) shouldBe "x".repeat(9) + "…"
    }

    private companion object {
        const val MAX = 800
    }
}
