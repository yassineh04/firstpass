package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.store.InstallationRecord
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.RepositoryRecord
import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class InstallationJobHandlerTest {
    private val stores = MemoryStores().apply { installations.clear() }
    private val handler = InstallationJobHandler(stores)
    private val secret = RepositoryRef(8, "octo/secret", private = true)
    private val other = RepositoryRef(9, "octo/other", private = false)

    private fun job(
        change: InstallationChange,
        repositories: List<RepositoryRef> = emptyList(),
        login: String? = "octo",
    ) = Job.Installation("delivery-9", RECEIVED_AT, 42, change, login, login?.let { "Organization" }, repositories)

    @Test
    fun `records a new installation with its repositories, public ones enabled and private ones waiting for consent`() {
        stores.repositories.clear()

        handler.handle(job(InstallationChange.CREATED, listOf(SANDBOX, secret))) shouldBe 2

        stores.installations[42] shouldBe InstallationRecord(42, "octo", "Organization", suspended = false)
        stores.repositories[7] shouldBe RepositoryRecord(42, SANDBOX, enabled = true, consentAt = null)
        stores.repositories[8] shouldBe RepositoryRecord(42, secret, enabled = false, consentAt = null)
        stores.repositories.getValue(8).reviewable shouldBe false
    }

    @Test
    fun `never overwrites a repository already known, so that a replayed event keeps a recorded consent`() {
        val consented = RepositoryRecord(42, secret, enabled = true, consentAt = Instant.parse("2026-11-01T08:00:00Z"))
        stores.repositories[8] = consented

        handler.handle(job(InstallationChange.CREATED, listOf(SANDBOX, secret))) shouldBe 0
        handler.handle(job(InstallationChange.REPOSITORIES_ADDED, listOf(secret, other))) shouldBe 1

        stores.repositories[8] shouldBe consented
        stores.repositories[9] shouldBe RepositoryRecord(42, other, enabled = true, consentAt = null)
    }

    @Test
    fun `suspends and resumes an installation without touching its repositories`() {
        handler.handle(job(InstallationChange.SUSPENDED)) shouldBe 0
        stores.installations.getValue(42).suspended shouldBe true

        handler.handle(job(InstallationChange.UNSUSPENDED)) shouldBe 0
        stores.installations.getValue(42).suspended shouldBe false
        stores.repositories shouldContainKey 7L
        stores.purged.shouldBeEmpty()
    }

    @Test
    fun `purges the repositories removed from an installation, and only them`() {
        stores.repositories[9] = RepositoryRecord(42, other, enabled = true, consentAt = null)
        stores.states[7L to 3] = PullRequestState(7, 3, lastReviewedSha = "head1")
        stores.states[9L to 1] = PullRequestState(9, 1)

        handler.handle(job(InstallationChange.REPOSITORIES_REMOVED, listOf(SANDBOX), login = null)) shouldBe 1

        stores.purged shouldContainExactly listOf(7L)
        stores.repositories.keys shouldContainExactly setOf(9L)
        stores.states.keys shouldContainExactly setOf(9L to 1)
    }

    @Test
    fun `deletes an uninstalled app at once, then purges every repository it had`() {
        stores.saveInstallation(InstallationRecord(42, "octo", "Organization", suspended = false))
        stores.repositories[9] = RepositoryRecord(42, other, enabled = true, consentAt = null)
        stores.dashboardSettings[7] = SettingsOverrides(maxComments = 3)
        // The event can name a repository FirstPass never recorded: it is purged as well.
        val unknown = RepositoryRef(10, "octo/unknown", private = false)

        handler.handle(job(InstallationChange.DELETED, listOf(SANDBOX, unknown))) shouldBe 3

        stores.installations[42].shouldBeNull()
        stores.purged shouldContainExactlyInAnyOrder listOf(7L, 9L, 10L)
        stores.repositories.shouldBeEmpty()
        stores.dashboardSettings.shouldBeEmpty()
    }

    @Test
    fun `stops every review of an installation once the app is uninstalled`() {
        stores.saveInstallation(InstallationRecord(42, "octo", "Organization", suspended = false))
        handler.handle(job(InstallationChange.DELETED))

        val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
        val preparer =
            ReviewJobPreparer(FakeGitHub(), stores, stores, stores, stores, QuotaPolicy(QuotaLimits(), clock), clock)

        preparer.prepare(reviewJob()) shouldBe Preparation.Stopped(StopReason.INSTALLATION_INACTIVE)
    }
}
