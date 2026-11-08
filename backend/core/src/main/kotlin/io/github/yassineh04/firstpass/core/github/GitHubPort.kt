package io.github.yassineh04.firstpass.core.github

import io.github.yassineh04.firstpass.core.diff.DiffFile
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

    /** Every changed file of the pull request, with its patch when GitHub gives one. */
    fun pullRequestFiles(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
    ): List<DiffFile>

    fun updateCheckRun(
        installationId: Long,
        repository: RepositoryRef,
        checkRunId: Long,
        state: CheckRunState,
        text: CheckRunText,
    )

    /**
     * Publishes one review of type COMMENT, the only type FirstPass ever sends (FR-10): the port
     * has no way to approve or to request changes. False when GitHub refuses the inline comments
     * (HTTP 422), so that the caller can publish again without them.
     */
    fun publishReview(
        installationId: Long,
        repository: RepositoryRef,
        number: Int,
        review: ReviewSubmission,
    ): Boolean
}

/** One inline comment on the new side of the diff; [startLine] is set for a comment on several lines. */
data class InlineComment(
    val path: String,
    val line: Int,
    val startLine: Int?,
    val body: String,
)

/** A review on [commitId], the head that was reviewed. */
data class ReviewSubmission(
    val commitId: String,
    val body: String,
    val comments: List<InlineComment>,
)
