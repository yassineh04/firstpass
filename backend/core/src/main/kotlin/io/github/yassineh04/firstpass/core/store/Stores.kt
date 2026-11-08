package io.github.yassineh04.firstpass.core.store

import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
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
}
