package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.store.PullRequestStateStore
import io.github.yassineh04.firstpass.core.webhook.Job

/**
 * Remembers when a pull request was closed, so that its state expires 90 days later (design 8).
 * The closing time is the reception of the webhook, so a replayed job writes the same state.
 */
class PullRequestClosedHandler(
    private val pullRequests: PullRequestStateStore,
) {
    /** False when FirstPass remembers nothing about the pull request, so there is nothing to expire. */
    fun handle(job: Job.PullRequestClosed): Boolean {
        val state = pullRequests.state(job.repository.id, job.pullRequest) ?: return false
        pullRequests.save(state.copy(closedAt = job.receivedAt))
        return true
    }
}
