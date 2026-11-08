package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class CommentFormatterTest {
    @Test
    fun `writes the severity and category in bold, the title, the explanation, the suggestion and the footer`() {
        val comment =
            CommentFormatter.inline(
                finding(suggestion = "    val user = repo.find(id) ?: throw UserNotFound(id)"),
                MODEL,
                ReviewLanguage.EN,
                URL,
            )

        comment shouldBe
            """
            **Major · Bug**: Crash when the user is missing

            `repo.find(id)` can return null.

            ```suggestion
                val user = repo.find(id) ?: throw UserNotFound(id)
            ```

            <sub>FirstPass (AI) · gemini-3.5-flash-lite · Useful? React with 👍 or 👎 · [How FirstPass works](https://example.org/firstpass)</sub>
            """.trimIndent()
    }

    @Test
    fun `writes the labels and the footer in French`() {
        val comment =
            CommentFormatter.inline(
                finding(severity = Severity.CRITICAL, category = Category.SECURITY, title = "Injection SQL"),
                MODEL,
                ReviewLanguage.FR,
                URL,
            )

        comment shouldStartWith "**Critique · Sécurité** : Injection SQL"
        comment shouldContain "<sub>FirstPass (IA) · gemini-3.5-flash-lite · Utile ? Réagissez avec 👍 ou 👎 · "
        comment shouldContain "[Fonctionnement de FirstPass](https://example.org/firstpass)</sub>"
    }

    @Test
    fun `leaves out the suggestion block when there is none, or when a code fence in it would break the block`() {
        val withoutSuggestion = CommentFormatter.inline(finding(), MODEL, ReviewLanguage.EN, URL)
        val fenceInside =
            CommentFormatter.inline(
                finding(suggestion = "val doc = \"\"\"\n```\n\"\"\""),
                MODEL,
                ReviewLanguage.EN,
                URL,
            )

        withoutSuggestion shouldNotContain "```"
        fenceInside shouldNotContain "```suggestion"
    }

    @Test
    fun `escapes angle brackets outside code, so that GitHub does not take generics for HTML tags`() {
        val comment =
            CommentFormatter.inline(
                finding(
                    title = "Use Map<String, Int> here",
                    body = "Prefer `Map<String, Int>` over List<Pair<String, Int>>.",
                ),
                MODEL,
                ReviewLanguage.EN,
                URL,
            )

        comment shouldContain "**Major · Bug**: Use Map&lt;String, Int> here"
        comment shouldContain "Prefer `Map<String, Int>` over List&lt;Pair&lt;String, Int>>."
        CommentFormatter.inline(
            finding(body = "A List<String> is not a `Set<String>`."),
            MODEL,
            ReviewLanguage.EN,
            URL,
        ) shouldContain
            "A List&lt;String> is not a `Set<String>`."
    }
}
