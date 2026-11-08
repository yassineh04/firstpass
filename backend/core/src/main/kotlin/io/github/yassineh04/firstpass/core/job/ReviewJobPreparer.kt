package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.github.CheckConclusion
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.GitHubPort
import io.github.yassineh04.firstpass.core.github.PullRequestDetails
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.publish.CheckRunTexts
import io.github.yassineh04.firstpass.core.publish.Notices
import io.github.yassineh04.firstpass.core.quota.QuotaNotice
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.review.PullRequestRules
import io.github.yassineh04.firstpass.core.review.RuleDecision
import io.github.yassineh04.firstpass.core.review.SilentSkip
import io.github.yassineh04.firstpass.core.settings.ConfigIssue
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.settings.ReviewSettings
import io.github.yassineh04.firstpass.core.settings.SettingsResolver
import io.github.yassineh04.firstpass.core.store.DashboardSettingsStore
import io.github.yassineh04.firstpass.core.store.InstallationStore
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.PullRequestStateStore
import io.github.yassineh04.firstpass.core.store.UsageStore
import io.github.yassineh04.firstpass.core.webhook.Job
import java.time.Clock
import java.time.Instant

/** Why a review job stops before the review itself. */
enum class StopReason {
    INSTALLATION_INACTIVE,
    REPOSITORY_DISABLED,
    PAUSED,
    ALREADY_REVIEWED,
    CLOSED,
    OUTDATED,
    DISABLED_BY_SETTINGS,
    DRAFT,
    BOT_AUTHOR,
    TOO_LARGE,
    QUOTA_REACHED,
}

/** Everything the review needs once the job is allowed to run and its check run exists. */
data class ReviewContext(
    val job: Job.Review,
    val pullRequest: PullRequestDetails,
    val settings: ReviewSettings,
    val configIssues: List<ConfigIssue>,
    val state: PullRequestState,
    val checkRunId: Long,
    val startedAt: Instant,
)

sealed interface Preparation {
    data class Stopped(
        val reason: StopReason,
    ) : Preparation

    data class Ready(
        val context: ReviewContext,
    ) : Preparation
}

/**
 * Steps 6 to 11 of a review job (design 7.1): what FirstPass knows without calling GitHub, then
 * the pull request and its settings, the rules that skip it, the daily quota and the check run.
 * Nothing that stops the job before the quota uses any of it.
 */
class ReviewJobPreparer(
    private val github: GitHubPort,
    private val installations: InstallationStore,
    private val pullRequests: PullRequestStateStore,
    private val dashboard: DashboardSettingsStore,
    private val usage: UsageStore,
    private val quota: QuotaPolicy,
    private val clock: Clock,
) {
    private val refusal = QuotaRefusal(github, pullRequests, quota)

    fun prepare(job: Job.Review): Preparation {
        val state =
            pullRequests.state(job.repository.id, job.pullRequest)
                ?: PullRequestState(job.repository.id, job.pullRequest)
        val known = knownStop(job, state)
        return if (known != null) Preparation.Stopped(known) else withPullRequest(job, state)
    }

    /** Step 6: what FirstPass knows without calling GitHub, including a replay of a head already reviewed. */
    private fun knownStop(
        job: Job.Review,
        state: PullRequestState,
    ): StopReason? {
        val installation = installations.installation(job.installationId)
        val repository = installations.repository(job.installationId, job.repository.id)
        return when {
            installation == null || installation.suspended -> StopReason.INSTALLATION_INACTIVE
            repository == null || !repository.reviewable -> StopReason.REPOSITORY_DISABLED
            state.paused -> StopReason.PAUSED
            state.lastReviewedSha == job.headSha -> StopReason.ALREADY_REVIEWED
            else -> null
        }
    }

    /** A closed pull request, or one with a newer head, is left to the job of that newer push. */
    private fun withPullRequest(
        job: Job.Review,
        state: PullRequestState,
    ): Preparation {
        val pullRequest = github.pullRequest(job.installationId, job.repository, job.pullRequest)
        val stop =
            when {
                !pullRequest.open -> StopReason.CLOSED
                pullRequest.headSha != job.headSha -> StopReason.OUTDATED
                else -> null
            }
        return if (stop != null) Preparation.Stopped(stop) else withSettings(job, state, pullRequest)
    }

    /** Steps 8 and 9: the settings of the base branch, then the rules that skip the pull request. */
    private fun withSettings(
        job: Job.Review,
        state: PullRequestState,
        pullRequest: PullRequestDetails,
    ): Preparation {
        val config = github.repositoryConfig(job.installationId, job.repository, pullRequest.baseRef)
        val settings = SettingsResolver.resolve(dashboard.settings(job.repository.id), config?.overrides)
        return when (val decision = PullRequestRules.check(pullRequest.facts(), settings)) {
            RuleDecision.Review -> withQuota(job, state, pullRequest, settings, config?.issues.orEmpty())
            is RuleDecision.Skip -> Preparation.Stopped(SKIPS.getValue(decision.reason))
            is RuleDecision.TooLarge -> tooLarge(job, state, decision, settings.language)
        }
    }

    /** One explanatory comment per pull request (FR-09), however many pushes follow. */
    private fun tooLarge(
        job: Job.Review,
        state: PullRequestState,
        decision: RuleDecision.TooLarge,
        language: ReviewLanguage,
    ): Preparation {
        if (!state.tooLargeNoticePosted) {
            val notice = Notices.tooLarge(decision.changedLines, decision.limit, language)
            github.postComment(job.installationId, job.repository, job.pullRequest, notice)
            pullRequests.save(state.copy(tooLargeNoticePosted = true))
        }
        return Preparation.Stopped(StopReason.TOO_LARGE)
    }

    /** Steps 10 and 11: the atomic reservation of one review today, then the check run in progress. */
    private fun withQuota(
        job: Job.Review,
        state: PullRequestState,
        pullRequest: PullRequestDetails,
        settings: ReviewSettings,
        issues: List<ConfigIssue>,
    ): Preparation {
        val reserved =
            usage.reserveReview(
                job.installationId,
                quota.dayKey(),
                quota.limits.reviewsPerInstallationPerDay,
            )
        if (!reserved) {
            refusal.notify(job, state, settings.language, QuotaReason.INSTALLATION_REVIEWS)
            return Preparation.Stopped(StopReason.QUOTA_REACHED)
        }
        val checkRunId =
            github.createCheckRun(
                job.installationId,
                job.repository,
                job.headSha,
                CheckRunState.InProgress,
                CheckRunTexts.inProgress(settings.language),
            )
        return Preparation.Ready(ReviewContext(job, pullRequest, settings, issues, state, checkRunId, clock.instant()))
    }

    private companion object {
        val SKIPS =
            mapOf(
                SilentSkip.DISABLED to StopReason.DISABLED_BY_SETTINGS,
                SilentSkip.DRAFT to StopReason.DRAFT,
                SilentSkip.BOT_AUTHOR to StopReason.BOT_AUTHOR,
            )
    }
}

/**
 * What a reached quota publishes (design 7.9): one comment per pull request and per UTC day, and
 * a neutral check run on the head, so that the author sees why there is no review.
 */
internal class QuotaRefusal(
    private val github: GitHubPort,
    private val pullRequests: PullRequestStateStore,
    private val quota: QuotaPolicy,
) {
    /** Completes [checkRunId] when the review had already started, or creates a completed check run. */
    fun notify(
        job: Job.Review,
        state: PullRequestState,
        language: ReviewLanguage,
        reason: QuotaReason,
        checkRunId: Long? = null,
    ) {
        val comment = QuotaNotice.comment(reason, language)
        if (quota.shouldPostNotice(state.lastQuotaNoticeDay)) {
            github.postComment(job.installationId, job.repository, job.pullRequest, comment)
            pullRequests.save(state.copy(lastQuotaNoticeDay = quota.today()))
        }
        val neutral = CheckRunState.Completed(CheckConclusion.NEUTRAL)
        val text = CheckRunText(QuotaNotice.checkRunTitle(language), comment)
        if (checkRunId == null) {
            github.createCheckRun(job.installationId, job.repository, job.headSha, neutral, text)
        } else {
            github.updateCheckRun(job.installationId, job.repository, checkRunId, neutral, text)
        }
    }
}
