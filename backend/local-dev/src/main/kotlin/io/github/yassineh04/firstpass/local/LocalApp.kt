package io.github.yassineh04.firstpass.local

import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.webhook.WebhookSecret
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import io.github.yassineh04.firstpass.webhook.IntakeFactory
import io.github.yassineh04.firstpass.worker.MissingSettingException
import io.github.yassineh04.firstpass.worker.WorkerFactory
import java.time.Clock
import java.time.Duration

/**
 * FirstPass on this machine (design 13): the webhook intake and the review worker of production,
 * wired to DynamoDB Local, with a local server instead of the function URL and an in-memory queue
 * instead of SQS.
 */
class LocalApp private constructor(
    private val server: LocalServer,
    private val queue: LocalQueue,
) : AutoCloseable {
    val port: Int get() = server.port

    /** Waits until every job received so far has finished. */
    fun awaitIdle(timeout: Duration) = queue.awaitIdle(timeout)

    override fun close() {
        server.close()
        queue.close()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        private const val SECRET = "GITHUB_WEBHOOK_SECRET"

        /**
         * Creates the table if needed, records the app's installations, then starts the server on
         * [port] (0 for any free port). [providers] replaces the providers of the settings, for tests.
         */
        fun start(
            settings: Map<String, String>,
            port: Int = DEFAULT_PORT,
            log: (String) -> Unit = ::println,
            clock: Clock = Clock.systemUTC(),
            providers: List<ReviewProvider>? = null,
        ): LocalApp {
            val secret = settings[SECRET]?.takeIf { it.isNotBlank() } ?: throw MissingSettingException(SECRET)
            val table = FirstPassTable.fromSettings(settings).also { it.createIfMissing() }
            val worker = WorkerFactory.create(settings, table, clock, providers)
            InstallationSync(worker, clock).run().forEach { (id, repositories) ->
                log("Installation $id recorded with $repositories new repositories")
            }
            val queue = LocalQueue(worker.jobs, log)
            val intake = IntakeFactory.create(WebhookSecret(secret), table, queue, clock)
            return LocalApp(LocalServer(intake, port, log), queue)
        }
    }
}
