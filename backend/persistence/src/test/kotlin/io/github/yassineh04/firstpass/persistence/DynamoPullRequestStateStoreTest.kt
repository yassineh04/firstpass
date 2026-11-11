package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.store.PullRequestState
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DynamoPullRequestStateStoreTest {
    private val table = DynamoLocal.freshTable()
    private val store = storeAt(T0)

    private fun storeAt(now: Instant) = DynamoPullRequestStateStore(table, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `saves and reads back every field of a pull request state`() {
        val state =
            PullRequestState(
                repoId = 7,
                number = 3,
                lastReviewedSha = "head1",
                paused = true,
                fingerprints = (1..500).map { "%064x".format(it) }.toSet(),
                lastQuotaNoticeDay = LocalDate.parse("2026-11-08"),
                tooLargeNoticePosted = true,
            )

        store.save(state)

        store.state(7, 3) shouldBe state
        store.state(7, 4).shouldBeNull()
    }

    @Test
    fun `stores a state without fingerprints, which DynamoDB cannot hold as an empty set`() {
        store.save(PullRequestState(7, 5))

        store.state(7, 5) shouldBe PullRequestState(7, 5)
        table.items("REPO#7", "PR#5").single() shouldNotContainKey "fingerprints"
    }

    @Test
    fun `lets the state of a closed pull request expire 90 days after the closing`() {
        val closed = PullRequestState(7, 6, lastReviewedSha = "head1", closedAt = T0)
        store.save(closed)
        val expiry = T0.plus(Duration.ofDays(90))

        table.items("REPO#7", "PR#6").single().long("expiresAt") shouldBe expiry.epochSecond
        storeAt(expiry).state(7, 6) shouldBe closed
        storeAt(expiry.plusSeconds(1)).state(7, 6).shouldBeNull()
    }

    @Test
    fun `removes the expiry of a reopened pull request`() {
        store.save(PullRequestState(7, 8, closedAt = T0))
        table.items("REPO#7", "PR#8").single() shouldContainKey "expiresAt"

        store.save(PullRequestState(7, 8))

        table.items("REPO#7", "PR#8").single() shouldNotContainKey "expiresAt"
    }
}
