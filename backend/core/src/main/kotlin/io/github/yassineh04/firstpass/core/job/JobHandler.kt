package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job

/** How a job ended, for the worker's logs. */
sealed interface JobOutcome {
    data class Reviewed(
        val result: ReviewJobResult,
    ) : JobOutcome

    /** [repositories] repositories were recorded or purged. */
    data class InstallationUpdated(
        val change: InstallationChange,
        val repositories: Int,
    ) : JobOutcome

    /** [known] is false when FirstPass remembered nothing about the pull request. */
    data class ClosingRecorded(
        val known: Boolean,
    ) : JobOutcome
}

/** The worker's single way into core: each job goes to the handler of its kind. */
class JobHandler(
    private val reviews: ReviewJobHandler,
    private val installations: InstallationJobHandler,
    private val closings: PullRequestClosedHandler,
) {
    fun handle(job: Job): JobOutcome =
        when (job) {
            is Job.Review -> JobOutcome.Reviewed(reviews.handle(job))
            is Job.Installation -> JobOutcome.InstallationUpdated(job.change, installations.handle(job))
            is Job.PullRequestClosed -> JobOutcome.ClosingRecorded(closings.handle(job))
        }
}
