package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.store.InstallationRecord
import io.github.yassineh04.firstpass.core.store.InstallationStore
import io.github.yassineh04.firstpass.core.store.RepositoryRecord
import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job

/**
 * Keeps installations and their repositories up to date (FR-01 to FR-03): a new public repository
 * is reviewed at once, a private one only after an admin's consent, and an uninstalled app or a
 * removed repository leaves no data behind (FR-02). Every change can be replayed safely.
 */
class InstallationJobHandler(
    private val installations: InstallationStore,
) {
    /** Returns how many repositories were recorded or purged. */
    fun handle(job: Job.Installation): Int =
        when (job.change) {
            InstallationChange.CREATED -> {
                save(job, suspended = false)
                add(job)
            }

            InstallationChange.SUSPENDED -> {
                save(job, suspended = true)
                0
            }

            InstallationChange.UNSUSPENDED -> {
                save(job, suspended = false)
                0
            }

            InstallationChange.REPOSITORIES_ADDED -> {
                add(job)
            }

            InstallationChange.REPOSITORIES_REMOVED -> {
                purge(job.installationId, job.repositories.map { it.id })
            }

            InstallationChange.DELETED -> {
                delete(job)
            }
        }

    private fun save(
        job: Job.Installation,
        suspended: Boolean,
    ) {
        val login = checkNotNull(job.accountLogin) { "An installation event always names its account" }
        val type = checkNotNull(job.accountType) { "An installation event always gives its account type" }
        installations.saveInstallation(InstallationRecord(job.installationId, login, type, suspended))
    }

    private fun add(job: Job.Installation): Int =
        job.repositories.count {
            installations.addRepository(
                RepositoryRecord(job.installationId, it, enabled = !it.private, consentAt = null),
            )
        }

    /**
     * The installation goes first, so that reviews stop at once; a replayed job still finds the
     * repositories left to purge.
     */
    private fun delete(job: Job.Installation): Int {
        installations.deleteInstallation(job.installationId)
        val recorded = installations.repositories(job.installationId).map { it.repository.id }
        return purge(job.installationId, (recorded + job.repositories.map { it.id }).distinct())
    }

    private fun purge(
        installationId: Long,
        repoIds: List<Long>,
    ): Int {
        repoIds.forEach { installations.purgeRepository(installationId, it) }
        return repoIds.size
    }
}
