package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.store.InstallationRecord
import io.github.yassineh04.firstpass.core.store.InstallationStore
import io.github.yassineh04.firstpass.core.store.RepositoryRecord
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef

/**
 * Installations (`INST#{id}`, `META`) and their repositories (`INST#{id}`, `REPO#{repoId}`, also
 * indexed by GSI1 under `REPO#{repoId}`), design 8, access patterns 1 and 2.
 */
class DynamoInstallationStore(
    private val table: FirstPassTable,
) : InstallationStore {
    override fun installation(installationId: Long): InstallationRecord? =
        table.get(installationKey(installationId))?.let {
            InstallationRecord(
                installationId,
                it.string("accountLogin"),
                it.string("accountType"),
                it.flag("suspended"),
            )
        }

    override fun repository(
        installationId: Long,
        repoId: Long,
    ): RepositoryRecord? = table.get(repositoryKey(installationId, repoId))?.let(::repositoryOf)

    override fun repositories(installationId: Long): List<RepositoryRecord> =
        table.query(Keys.installation(installationId), Keys.REPO_PREFIX).map(::repositoryOf)

    override fun saveInstallation(installation: InstallationRecord) =
        table.put(
            installationKey(installation.installationId) +
                mapOf(
                    "accountLogin" to Values.s(installation.accountLogin),
                    "accountType" to Values.s(installation.accountType),
                    "suspended" to Values.bool(installation.suspended),
                ),
        )

    override fun addRepository(repository: RepositoryRecord): Boolean {
        val id = repository.repository.id
        val item =
            buildMap {
                putAll(repositoryKey(repository.installationId, id))
                put(Attr.GSI1_PK, Values.s(Keys.repository(id)))
                put(Attr.GSI1_SK, Values.s(Keys.META))
                put("repoId", Values.n(id))
                put("fullName", Values.s(repository.repository.fullName))
                put("private", Values.bool(repository.repository.private))
                put("enabled", Values.bool(repository.enabled))
                repository.consentAt?.let { put("consentAt", Values.time(it)) }
            }
        return table.putIfAbsent(item)
    }

    /**
     * The findings of each run go first, then everything under `REPO#{repoId}`, and the repository
     * record last: if the purge stops halfway, a replayed job still finds what is left.
     */
    override fun purgeRepository(
        installationId: Long,
        repoId: Long,
    ) {
        val data = table.query(Keys.repository(repoId))
        val findings =
            data
                .filter { it.string(Attr.SK).startsWith(Keys.RUN_PREFIX) }
                .flatMap { table.query(Keys.run(it.string("runId"))) }
        table.writeAll(
            items = emptyList(),
            deletions =
                (findings + data).map { it.key() } + repositoryKey(installationId, repoId),
        )
    }

    override fun deleteInstallation(installationId: Long) = table.delete(installationKey(installationId))

    private fun repositoryOf(item: Item) =
        RepositoryRecord(
            installationId = item.string(Attr.PK).removePrefix("INST#").toLong(),
            repository = RepositoryRef(item.long("repoId"), item.string("fullName"), item.flag("private")),
            enabled = item.flag("enabled"),
            consentAt = item.instantOrNull("consentAt"),
        )

    private fun installationKey(installationId: Long) = Keys.of(Keys.installation(installationId), Keys.META)

    private fun repositoryKey(
        installationId: Long,
        repoId: Long,
    ) = Keys.of(Keys.installation(installationId), Keys.repository(repoId))
}
