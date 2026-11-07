package io.github.yassineh04.firstpass.core.finding

import io.github.yassineh04.firstpass.core.diff.CommentOn
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FindingValidatorTest {
    private val file =
        DiffAnnotator
            .annotate(
                DiffFile(
                    path = PATH,
                    status = FileStatus.MODIFIED,
                    patch =
                        listOf(
                            "@@ -40,2 +40,4 @@",
                            " fun findUser(id: String): User {",
                            "+    val user = repo.find(id)!!",
                            "+    log.info(\"found \${user.email}\")",
                            "     return user",
                            "@@ -90,1 +92,2 @@",
                            " fun tail() = Unit",
                            "+fun added() = Unit",
                        ).joinToString("\n"),
                ),
            ).shouldNotBeNull()

    private fun comment(
        path: String = PATH,
        line: Int = 41,
        startLine: Int? = null,
        severity: Severity = Severity.MAJOR,
        confidence: Double = 0.86,
        body: String = "repo.find(id) can return null.",
        suggestion: String? = SUGGESTION,
    ) = ModelComment(
        path = path,
        startLine = startLine,
        line = line,
        severity = severity,
        category = Category.BUG,
        title = "Crash when the user is missing",
        body = body,
        suggestion = suggestion,
        confidence = confidence,
    )

    private fun validate(
        vararg comments: ModelComment,
        settings: ReviewSettings = ReviewSettings(),
    ) = FindingValidator(settings).validate(comments.toList(), listOf(file))

    @Test
    fun `keeps a valid comment as an inline finding, with its fingerprint`() {
        val result = validate(comment())

        result.anchored shouldContainExactly
            listOf(
                Finding(
                    path = PATH,
                    startLine = null,
                    line = 41,
                    severity = Severity.MAJOR,
                    category = Category.BUG,
                    title = "Crash when the user is missing",
                    body = "repo.find(id) can return null.",
                    suggestion = "    val user = repo.find(id) ?: throw UserNotFound(id)",
                    confidence = 0.86,
                    fingerprint =
                        Fingerprint.of(
                            PATH,
                            Category.BUG,
                            "Crash when the user is missing",
                            "    val user = repo.find(id)!!",
                        ),
                ),
            )
        result.unanchored.shouldBeEmpty()
        result.rejected shouldBe 0
    }

    @Test
    fun `rejects low confidence and severities below the threshold`() {
        val result =
            validate(
                comment(confidence = 0.59),
                comment(severity = Severity.NIT),
                comment(severity = Severity.MINOR),
            )

        result.anchored.map { it.severity } shouldContainExactly listOf(Severity.MINOR)
        result.rejected shouldBe 2
    }

    @Test
    fun `moves comments on unknown files or lines outside the diff to the summary`() {
        val result = validate(comment(path = "src/Other.kt"), comment(line = 60))

        result.anchored.shouldBeEmpty()
        result.unanchored.map { it.path to it.line } shouldContainExactly listOf("src/Other.kt" to 41, PATH to 60)
    }

    @Test
    fun `accepts a multi-line range only inside one hunk`() {
        val result =
            validate(
                comment(startLine = 41, line = 42),
                comment(startLine = 42, line = 93),
                comment(startLine = 41, line = 41),
            )

        result.anchored.map { it.startLine to it.line } shouldContainExactly listOf(41 to 42, null to 41)
        result.unanchored.map { it.startLine to it.line } shouldContainExactly listOf(42 to 93)
    }

    @Test
    fun `allows context lines only with diff_context`() {
        validate(comment(line = 40)).unanchored.map { it.line } shouldContainExactly listOf(40)
        validate(comment(line = 40), settings = ReviewSettings(commentOn = CommentOn.DIFF_CONTEXT)).anchored.map {
            it.line
        } shouldContainExactly
            listOf(40)
    }

    @Test
    fun `drops suggestions when they are turned off`() {
        validate(comment(), settings = ReviewSettings(suggestions = false))
            .anchored
            .single()
            .suggestion
            .shouldBeNull()
    }

    @Test
    fun `drops a suggestion that repeats the line above, whatever its indentation, and keeps the comment`() {
        val finding =
            validate(
                comment(
                    suggestion =
                        "    fun findUser(id: String): User {\n" +
                            "        val user = repo.find(id) ?: throw UserNotFound(id)",
                ),
            ).anchored.single()

        finding.line shouldBe 41
        finding.suggestion.shouldBeNull()
    }

    @Test
    fun `drops a suggestion that repeats the line below the commented range`() {
        validate(
            comment(
                startLine = 41,
                line = 42,
                suggestion =
                    "    val user = repo.find(id) ?: throw UserNotFound(id)\n" +
                        "    log.info(\"found \${user.id}\")\n" +
                        "    return user\n",
            ),
        ).anchored
            .single()
            .suggestion
            .shouldBeNull()
    }

    @Test
    fun `keeps a suggestion that adds lines, or that sits next to a line outside the diff`() {
        val addsLines = "    log.info(\"found user\")\n    metrics.increment(\"user.found\")"
        val lastLineOfHunk = "fun added(): Unit = Unit"

        validate(comment(line = 42, suggestion = addsLines), comment(line = 93, suggestion = lastLineOfHunk))
            .anchored
            .map { it.suggestion } shouldContainExactly listOf(addsLines, lastLineOfHunk)
    }

    @Test
    fun `drops a suggestion over the length limit instead of cutting it`() {
        val atLimit = "x".repeat(1200)

        validate(comment(suggestion = atLimit), comment(suggestion = atLimit + "x"))
            .anchored
            .map { it.suggestion } shouldContainExactly listOf(atLimit, null)
    }

    @Test
    fun `drops a suggestion that changes nothing`() {
        validate(
            comment(suggestion = "    val user = repo.find(id)!!  \n"),
            comment(
                startLine = 41,
                line = 42,
                suggestion = "    val user = repo.find(id)!!\n    log.info(\"found \${user.email}\")",
            ),
        ).anchored.map { it.suggestion } shouldContainExactly listOf(null, null)
    }

    @Test
    fun `cleans the text written by the model`() {
        validate(comment(body = "Ask @octocat before merging.")).anchored.single().body shouldBe
            "Ask `@octocat` before merging."
    }

    private companion object {
        const val PATH = "src/UserService.kt"
        const val SUGGESTION = "    val user = repo.find(id) ?: throw UserNotFound(id)"
    }
}
