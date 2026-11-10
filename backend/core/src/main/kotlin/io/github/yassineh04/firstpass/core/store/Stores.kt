package io.github.yassineh04.firstpass.core.store

import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.review.ProviderUsage
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import java.time.Instant
import java.time.LocalDate

/** An installation of the app on an account (design section 8). */
data class InstallationRecord(
    val installationId: Long,
    val accountLogin: String,
    val accountType: String,
    val suspended: Boolean,
)

/**
 * A repository an installation may read. A private repository is created disabled; only an admin's
 * recorded consent ([consentAt]) lets it be reviewed (FR-03).
 */
data class RepositoryRecord(
    val installationId: Long,
    val repository: RepositoryRef,
    val enabled: Boolean,
    val consentAt: Instant?,
) {
    val reviewable: Boolean get() = enabled && (!repository.private || consentAt != null)
}

/** What FirstPass remembers about one pull request between jobs. */
data class PullRequestState(
    val repoId: Long,
    val number: Int,
    val lastReviewedSha: String? = null,
    val paused: Boolean = false,
    val fingerprints: Set<String> = emptySet(),
    val lastQuotaNoticeDay: LocalDate? = null,
    val tooLargeNoticePosted: Boolean = false,
    val closedAt: Instant? = null,
)

interface InstallationStore {
    fun installation(installationId: Long): InstallationRecord?

    fun repository(
        installationId: Long,
        repoId: Long,
    ): RepositoryRecord?

    /** Every repository recorded for [installationId] (design 8, access pattern 1). */
    fun repositories(installationId: Long): List<RepositoryRecord>

    /** Creates or replaces the installation itself; its repositories are left as they are. */
    fun saveInstallation(installation: InstallationRecord)

    /** Records [repository] unless it is already known, so that a recorded consent is never lost. False when known. */
    fun addRepository(repository: RepositoryRecord): Boolean

    /**
     * Deletes the repository and everything stored about it: dashboard settings, pull request
     * states, review runs and their findings (FR-02). Nothing happens for an unknown repository.
     */
    fun purgeRepository(
        installationId: Long,
        repoId: Long,
    )

    /** Deletes the installation itself; its repositories are purged one by one with [purgeRepository]. */
    fun deleteInstallation(installationId: Long)
}

interface PullRequestStateStore {
    fun state(
        repoId: Long,
        number: Int,
    ): PullRequestState?

    fun save(state: PullRequestState)
}

/** The settings an admin saved in the dashboard (phase 4); null when there are none. */
fun interface DashboardSettingsStore {
    fun settings(repoId: Long): SettingsOverrides?
}

interface UsageStore {
    /** Counts one review of [installationId] on [day] if fewer than [limit] were counted; false otherwise. Atomic. */
    fun reserveReview(
        installationId: Long,
        day: String,
        limit: Int,
    ): Boolean

    /** Model calls made on [day] by every installation. */
    fun globalLlmCalls(day: String): Int

    /** Tokens (input and output) each provider used on [day], by provider name. */
    fun providerTokens(day: String): Map<String, Int>

    /** Adds the calls and tokens of one review to the counters of [day]. */
    fun addConsumption(
        installationId: Long,
        day: String,
        usage: List<ProviderUsage>,
    )
}

/** How a review job ended, once its check run existed. */
enum class RunStatus {
    PUBLISHED,
    NOTHING_NEW,
    PUBLISH_REJECTED,
    UNAVAILABLE,
    QUOTA_REACHED,
    OUTDATED,
}

/**
 * One review run (design section 8). Its id is the delivery id and its place in the history the
 * time the webhook was received ([receivedAt]): both stay the same when the job is replayed, so a
 * replayed job overwrites its run instead of adding one.
 */
data class ReviewRunRecord(
    val runId: String,
    val repoId: Long,
    val pullRequest: Int,
    val headSha: String,
    val trigger: ReviewTrigger,
    val status: RunStatus,
    val receivedAt: Instant,
    val startedAt: Instant,
    val durationMs: Long,
    val usage: List<ProviderUsage>,
    val filesReviewed: Int,
    val filesSkipped: Int,
    val findingsPosted: Int,
    val findingsSummarized: Int,
)

/** A published finding, inline or in the summary. */
data class FindingRecord(
    val runId: String,
    val index: Int,
    val finding: Finding,
    val inline: Boolean,
)

fun interface ReviewRunStore {
    fun save(
        run: ReviewRunRecord,
        findings: List<FindingRecord>,
    )
}
