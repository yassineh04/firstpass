package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.quota.QuotaLimits
import io.github.yassineh04.firstpass.core.quota.QuotaPolicy
import io.github.yassineh04.firstpass.core.review.LlmPort
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.store.RunStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewJobHandlerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private val github = FakeGitHub()
    private val stores = MemoryStores()
    private val quota = QuotaPolicy(QuotaLimits(), clock)
    private val unused =
        ReviewProvider(
            "gemini",
            LlmPort { error("No file, so no model call") },
            ProviderBudget(20_000, 6),
            Int.MAX_VALUE,
        )
    private val handler =
        ReviewJobHandler(
            ReviewJobPreparer(github, stores, stores, stores, stores, quota, clock),
            ReviewJobRunner(github, listOf(unused), stores, stores, stores, quota, clock, "https://example.org"),
        )

    @Test
    fun `stops a job the preparation refuses, and runs the review of the others`() {
        stores.states[7L to 3] = PullRequestState(7, 3, paused = true)
        handler.handle(reviewJob()) shouldBe ReviewJobResult.Stopped(StopReason.PAUSED)

        stores.states.clear()
        // No file to review: the review completes without any model call.
        handler.handle(reviewJob()) shouldBe ReviewJobResult.Finished(RunStatus.PUBLISHED)
    }
}
