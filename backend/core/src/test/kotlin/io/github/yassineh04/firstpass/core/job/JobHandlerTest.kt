package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JobHandlerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private val github = FakeGitHub()
    private val stores = MemoryStores()
    private val quota = QuotaPolicy(QuotaLimits(), clock)
    private val unused =
        ReviewProvider("gemini", LlmPort { error("No model call") }, ProviderBudget(20_000, 6), Int.MAX_VALUE)
    private val handler =
        JobHandler(
            ReviewJobHandler(
                ReviewJobPreparer(github, stores, stores, stores, stores, quota, clock),
                ReviewJobRunner(github, listOf(unused), stores, stores, stores, quota, clock, "https://example.org"),
            ),
            InstallationJobHandler(stores),
            PullRequestClosedHandler(stores),
        )

    @Test
    fun `sends each job to the handler of its kind`() {
        stores.states[7L to 3] = PullRequestState(7, 3, paused = true)
        handler.handle(reviewJob()) shouldBe JobOutcome.Reviewed(ReviewJobResult.Stopped(StopReason.PAUSED))

        val closing = Job.PullRequestClosed("delivery-2", RECEIVED_AT, 42, SANDBOX, pullRequest = 3, merged = false)
        handler.handle(closing) shouldBe JobOutcome.ClosingRecorded(known = true)

        val removal =
            Job.Installation(
                "delivery-3",
                RECEIVED_AT,
                42,
                InstallationChange.REPOSITORIES_REMOVED,
                null,
                null,
                listOf(SANDBOX),
            )
        handler.handle(removal) shouldBe
            JobOutcome.InstallationUpdated(InstallationChange.REPOSITORIES_REMOVED, repositories = 1)
        stores.purged shouldBe listOf(7L)
    }
}
