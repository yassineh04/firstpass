package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.github.GitHubPort
import io.github.yassineh04.firstpass.core.github.InlineComment
import io.github.yassineh04.firstpass.core.github.ReviewSubmission
import io.github.yassineh04.firstpass.core.publish.CommentFormatter
import io.github.yassineh04.firstpass.core.publish.SummaryFormatter
import io.github.yassineh04.firstpass.core.publish.SummaryInput
import io.github.yassineh04.firstpass.core.review.ReviewReport
import java.time.Clock
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * What was published: the report as posted (the inline comments may have moved to the summary),
 * the body of the review, and whether GitHub accepted it.
 */
internal class Published(
    val report: ReviewReport,
    val body: String,
    val accepted: Boolean,
)

/** Step 17: one COMMENT review on the reviewed head, with the summary and the inline comments. */
internal class ReviewPublisher(
    private val github: GitHubPort,
    private val clock: Clock,
    private val transparencyUrl: String,
) {
    /** GitHub refuses the whole review when one inline comment is invalid (HTTP 422): they all move to the summary. */
    fun publish(
        context: ReviewContext,
        changedFiles: Int,
        report: ReviewReport,
    ): Published {
        val first = submission(context, changedFiles, report)
        if (send(context, first)) return Published(report, first.body, accepted = true)
        val moved = report.copy(inline = emptyList(), summary = report.inline + report.summary)
        val second = submission(context, changedFiles, moved)
        return Published(moved, second.body, accepted = send(context, second))
    }

    private fun send(
        context: ReviewContext,
        submission: ReviewSubmission,
    ): Boolean =
        github.publishReview(context.job.installationId, context.job.repository, context.job.pullRequest, submission)

    private fun submission(
        context: ReviewContext,
        changedFiles: Int,
        report: ReviewReport,
    ): ReviewSubmission {
        val language = context.settings.language
        val model =
            report.usage
                .mapNotNull { it.model }
                .distinct()
                .joinToString(", ")
                .ifEmpty { "FirstPass" }
        val duration = Duration.between(context.startedAt, clock.instant()).toKotlinDuration()
        val summary =
            SummaryInput(
                report,
                changedFiles,
                context.settings.maxComments,
                context.configIssues,
                duration,
                language,
                transparencyUrl,
            )
        val comments =
            report.inline.map {
                InlineComment(
                    it.path,
                    it.line,
                    it.startLine,
                    CommentFormatter.inline(it, model, language, transparencyUrl),
                )
            }
        return ReviewSubmission(context.job.headSha, SummaryFormatter.summary(summary), comments)
    }
}
