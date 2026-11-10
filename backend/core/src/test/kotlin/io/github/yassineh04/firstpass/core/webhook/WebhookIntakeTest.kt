package io.github.yassineh04.firstpass.core.webhook

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WebhookIntakeTest {
    private val secret = WebhookSecret("test-webhook-secret")
    private val body = """{"action":"opened"}""".toByteArray()
    private val event =
        WebhookEvent.PullRequest(
            "opened",
            42,
            RepositoryRef(7, "octo/sandbox", false),
            3,
            "head1",
            "base1",
            merged = false,
        )

    private val deliveries = MemoryDeliveries()
    private val published = mutableListOf<Job>()
    private var queueUp = true
    private var parsed: ParsedWebhook = ParsedWebhook.Handled(event)
    private var parserCalls = 0

    private val intake =
        WebhookIntake(
            secret,
            parser = { _, _ ->
                parserCalls++
                parsed
            },
            deliveries = deliveries,
            jobs = { job -> queueUp.also { if (it) published += job } },
            clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00.123456789Z"), ZoneOffset.UTC),
        )

    @Test
    fun `accepts a signed pull request event, remembers the delivery and hands the job over`() {
        intake.receive(request()) shouldBe WebhookOutcome.ACCEPTED

        WebhookOutcome.ACCEPTED.status shouldBe 202
        deliveries.received shouldContainExactly setOf("delivery-1")
        published.single().deliveryId shouldBe "delivery-1"
    }

    @Test
    fun `stamps the job with the reception time, to the millisecond, and records the delivery with it`() {
        intake.receive(request())

        val receivedAt = Instant.parse("2026-11-09T10:00:00.123Z")
        published.single().receivedAt shouldBe receivedAt
        deliveries.receivedAt shouldBe mapOf("delivery-1" to receivedAt)
    }

    @Test
    fun `refuses a delivery whose signature is missing or wrong, before reading it`() {
        intake.receive(request(signature = null)) shouldBe WebhookOutcome.UNAUTHORIZED
        intake.receive(request(signature = "sha256=" + "0".repeat(64))) shouldBe WebhookOutcome.UNAUTHORIZED

        WebhookOutcome.UNAUTHORIZED.status shouldBe 401
        parserCalls shouldBe 0
        deliveries.received.shouldBeEmpty()
        published.shouldBeEmpty()
    }

    @Test
    fun `refuses a delivery without an event name, without a delivery id, or with a broken payload`() {
        intake.receive(request(event = null)) shouldBe WebhookOutcome.BAD_REQUEST
        intake.receive(request(delivery = " ")) shouldBe WebhookOutcome.BAD_REQUEST
        parsed = ParsedWebhook.Malformed("not JSON")
        intake.receive(request()) shouldBe WebhookOutcome.BAD_REQUEST

        published.shouldBeEmpty()
    }

    @Test
    fun `answers 200 without storing anything for events and actions FirstPass does not handle`() {
        parsed = ParsedWebhook.Unhandled
        intake.receive(request(event = "ping")) shouldBe WebhookOutcome.IGNORED
        parsed = ParsedWebhook.Handled(event.copy(action = "labeled"))
        intake.receive(request()) shouldBe WebhookOutcome.IGNORED

        WebhookOutcome.IGNORED.status shouldBe 200
        deliveries.received.shouldBeEmpty()
        published.shouldBeEmpty()
    }

    @Test
    fun `hands a delivery over only once, however many times GitHub sends it`() {
        intake.receive(request()) shouldBe WebhookOutcome.ACCEPTED
        intake.receive(request()) shouldBe WebhookOutcome.DUPLICATE

        published.size shouldBe 1
    }

    @Test
    fun `forgets the delivery when the job cannot be handed over, so that a manual redelivery works`() {
        queueUp = false
        intake.receive(request()) shouldBe WebhookOutcome.FAILED

        WebhookOutcome.FAILED.status shouldBe 500
        deliveries.received.shouldBeEmpty()
        queueUp = true
        intake.receive(request()) shouldBe WebhookOutcome.ACCEPTED
    }

    @Test
    fun `reads the headers whatever their case`() {
        val headers =
            mapOf(
                "x-hub-signature-256" to WebhookSignature.header(secret, body),
                "X-GITHUB-EVENT" to "pull_request",
                "x-github-delivery" to "delivery-1",
            )

        intake.receive(WebhookRequest(headers, body)) shouldBe WebhookOutcome.ACCEPTED
    }

    private fun request(
        signature: String? = WebhookSignature.header(secret, body),
        event: String? = "pull_request",
        delivery: String? = "delivery-1",
    ) = WebhookRequest(
        listOfNotNull(
            signature?.let { "X-Hub-Signature-256" to it },
            event?.let { "X-GitHub-Event" to it },
            delivery?.let { "X-GitHub-Delivery" to it },
        ).toMap(),
        body,
    )

    private class MemoryDeliveries : DeliveryStore {
        val receivedAt = mutableMapOf<String, Instant>()
        val received: Set<String> get() = receivedAt.keys

        override fun markReceived(
            deliveryId: String,
            event: String,
            receivedAt: Instant,
        ): Boolean = this.receivedAt.putIfAbsent(deliveryId, receivedAt) == null

        override fun forget(deliveryId: String) {
            receivedAt.remove(deliveryId)
        }
    }
}
