package io.github.yassineh04.firstpass.local

import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.worker.Worker
import java.time.Clock

/**
 * Local only: records the installations the app has on GitHub, with their repositories, as an
 * `installation created` webhook would. DynamoDB Local runs in memory and forgets them on each
 * restart; in production, the webhooks record them (FR-01).
 */
internal class InstallationSync(
    private val worker: Worker,
    private val clock: Clock,
) {
    /** Returns the number of repositories recorded for each installation id. */
    fun run(): Map<Long, Int> =
        worker.apps.installations().associate { installation ->
            val job =
                Job.Installation(
                    deliveryId = "local-sync-${installation.id}",
                    receivedAt = clock.instant(),
                    installationId = installation.id,
                    change = InstallationChange.CREATED,
                    accountLogin = installation.account,
                    accountType = installation.accountType,
                    repositories = worker.apps.installationRepositories(installation.id),
                )
            installation.id to worker.installations.handle(job)
        }
}
