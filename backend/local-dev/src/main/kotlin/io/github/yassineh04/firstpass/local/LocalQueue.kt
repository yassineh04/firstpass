package io.github.yassineh04.firstpass.local

import io.github.yassineh04.firstpass.core.job.JobHandler
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.JobPublisher
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The local stand-in for the SQS queue: jobs run one at a time on a worker thread, so that the
 * webhook is answered at once, as in production. A failed job is logged and not retried; GitHub
 * can redeliver its webhook from the app settings.
 */
internal class LocalQueue(
    private val jobs: JobHandler,
    private val log: (String) -> Unit,
) : JobPublisher,
    AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "firstpass-worker") }

    override fun publish(job: Job): Boolean =
        try {
            worker.execute { run(job) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    /** Waits until every job published so far has finished. */
    fun awaitIdle(timeout: Duration) {
        worker.submit {}.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun close() {
        worker.shutdown()
        worker.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    // The worker thread must survive any failure of a job, whatever its type.
    @Suppress("TooGenericExceptionCaught")
    private fun run(job: Job) {
        try {
            log("Job ${describe(job)}: ${jobs.handle(job)}")
        } catch (error: Exception) {
            log("Job ${describe(job)} failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private companion object {
        const val STOP_TIMEOUT_SECONDS = 30L

        /** Commits are shown by their first 7 characters, as GitHub does. */
        const val SHORT_SHA = 7

        /** What identifies a job in the log: never code, a secret or a token. */
        fun describe(job: Job): String {
            val what =
                when (job) {
                    is Job.Review -> "review of ${job.repository.fullName}#${job.pullRequest} at ${short(job.headSha)}"
                    is Job.PullRequestClosed -> "closing of ${job.repository.fullName}#${job.pullRequest}"
                    is Job.Installation -> "installation ${job.installationId} ${job.change}"
                }
            return "delivery ${job.deliveryId}, $what"
        }

        private fun short(sha: String) = sha.take(SHORT_SHA)
    }
}
