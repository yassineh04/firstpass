package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.batch.NotReviewedFile
import io.github.yassineh04.firstpass.core.batch.TruncatedHunk
import io.github.yassineh04.firstpass.core.filter.SkipReason
import io.github.yassineh04.firstpass.core.filter.SkippedFile
import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.review.FailedBatch
import io.github.yassineh04.firstpass.core.review.LlmFailure
import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.review.ReviewReport
import io.github.yassineh04.firstpass.core.settings.ConfigIssue
import io.github.yassineh04.firstpass.core.settings.ConfigProblem
import io.github.yassineh04.firstpass.core.settings.Expectation
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class SummaryFormatterTest {
    private val usage = listOf(ProviderUsage("gemini", MODEL, calls = 1, inputTokens = 900, outputTokens = 60))

    @Test
    fun `says that nothing was found, how many files were reviewed, by which model and how fast`() {
        val summary = SummaryFormatter.summary(input(report(), changedFiles = 2))

        summary shouldBe
            """
            ### FirstPass review

            No issues found in the changed lines.

            **Files**

            - Reviewed: 2 files

            <sub>FirstPass (AI) · gemini-3.5-flash-lite · 1 min 12 s · FirstPass only comments: it never approves or blocks a pull request. · [How FirstPass works](https://example.org/firstpass)</sub>
            """.trimIndent()
    }

    @Test
    fun `counts issues by severity, gives the overview, and lists other findings with their explanation`() {
        val inline =
            listOf(finding(severity = Severity.CRITICAL, line = 10), finding(severity = Severity.MAJOR, line = 20))
        val other =
            finding(
                severity = Severity.MINOR,
                line = 99,
                startLine = 97,
                title = "Use List<String>",
                body = "Explained.",
            )
        val report = report(inline = inline, summary = listOf(other), modelSummaries = listOf("Adds a user lookup."))

        val summary = SummaryFormatter.summary(input(report, changedFiles = 1, maxComments = 2))

        summary shouldContain "Found 3 issues: 1 critical, 1 major, 1 minor."
        summary shouldContain "**Overview**\n\nAdds a user lookup."
        summary shouldContain
            "**Other findings**\n\nNot posted as inline comments: beyond the limit of 2 comments, or on lines outside the diff."
        summary shouldContain
            "<details><summary>Minor · Bug · <code>src/main/kotlin/UserService.kt:97-99</code> · Use List&lt;String&gt;</summary>\n\nExplained.\n\n</details>"
    }

    @Test
    fun `lists the files skipped, not reviewed, cut or without a valid answer, with the reason`() {
        val report =
            report(
                skipped =
                    listOf(
                        SkippedFile("package-lock.json", SkipReason.LOCKFILE),
                        SkippedFile("README.md", SkipReason.DOCUMENTATION),
                    ),
                notReviewed =
                    listOf(
                        NotReviewedFile("src/Big.kt", partial = true),
                        NotReviewedFile("src/Other.kt", partial = false),
                    ),
                truncated = listOf(TruncatedHunk("src/Huge.kt", "@@ -1,900 +1,900 @@")),
                failed =
                    listOf(
                        FailedBatch(
                            listOf("src/Broken.kt"),
                            "gemini",
                            LlmFailure.INVALID_OUTPUT,
                            "provider text never shown",
                        ),
                    ),
            )

        val summary = SummaryFormatter.summary(input(report, changedFiles = 7))

        summary shouldContain "- Reviewed: 3 files"
        summary shouldContain "- Skipped: `package-lock.json` (lockfile), `README.md` (documentation)"
        summary shouldContain "- Not reviewed (no batch left for them): `src/Big.kt` (partly), `src/Other.kt`"
        summary shouldContain "- Cut to fit a batch (the end of the hunk was not reviewed): `src/Huge.kt`"
        summary shouldContain "- No valid answer: `src/Broken.kt` (invalid answer from the model)"
        summary shouldNotContain "provider text never shown"
    }

    @Test
    fun `shows at most 20 items per list`() {
        val skipped = (1..23).map { SkippedFile("docs/page$it.md", SkipReason.DOCUMENTATION) }

        val summary = SummaryFormatter.summary(input(report(skipped = skipped), changedFiles = 23))

        summary shouldContain "`docs/page20.md` (documentation), and 3 more"
        summary shouldNotContain "page21"
    }

    @Test
    fun `explains each problem of the settings file without repeating the invalid value`() {
        val issues =
            listOf(
                ConfigIssue(null, ConfigProblem.INVALID_FILE, detail = "mapping values are not allowed, line `3`"),
                ConfigIssue("version", ConfigProblem.UNSUPPORTED_VERSION, Expectation.OneOf(listOf("1"))),
                ConfigIssue("colour", ConfigProblem.UNKNOWN_SETTING),
                ConfigIssue("language", ConfigProblem.INVALID_VALUE, Expectation.OneOf(listOf("en", "fr"))),
                ConfigIssue("focus", ConfigProblem.INVALID_VALUE, Expectation.ListOf(listOf("bug", "security"))),
                ConfigIssue("max_comments", ConfigProblem.INVALID_VALUE, Expectation.WholeNumber(0..50)),
                ConfigIssue("enabled", ConfigProblem.INVALID_VALUE, Expectation.TrueOrFalse),
                ConfigIssue("instructions", ConfigProblem.TOO_LONG),
            )

        val summary = SummaryFormatter.summary(input(report(), changedFiles = 1, configIssues = issues))

        summary shouldContain "**Settings file**\n\nSome settings in `.firstpass.yml` were not applied:"
        summary shouldContain
            "- the file could not be read, so the default settings apply: `mapping values are not allowed, line 3`"
        summary shouldContain "- `version`: only version 1 is supported, so the whole file was ignored"
        summary shouldContain "- `colour` is not a FirstPass setting and was ignored"
        summary shouldContain "- `language` was ignored: expected one of `en`, `fr`"
        summary shouldContain "- `focus` was ignored: expected a non-empty list of `bug`, `security`"
        summary shouldContain "- `max_comments` was ignored: expected a whole number from 0 to 50"
        summary shouldContain "- `enabled` was ignored: expected `true` or `false`"
        summary shouldContain
            "- `instructions`: only the first 1,000 characters are sent to the model"
    }

    @Test
    fun `writes the whole summary in French, with French plurals`() {
        val report =
            report(
                inline =
                    listOf(
                        finding(severity = Severity.MAJOR),
                        finding(severity = Severity.MINOR, line = 7),
                        finding(severity = Severity.MINOR, line = 8),
                    ),
                skipped = listOf(SkippedFile("yarn.lock", SkipReason.LOCKFILE)),
            )
        val issues = listOf(ConfigIssue("langue", ConfigProblem.UNKNOWN_SETTING))

        val summary =
            SummaryFormatter.summary(
                input(report, changedFiles = 3, configIssues = issues, language = ReviewLanguage.FR),
            )

        summary shouldContain "### Revue FirstPass"
        summary shouldContain "3 problèmes trouvés : 1 majeur, 2 mineurs."
        summary shouldContain "- Relus : 2 fichiers"
        summary shouldContain "- Ignorés : `yarn.lock` (fichier de verrouillage)"
        summary shouldContain "- `langue` n'est pas un réglage FirstPass et a été ignoré"
        summary shouldContain "FirstPass (IA) · gemini-3.5-flash-lite · 1 min 12 s · FirstPass se contente de commenter"
    }

    @Test
    fun `gives the time in seconds under a minute, and leaves the model out when none answered`() {
        val summary =
            SummaryFormatter.summary(
                input(report(usage = emptyList()), changedFiles = 1).copy(duration = 4.6.seconds),
            )

        summary shouldContain "<sub>FirstPass (AI) · 4 s · FirstPass only comments"
        SummaryFormatter.summary(input(report(), changedFiles = 1).copy(duration = 0.2.seconds)) shouldContain
            " · 1 s · "
    }

    private fun report(
        inline: List<io.github.yassineh04.firstpass.core.finding.Finding> = emptyList(),
        summary: List<io.github.yassineh04.firstpass.core.finding.Finding> = emptyList(),
        modelSummaries: List<String> = emptyList(),
        skipped: List<SkippedFile> = emptyList(),
        notReviewed: List<NotReviewedFile> = emptyList(),
        truncated: List<TruncatedHunk> = emptyList(),
        failed: List<FailedBatch> = emptyList(),
        usage: List<ProviderUsage> = this.usage,
    ) = ReviewReport(
        inline,
        summary,
        modelSummaries,
        skipped,
        notReviewed,
        truncated,
        failed,
        usage,
        rejected = 0,
        promptVersion = "1",
    )

    private fun input(
        report: ReviewReport,
        changedFiles: Int,
        maxComments: Int = 10,
        configIssues: List<ConfigIssue> = emptyList(),
        language: ReviewLanguage = ReviewLanguage.EN,
    ) = SummaryInput(report, changedFiles, maxComments, configIssues, 72.seconds, language, URL)
}
