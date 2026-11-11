package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.finding.Category
import io.github.yassineh04.firstpass.core.finding.Finding
import io.github.yassineh04.firstpass.core.finding.Severity
import io.github.yassineh04.firstpass.core.settings.SettingsOverrides
import io.github.yassineh04.firstpass.core.store.FindingRecord
import io.github.yassineh04.firstpass.core.store.InstallationRecord
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.RepositoryRecord
import io.github.yassineh04.firstpass.core.store.ReviewRunRecord
import io.github.yassineh04.firstpass.core.store.RunStatus
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

class DynamoInstallationStoreTest {
    private val table = DynamoLocal.freshTable()
    private val store = DynamoInstallationStore(table)
    private val sandbox = RepositoryRef(7, "octo/sandbox", private = false)
    private val secret = RepositoryRef(8, "octo/secret", private = true)

    @Test
    fun `saves an installation and replaces it, for example when it is suspended`() {
        store.installation(42).shouldBeNull()

        store.saveInstallation(InstallationRecord(42, "octo", "Organization", suspended = false))
        store.saveInstallation(InstallationRecord(42, "octo", "Organization", suspended = true))

        store.installation(42) shouldBe InstallationRecord(42, "octo", "Organization", suspended = true)
    }

    @Test
    fun `adds a repository only once, so that a recorded consent is never lost`() {
        val consented = RepositoryRecord(42, secret, enabled = true, consentAt = T0)

        store.addRepository(consented) shouldBe true
        store.addRepository(RepositoryRecord(42, secret, enabled = false, consentAt = null)) shouldBe false

        store.repository(42, 8) shouldBe consented
        store.repository(43, 8).shouldBeNull()
    }

    @Test
    fun `lists the repositories of one installation, and finds a repository by its id through GSI1`() {
        store.saveInstallation(InstallationRecord(42, "octo", "User", suspended = false))
        store.addRepository(RepositoryRecord(42, sandbox, enabled = true, consentAt = null))
        store.addRepository(RepositoryRecord(42, secret, enabled = false, consentAt = null))
        store.addRepository(
            RepositoryRecord(43, RepositoryRef(9, "other/repo", false), enabled = true, consentAt = null),
        )

        store.repositories(42).map { it.repository } shouldContainExactlyInAnyOrder listOf(sandbox, secret)
        val byId =
            DynamoLocal.client
                .query {
                    it
                        .tableName(table.name)
                        .indexName("GSI1")
                        .keyConditionExpression("gsi1pk = :id")
                        .expressionAttributeValues(mapOf(":id" to Values.s("REPO#8")))
                }.items()
        byId.single().string("fullName") shouldBe "octo/secret"
    }

    @Test
    fun `purges a repository with its settings, pull request states, runs and findings, and nothing else`() {
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val settings = DynamoSettingsStore(table)
        val states = DynamoPullRequestStateStore(table, clock)
        val runs = DynamoReviewRunStore(table)
        listOf(sandbox, RepositoryRef(9, "octo/kept", false)).forEach {
            store.addRepository(RepositoryRecord(42, it, enabled = true, consentAt = null))
            settings.save(it.id, SettingsOverrides(maxComments = 3))
            states.save(PullRequestState(it.id, 1, lastReviewedSha = "head1"))
        }
        states.save(PullRequestState(7, 2))
        // 31 findings across two runs: the deletions need more than one batch of 25.
        runs.save(run("run-a", repoId = 7), findings("run-a", 20))
        runs.save(run("run-b", repoId = 7), findings("run-b", 11))
        runs.save(run("run-c", repoId = 9), findings("run-c", 2))

        store.purgeRepository(42, 7)

        store.repository(42, 7).shouldBeNull()
        table.items("REPO#7").shouldBeEmpty()
        table.items("RUN#run-a").shouldBeEmpty()
        table.items("RUN#run-b").shouldBeEmpty()
        store.repository(42, 9).shouldNotBeNull()
        table.items("REPO#9") shouldHaveSize 3
        table.items("RUN#run-c") shouldHaveSize 2
    }

    @Test
    fun `deletes an installation without touching its repositories, which are purged one by one`() {
        store.saveInstallation(InstallationRecord(42, "octo", "User", suspended = false))
        store.addRepository(RepositoryRecord(42, sandbox, enabled = true, consentAt = null))

        store.deleteInstallation(42)

        store.installation(42).shouldBeNull()
        store.repositories(42) shouldHaveSize 1
    }

    private fun run(
        runId: String,
        repoId: Long,
    ) = ReviewRunRecord(
        runId = runId,
        repoId = repoId,
        pullRequest = 1,
        headSha = "head1",
        trigger = ReviewTrigger.OPENED,
        status = RunStatus.PUBLISHED,
        receivedAt = T0,
        startedAt = T0.plusSeconds(2),
        durationMs = 1500,
        usage = emptyList(),
        filesReviewed = 1,
        filesSkipped = 0,
        findingsPosted = 1,
        findingsSummarized = 0,
    )

    private fun findings(
        runId: String,
        count: Int,
    ) = List(count) { index ->
        val finding =
            Finding(
                "src/A.kt",
                null,
                index + 1,
                Severity.MINOR,
                Category.BUG,
                "Title $index",
                "Body",
                null,
                0.9,
                "f$index",
            )
        FindingRecord(runId, index, finding, inline = true)
    }
}
