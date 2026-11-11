package io.github.yassineh04.firstpass.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class DynamoDeliveryStoreTest {
    private val table = DynamoLocal.freshTable()
    private val store = DynamoDeliveryStore(table)

    @Test
    fun `records a delivery once, and again after a failed hand-off`() {
        store.markReceived("d-1", "pull_request", T0) shouldBe true
        store.markReceived("d-1", "pull_request", T0.plusSeconds(5)) shouldBe false

        store.forget("d-1")

        store.markReceived("d-1", "pull_request", T0.plusSeconds(10)) shouldBe true
    }

    @Test
    fun `keeps the event, the reception time and an expiry three days later`() {
        store.markReceived("d-2", "installation", T0)

        val item = table.items("DELIVERY#d-2").single()
        item.string("sk") shouldBe "META"
        item.string("event") shouldBe "installation"
        item.string("receivedAt") shouldBe "2026-11-09T10:00:00Z"
        item.long("expiresAt") shouldBe T0.plus(Duration.ofDays(3)).epochSecond
    }

    @Test
    fun `takes an expired delivery that TTL has not deleted yet for a new one`() {
        val expiry = T0.plus(Duration.ofDays(3))
        store.markReceived("d-3", "pull_request", T0)

        store.markReceived("d-3", "pull_request", expiry) shouldBe false
        store.markReceived("d-3", "pull_request", expiry.plusSeconds(1)) shouldBe true
    }
}
