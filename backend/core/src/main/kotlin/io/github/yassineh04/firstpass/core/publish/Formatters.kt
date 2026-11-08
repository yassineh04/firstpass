package io.github.yassineh04.firstpass.core.publish

import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.prompt.PromptBuilder
import io.github.yassineh04.firstpass.core.publish.Markdown.code
import io.github.yassineh04.firstpass.core.publish.Markdown.escapeOutsideCode
import io.github.yassineh04.firstpass.core.publish.Markdown.html
import io.github.yassineh04.firstpass.core.publish.Markdown.number
import io.github.yassineh04.firstpass.core.review.ReviewReport
import io.github.yassineh04.firstpass.core.settings.ConfigIssue
import io.github.yassineh04.firstpass.core.settings.ConfigProblem
import io.github.yassineh04.firstpass.core.settings.Expectation
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import kotlin.time.Duration

/** One inline review comment (design 7.7): severity and category, title, explanation, suggestion, footer. */
object CommentFormatter {
    fun inline(
        finding: Finding,
        model: String,
        language: ReviewLanguage,
        transparencyUrl: String,
    ): String {
        val texts = PublishTexts.of(language)
        val header =
            "**${texts.severity.getValue(finding.severity)} · ${texts.category.getValue(finding.category)}**" +
                "${texts.colon} ${escapeOutsideCode(finding.title)}"
        // A code fence inside the suggestion would close the block early: the suggestion is dropped, the comment kept.
        val suggestion =
            finding.suggestion
                ?.trimEnd('\n')
                ?.takeUnless { FENCE in it }
                ?.let { "${FENCE}suggestion\n$it\n$FENCE" }
        val link = "[${texts.howItWorks}]($transparencyUrl)"
        val footer = "<sub>${texts.aiName} · $model · ${texts.reactions} · $link</sub>"
        return listOfNotNull(header, escapeOutsideCode(finding.body), suggestion, footer).joinToString("\n\n")
    }

    private const val FENCE = "```"
}

/** Everything the body of the review needs besides the findings posted inline. */
data class SummaryInput(
    val report: ReviewReport,
    val changedFiles: Int,
    val maxComments: Int,
    val configIssues: List<ConfigIssue>,
    val duration: Duration,
    val language: ReviewLanguage,
    val transparencyUrl: String,
)

/** The body of the review: what was found, what was not reviewed and why, and who reviewed. */
object SummaryFormatter {
    private const val MAX_LISTED_FILES = 20
    private const val MAX_LISTED_FINDINGS = 25

    fun summary(input: SummaryInput): String {
        val texts = PublishTexts.of(input.language)
        val report = input.report
        return listOfNotNull(
            "### ${texts.reviewTitle}",
            headline(report.inline + report.summary, texts),
            overview(report.modelSummaries, texts),
            others(report.summary, input.maxComments, texts),
            files(input, texts),
            ConfigIssueTexts.section(input.configIssues, texts),
            footer(input, texts),
        ).joinToString("\n\n")
    }

    private fun headline(
        findings: List<Finding>,
        texts: PublishTexts,
    ): String =
        if (findings.isEmpty()) {
            texts.noIssues
        } else {
            texts.found(findings.size, Markdown.issues(findings.size, texts), Markdown.breakdown(findings, texts))
        }

    private fun overview(
        summaries: List<String>,
        texts: PublishTexts,
    ): String? {
        val text =
            when (summaries.size) {
                0 -> return null
                1 -> escapeOutsideCode(summaries.single())
                else -> summaries.joinToString("\n") { "- ${escapeOutsideCode(it)}" }
            }
        return "**${texts.overview}**\n\n$text"
    }

    /** Findings not posted inline, each in a details block that opens on its explanation. */
    private fun others(
        findings: List<Finding>,
        maxComments: Int,
        texts: PublishTexts,
    ): String? {
        if (findings.isEmpty()) return null
        val blocks = findings.take(MAX_LISTED_FINDINGS).map { details(it, texts) }
        val hidden = findings.size - MAX_LISTED_FINDINGS
        val more =
            listOfNotNull(texts.more(number(hidden, texts)).replaceFirstChar(Char::uppercase).takeIf { hidden > 0 })
        val intro = listOf("**${texts.others}**", texts.othersExplained(number(maxComments, texts)))
        return (intro + blocks + more).joinToString("\n\n")
    }

    private fun details(
        finding: Finding,
        texts: PublishTexts,
    ): String {
        val severity = texts.severity.getValue(finding.severity)
        val category = texts.category.getValue(finding.category)
        val location = html(Markdown.location(finding))
        val line = "$severity · $category · <code>$location</code> · ${html(finding.title)}"
        return "<details><summary>$line</summary>\n\n${escapeOutsideCode(finding.body)}\n\n</details>"
    }

    private fun files(
        input: SummaryInput,
        texts: PublishTexts,
    ): String {
        val report = input.report
        val failed =
            report.failedBatches.flatMap { batch ->
                batch.paths.map { "${code(it)} (${texts.failure.getValue(batch.reason)})" }
            }
        val notReviewedWhole = report.notReviewed.count { !it.partial }
        val failedPaths =
            report.failedBatches
                .flatMap { it.paths }
                .distinct()
                .size
        val reviewed = (input.changedFiles - report.skipped.size - notReviewedWhole - failedPaths).coerceAtLeast(0)
        val lines =
            listOfNotNull(
                line(texts.reviewed, "${number(reviewed, texts)} ${texts.file.of(reviewed)}", texts),
                report.skipped
                    .map { "${code(it.path)} (${texts.skipReason.getValue(it.reason)})" }
                    .listed(texts.skipped, texts),
                report.notReviewed
                    .map { if (it.partial) "${code(it.path)} (${texts.partly})" else code(it.path) }
                    .listed(texts.notReviewed, texts),
                report.truncated
                    .map { it.path }
                    .distinct()
                    .map(::code)
                    .listed(texts.truncated, texts),
                failed.listed(texts.failed, texts),
            )
        return "**${texts.files}**\n\n" + lines.joinToString("\n")
    }

    private fun List<String>.listed(
        label: String,
        texts: PublishTexts,
    ): String? = takeIf { it.isNotEmpty() }?.let { line(label, Markdown.list(it, MAX_LISTED_FILES, texts), texts) }

    private fun line(
        label: String,
        value: String,
        texts: PublishTexts,
    ) = "- $label${texts.colon} $value"

    private fun footer(
        input: SummaryInput,
        texts: PublishTexts,
    ): String {
        val models =
            input.report.usage
                .mapNotNull { it.model }
                .distinct()
                .joinToString(", ")
        val parts =
            listOf(texts.aiName, models, Markdown.duration(input.duration), texts.commentsOnly).filter {
                it.isNotEmpty()
            }
        return "<sub>${parts.joinToString(" · ")} · [${texts.howItWorks}](${input.transparencyUrl})</sub>"
    }
}

/** The settings-file section of the summary: one line per problem, without repeating the invalid value. */
internal object ConfigIssueTexts {
    fun section(
        issues: List<ConfigIssue>,
        texts: PublishTexts,
    ): String? =
        issues.takeIf { it.isNotEmpty() }?.let {
            "**${texts.configTitle}**\n\n${texts.configIntro}\n\n" +
                it.joinToString("\n") { issue -> "- ${text(issue, texts)}" }
        }

    private fun text(
        issue: ConfigIssue,
        texts: PublishTexts,
    ): String {
        val key = code(issue.setting.orEmpty())
        return when (issue.problem) {
            ConfigProblem.INVALID_FILE -> texts.invalidFile + (issue.detail?.let { "${texts.colon} ${code(it)}" } ?: "")
            ConfigProblem.UNSUPPORTED_VERSION -> texts.unsupportedVersion
            ConfigProblem.UNKNOWN_SETTING -> texts.unknownSetting(key)
            ConfigProblem.INVALID_VALUE -> texts.invalidValue(key, expectation(issue.expected, texts))
            ConfigProblem.TOO_LONG -> texts.tooLongInstructions(number(PromptBuilder.MAX_INSTRUCTIONS, texts))
        }
    }

    private fun expectation(
        expected: Expectation?,
        texts: PublishTexts,
    ): String =
        when (expected) {
            is Expectation.OneOf -> {
                texts.oneOf(expected.values.joinToString(", ") { code(it) })
            }

            is Expectation.ListOf -> {
                texts.listOf(expected.values.joinToString(", ") { code(it) })
            }

            is Expectation.WholeNumber -> {
                texts.wholeNumber(number(expected.range.first, texts), number(expected.range.last, texts))
            }

            Expectation.TrueOrFalse -> {
                texts.trueOrFalse
            }

            Expectation.Patterns -> {
                texts.patterns
            }

            Expectation.Text, null -> {
                texts.text
            }
        }
}

/** The comment posted instead of a review when a pull request is above the size limit (FR-09). */
object Notices {
    fun tooLarge(
        changedLines: Int,
        limit: Int,
        language: ReviewLanguage,
    ): String {
        val texts = PublishTexts.of(language)
        return texts.tooLarge(number(changedLines, texts), number(limit, texts))
    }
}

data class CheckRunText(
    val title: String,
    val summary: String,
)

/** Texts of the FirstPass check run. Its conclusion is success or neutral, never failure (FR-13). */
object CheckRunTexts {
    const val NAME = "FirstPass"

    fun inProgress(language: ReviewLanguage): CheckRunText =
        PublishTexts.of(language).let { CheckRunText(it.inProgressTitle, it.inProgressSummary) }

    fun completed(
        findings: List<Finding>,
        language: ReviewLanguage,
    ): CheckRunText {
        val texts = PublishTexts.of(language)
        return if (findings.isEmpty()) {
            CheckRunText(texts.noIssuesTitle, texts.noIssuesSummary)
        } else {
            CheckRunText(
                texts.foundTitle(findings.size, Markdown.issues(findings.size, texts)),
                texts.foundSummary(Markdown.breakdown(findings, texts)),
            )
        }
    }

    fun unavailable(language: ReviewLanguage): CheckRunText =
        PublishTexts.of(language).let { CheckRunText(it.unavailableTitle, it.unavailableSummary) }

    /** A newer push arrived during the review: this head is left to the job of that push. */
    fun outdated(language: ReviewLanguage): CheckRunText =
        PublishTexts.of(language).let { CheckRunText(it.outdatedTitle, it.outdatedSummary) }
}
