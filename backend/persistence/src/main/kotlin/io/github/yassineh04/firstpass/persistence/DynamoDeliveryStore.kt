package io.github.yassineh04.firstpass.persistence

import io.github.yassineh04.firstpass.core.webhook.DeliveryStore
import java.time.Duration
import java.time.Instant

/** The webhook deliveries already received, kept 3 days (design 8, access pattern 8). */
class DynamoDeliveryStore(
    private val table: FirstPassTable,
) : DeliveryStore {
    /** A delivery that expired but that TTL has not deleted yet no longer counts as received. */
    override fun markReceived(
        deliveryId: String,
        event: String,
        receivedAt: Instant,
    ): Boolean {
        val item =
            key(deliveryId) +
                mapOf(
                    "event" to Values.s(event),
                    "receivedAt" to Values.time(receivedAt),
                    Attr.EXPIRES_AT to Values.expiry(receivedAt.plus(KEPT)),
                )
        return table.putIfAbsentOrExpired(item, now = receivedAt)
    }

    override fun forget(deliveryId: String) = table.delete(key(deliveryId))

    private fun key(deliveryId: String) = Keys.of("DELIVERY#$deliveryId", Keys.META)

    private companion object {
        val KEPT: Duration = Duration.ofDays(3)
    }
}
