package io.github.yassineh04.firstpass.core.review

import io.github.yassineh04.firstpass.core.batch.NotReviewedFile
import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.batch.TruncatedHunk
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.filter.SkippedFile
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.quota.DailyUsage
import io.github.yassineh04.firstpass.core.quota.QuotaReason
import io.github.yassineh04.firstpass.core.settings.ReviewSettings

/**
 * A model provider as the orchestrator uses it. Providers are given in order of preference;
 * [budget] sizes the batches sent to this provider, and [tokensPerDay] caps what it may use in
 * one UTC day.
 */
data class ReviewProvider(
    val name: String,
    val llm: LlmPort,
    val budget: ProviderBudget,
    val tokensPerDay: Int,
)

/**
 * Everything one review needs, gathered by the caller: the changed files, the effective
 * settings, today's counters, and the fingerprints already published on the pull request.
 */
data class ReviewRequest(
    val pullRequest: PullRequestInfo,
    val files: List<DiffFile>,
    val settings: ReviewSettings = ReviewSettings(),
    val usage: DailyUsage = DailyUsage(installationReviews = 0, globalLlmCalls = 0),
    val providerTokensToday: Map<String, Int> = emptyMap(),
    val alreadyPublished: Set<String> = emptySet(),
)

/**
 * Files that got no valid answer: their batch was dropped after an invalid answer, or no
 * provider could take over after a failure. [detail] is the provider's explanation.
 */
data class FailedBatch(
    val paths: List<String>,
    val provider: String,
    val reason: LlmFailure,
    val detail: String,
)

/** What one provider consumed during the review, for the daily counters. [model] is null when no call succeeded. */
data class ProviderUsage(
    val provider: String,
    val model: String?,
    val calls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
)

/** A finished review: the findings to publish and everything the review summary reports. */
data class ReviewReport(
    val inline: List<Finding>,
    val summary: List<Finding>,
    val modelSummaries: List<String>,
    val skipped: List<SkippedFile>,
    val notReviewed: List<NotReviewedFile>,
    val truncated: List<TruncatedHunk>,
    val failedBatches: List<FailedBatch>,
    val usage: List<ProviderUsage>,
    val rejected: Int,
    val promptVersion: String,
)

sealed interface ReviewOutcome {
    /** At least one batch got a valid answer, or nothing had to be sent to a model. */
    data class Completed(
        val report: ReviewReport,
    ) : ReviewOutcome

    /** Refused before any model call. */
    data class QuotaExceeded(
        val reason: QuotaReason,
    ) : ReviewOutcome

    /** No batch got a valid answer from any provider; [report] still holds the secrets found and the failures. */
    data class Unavailable(
        val report: ReviewReport,
    ) : ReviewOutcome
}
