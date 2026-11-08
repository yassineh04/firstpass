package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.github.CheckConclusion
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.GitHubPort
import io.github.yassineh04.firstpass.core.prompt.BoundarySource
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.publish.CheckRunTexts
import io.github.yassineh04.firstpass.core.quota.DailyUsage
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.review.PullRequestInfo
import io.github.yassineh04.firstpass.core.review.ReviewOrchestrator
import io.github.yassineh04.firstpass.core.review.ReviewOutcome
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.review.ReviewReport
import io.github.yassineh04.firstpass.core.review.ReviewRequest
import io.github.yassineh04.firstpass.core.settings.ProviderPreference
import io.github.yassineh04.firstpass.core.store.FindingRecord
import io.github.yassineh04.firstpass.core.store.PullRequestStateStore
import io.github.yassineh04.firstpass.core.store.ReviewRunRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunStore
import io.github.yassineh04.firstpass.core.store.RunStatus
import io.github.yassineh04.firstpass.core.store.UsageStore
import io.github.yassineh04.firstpass.core.webhook.Job
import java.time.Clock
import java.time.Duration

/**
 * Steps 12 to 18 of a review job (design 7.1): the changed files, the review itself, a last check
 * that the head did not move, one COMMENT review, the end of the check run and the records.
 *
 * A later push that finds nothing new publishes no review, only the check run: re-reviewing the
 * whole pull request on each push must not add an empty review each time.
 */
class ReviewJobRunner(
    private val github: GitHubPort,
    private val providers: List<ReviewProvider>,
    private val pullRequests: PullRequestStateStore,
    private val runs: ReviewRunStore,
    private val usage: UsageStore,
    private val quota: QuotaPolicy,
    private val clock: Clock,
    private val transparencyUrl: String,
    private val boundaries: BoundarySource = BoundarySource.SECURE,
) {
    private val refusal = QuotaRefusal(github, pullRequests, quota)
    private val publisher = ReviewPublisher(github, clock, transparencyUrl)

    fun run(context: ReviewContext): RunStatus {
        val job = context.job
        val files = github.pullRequestFiles(job.installationId, job.repository, job.pullRequest)
        val day = quota.dayKey()
        val request =
            ReviewRequest(
                pullRequest = PullRequestInfo(job.pullRequest, context.pullRequest.title, context.pullRequest.body),
                files = files,
                settings = context.settings,
                // The review of the installation was already reserved, atomically, by the preparation.
                usage = DailyUsage(installationReviews = 0, globalLlmCalls = usage.globalLlmCalls(day)),
                providerTokensToday = usage.providerTokens(day),
                alreadyPublished = context.state.fingerprints,
            )
        return when (
            val outcome =
                ReviewOrchestrator(
                    ordered(context.settings.provider),
                    quota,
                    boundaries,
                ).review(request)
        ) {
            is ReviewOutcome.QuotaExceeded -> refuse(context, outcome.reason)
            is ReviewOutcome.Completed -> finish(context, Review(files.size, outcome.report, available = true), day)
            is ReviewOutcome.Unavailable -> finish(context, Review(files.size, outcome.report, available = false), day)
        }
    }

    /** The preferred provider first, the others after it in their usual order. */
    private fun ordered(preference: ProviderPreference): List<ReviewProvider> =
        if (preference == ProviderPreference.AUTO) {
            providers
        } else {
            providers.sortedBy { if (it.name.equals(preference.name, ignoreCase = true)) 0 else 1 }
        }

    private fun refuse(
        context: ReviewContext,
        reason: QuotaReason,
    ): RunStatus {
        refusal.notify(context.job, context.state, context.settings.language, reason, context.checkRunId)
        record(context, RunStatus.QUOTA_REACHED, review = null, published = null)
        return RunStatus.QUOTA_REACHED
    }

    private class Review(
        val changedFiles: Int,
        val report: ReviewReport,
        val available: Boolean,
    ) {
        val findings: List<Finding> get() = report.inline + report.summary
    }

    private fun finish(
        context: ReviewContext,
        review: Review,
        day: String,
    ): RunStatus {
        val job = context.job
        // The model calls are spent, whatever happens next.
        usage.addConsumption(job.installationId, day, review.report.usage)
        val head = github.pullRequest(job.installationId, job.repository, job.pullRequest).headSha
        if (head != job.headSha) {
            conclude(context, CheckConclusion.NEUTRAL, CheckRunTexts.outdated(context.settings.language))
            record(context, RunStatus.OUTDATED, review, published = null)
            return RunStatus.OUTDATED
        }
        val published =
            if (shouldPublish(
                    context,
                    review,
                )
            ) {
                publisher.publish(context, review.changedFiles, review.report)
            } else {
                null
            }
        val status =
            when {
                !review.available -> RunStatus.UNAVAILABLE
                published == null -> RunStatus.NOTHING_NEW
                published.accepted -> RunStatus.PUBLISHED
                else -> RunStatus.PUBLISH_REJECTED
            }
        conclude(context, review, published, status)
        remember(context, review, published, status)
        record(context, status, review, published?.takeIf { it.accepted })
        return status
    }

    /**
     * Always when something was found; on the first review of the pull request, to say what was
     * reviewed; and when the settings file or a batch needs a word. A review without a valid answer
     * only publishes the secrets found without the model.
     */
    private fun shouldPublish(
        context: ReviewContext,
        review: Review,
    ): Boolean {
        val worthAWord =
            context.state.lastReviewedSha == null ||
                context.configIssues.isNotEmpty() ||
                review.report.failedBatches.isNotEmpty()
        return review.findings.isNotEmpty() || (review.available && worthAWord)
    }

    /** Success without any finding, neutral otherwise; never failure (FR-13). */
    private fun conclude(
        context: ReviewContext,
        review: Review,
        published: Published?,
        status: RunStatus,
    ) {
        val language = context.settings.language
        val findings = review.findings
        when (status) {
            RunStatus.UNAVAILABLE -> {
                conclude(context, CheckConclusion.NEUTRAL, CheckRunTexts.unavailable(language))
            }

            // The review could not be published: the check run carries the summary instead.
            RunStatus.PUBLISH_REJECTED -> {
                val title = CheckRunTexts.completed(findings, language).title
                conclude(context, CheckConclusion.NEUTRAL, CheckRunText(title, published?.body.orEmpty()))
            }

            else -> {
                val conclusion = if (findings.isEmpty()) CheckConclusion.SUCCESS else CheckConclusion.NEUTRAL
                conclude(context, conclusion, CheckRunTexts.completed(findings, language))
            }
        }
    }

    private fun conclude(
        context: ReviewContext,
        conclusion: CheckConclusion,
        text: CheckRunText,
    ) = github.updateCheckRun(
        context.job.installationId,
        context.job.repository,
        context.checkRunId,
        CheckRunState.Completed(conclusion),
        text,
    )

    /** The head counts as reviewed only when a review answered and anything to publish was published. */
    private fun remember(
        context: ReviewContext,
        review: Review,
        published: Published?,
        status: RunStatus,
    ) {
        val reviewed = status == RunStatus.PUBLISHED || status == RunStatus.NOTHING_NEW
        val newFingerprints = published?.takeIf { it.accepted }?.let { it.report.inline + it.report.summary }.orEmpty()
        if (!reviewed && newFingerprints.isEmpty()) return
        val state = context.state
        pullRequests.save(
            state.copy(
                lastReviewedSha = if (reviewed && review.available) context.job.headSha else state.lastReviewedSha,
                fingerprints =
                    (
                        state.fingerprints +
                            newFingerprints.map {
                                it.fingerprint
                            }
                    ).toList().takeLast(MAX_FINGERPRINTS).toSet(),
            ),
        )
    }

    private fun record(
        context: ReviewContext,
        status: RunStatus,
        review: Review?,
        published: Published?,
    ) {
        val job: Job.Review = context.job
        val skipped = review?.report?.skipped?.size ?: 0
        val inline = published?.report?.inline.orEmpty()
        val summary = published?.report?.summary.orEmpty()
        val run =
            ReviewRunRecord(
                runId = job.deliveryId,
                repoId = job.repository.id,
                pullRequest = job.pullRequest,
                headSha = job.headSha,
                trigger = job.trigger,
                status = status,
                startedAt = context.startedAt,
                durationMs = Duration.between(context.startedAt, clock.instant()).toMillis(),
                usage = review?.report?.usage.orEmpty(),
                filesReviewed = ((review?.changedFiles ?: 0) - skipped).coerceAtLeast(0),
                filesSkipped = skipped,
                findingsPosted = inline.size,
                findingsSummarized = summary.size,
            )
        val findings =
            inline.map { true to it }.plus(summary.map { false to it }).mapIndexed { index, (isInline, finding) ->
                FindingRecord(job.deliveryId, index, finding, isInline)
            }
        runs.save(run, findings)
    }

    private companion object {
        /** The design keeps at most 500 fingerprints per pull request, the most recent ones. */
        const val MAX_FINGERPRINTS = 500
    }
}

/** How a review job ended: stopped before the review, or finished with a status. */
sealed interface ReviewJobResult {
    data class Stopped(
        val reason: StopReason,
    ) : ReviewJobResult

    data class Finished(
        val status: RunStatus,
    ) : ReviewJobResult
}

/** The whole review job: preparation, then the review when the preparation allows it. */
class ReviewJobHandler(
    private val preparer: ReviewJobPreparer,
    private val runner: ReviewJobRunner,
) {
    fun handle(job: Job.Review): ReviewJobResult =
        when (val preparation = preparer.prepare(job)) {
            is Preparation.Stopped -> ReviewJobResult.Stopped(preparation.reason)
            is Preparation.Ready -> ReviewJobResult.Finished(runner.run(preparation.context))
        }
}
