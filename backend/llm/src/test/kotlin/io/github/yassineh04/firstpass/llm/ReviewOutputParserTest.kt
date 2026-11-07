package io.github.yassineh04.firstpass.llm

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.ModelComment
import io.github.yassineh04.firstpass.core.finding.ModelReview
import io.github.yassineh04.firstpass.core.finding.Severity
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class ReviewOutputParserTest {
    private val parser = ReviewOutputParser()

    private val validAnswer =
        """
        {
          "summary": "One likely crash.",
          "comments": [
            {
              "path": "src/UserService.kt",
              "start_line": null,
              "line": 41,
              "severity": "major",
              "category": "bug",
              "title": "Crash when the user is missing",
              "body": "repo.find(id) can return null.",
              "suggestion": "    val user = repo.find(id) ?: throw UserNotFound(id)",
              "confidence": 0.86
            },
            {
              "path": "src/UserService.kt",
              "start_line": 42,
              "line": 43,
              "severity": "minor",
              "category": "security",
              "title": "Personal data in logs",
              "body": "Log the id instead of the email.",
              "confidence": 0.7
            }
          ]
        }
        """.trimIndent()

    private fun errorsOf(answer: String): List<String> =
        parser.parse(answer).shouldBeInstanceOf<ParsedOutput.Invalid>().errors

    @Test
    fun `turns a valid answer into typed comments`() {
        parser.parse(validAnswer) shouldBe
            ParsedOutput.Valid(
                ModelReview(
                    summary = "One likely crash.",
                    comments =
                        listOf(
                            ModelComment(
                                path = "src/UserService.kt",
                                startLine = null,
                                line = 41,
                                severity = Severity.MAJOR,
                                category = Category.BUG,
                                title = "Crash when the user is missing",
                                body = "repo.find(id) can return null.",
                                suggestion = "    val user = repo.find(id) ?: throw UserNotFound(id)",
                                confidence = 0.86,
                            ),
                            ModelComment(
                                path = "src/UserService.kt",
                                startLine = 42,
                                line = 43,
                                severity = Severity.MINOR,
                                category = Category.SECURITY,
                                title = "Personal data in logs",
                                body = "Log the id instead of the email.",
                                suggestion = null,
                                confidence = 0.7,
                            ),
                        ),
                ),
            )
    }

    @Test
    fun `accepts an answer wrapped in a Markdown code block`() {
        parser.parse("```json\n$validAnswer\n```").shouldBeInstanceOf<ParsedOutput.Valid>()
    }

    @Test
    fun `reports an answer that is not JSON`() {
        errorsOf("Sure! Here is my review: the code looks fine.").single() shouldContain "not valid JSON"
    }

    @Test
    fun `reports every schema violation with its location`() {
        val answer =
            validAnswer
                .replace("\"major\"", "\"blocker\"")
                .replace("\"confidence\": 0.86", "\"confidence\": 1.5, \"extra\": true")

        val errors = errorsOf(answer)

        errors.shouldNotBeEmpty()
        errors.joinToString("\n") shouldContain "severity"
        errors.joinToString("\n") shouldContain "confidence"
        errors.joinToString("\n") shouldContain "extra"
    }

    @Test
    fun `enforces the length limits that some providers ignore`() {
        errorsOf(validAnswer.replace("Crash when the user is missing", "x".repeat(91))).joinToString() shouldContain
            "title"
    }

    @Test
    fun `rejects more than ten comments in one batch`() {
        val comment =
            """{"path": "a.kt", "line": 1, "severity": "minor", "category": "bug", "title": "t", "body": "b", "confidence": 0.9}"""
        val answer = """{"summary": "s", "comments": [${List(11) { comment }.joinToString(",")}]}"""

        errorsOf(answer).joinToString() shouldContain "comments"
    }
}
