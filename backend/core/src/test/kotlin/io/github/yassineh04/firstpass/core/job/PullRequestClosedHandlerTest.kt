package io.github.yassineh04.firstpass.core.job

import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.github.yassineh04.firstpass.core.webhook.Job
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PullRequestClosedHandlerTest {
    private val stores = MemoryStores()
    private val handler = PullRequestClosedHandler(stores)
    private val closing = Job.PullRequestClosed("delivery-5", RECEIVED_AT, 42, SANDBOX, pullRequest = 3, merged = true)

    @Test
    fun `remembers when the pull request was closed, keeping everything else`() {
        val state = PullRequestState(7, 3, lastReviewedSha = "head1", fingerprints = setOf("f1"))
        stores.states[7L to 3] = state

        handler.handle(closing) shouldBe true

        stores.states.getValue(7L to 3) shouldBe state.copy(closedAt = RECEIVED_AT)
    }

    @Test
    fun `writes nothing for a pull request FirstPass never reviewed`() {
        handler.handle(closing) shouldBe false

        stores.states.shouldBeEmpty()
    }
}
