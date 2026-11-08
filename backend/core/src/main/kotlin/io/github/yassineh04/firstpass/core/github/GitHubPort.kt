package io.github.yassineh04.firstpass.core.github

import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.review.PullRequestFacts
import io.github.yassineh04.firstpass.core.settings.ParsedConfig
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef

/** A pull request as the REST API returns it, with what the review needs. */
data class PullRequestDetails(
    val number: Int,
    val title: String,
    val body: String?,
    val open: Boolean,
    val draft: Boolean,
    val authorLogin: String,
    val authorType: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val headSha: String,
    val baseRef: String,
    val baseSha: String,
) {
    fun facts(): PullRequestFacts = PullRequestFacts(draft, authorLogin, authorType, additions, deletions)
}

/** FirstPass never concludes a check run with failure: it never blocks a merge (FR-13). */
enum class CheckConclusion {
    SUCCESS,
    NEUTRAL,
}

sealed interface CheckRunState {
    data object InProgress : CheckRunState

    data class Completed(
        val conclusion: CheckConclusion,
    ) : CheckRunState
}

/**
 * What the review needs from GitHub, for one installation. The github adapter gets and caches the
 * installation token itself: core never sees a token.
 */
interface GitHubPort {
    fun pullRequest(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): PullRequestDetails

    /** `.firstpass.yml` on [ref] (the base branch), already read; null when the repository has none. */
    fun repositoryConfig(
        installationId: Long,
        repository: RepositoryRef,
        ref: String,
    ): ParsedConfig?

    /** Creates the FirstPass check run on [headSha] and returns its id. */
    fun createCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        headSha: String,
        state: CheckRunState,
        text: CheckRunText,
    ): Long

    /** A plain comment on the pull request conversation, for notices. */
    fun postComment(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        body: String,
    )
}
