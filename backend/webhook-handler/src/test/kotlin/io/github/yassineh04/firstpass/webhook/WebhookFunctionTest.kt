package io.github.yassineh04.firstpass.webhook

import io.github.yassineh04.firstpass.aws.JsonLog
import io.github.yassineh04.firstpass.core.webhook.DeliveryStore
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.WebhookIntake
import io.github.yassineh04.firstpass.core.webhook.WebhookSecret
import io.github.yassineh04.firstpass.core.webhook.WebhookSignature
import io.github.yassineh04.firstpass.github.WebhookPayloadParser
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class WebhookFunctionTest {
    private val json = JsonMapper.builder().build()
    private val secret = WebhookSecret("function-test-secret")
    private val published = mutableListOf<Job>()
    private val logs = ByteArrayOutputStream()
    private var intakesBuilt = 0

    private val payload =
        """{"action":"opened","number":3,"installation":{"id":42},
           "repository":{"id":7,"full_name":"octo/sandbox","private":false},
           "pull_request":{"number":3,"head":{"sha":"head1"},"base":{"sha":"base1"},"merged":false}}"""

    private val function =
        WebhookFunction(JsonLog(PrintStream(logs, true, Charsets.UTF_8))) {
            intakesBuilt++
            val deliveries =
                object : DeliveryStore {
                    val seen = mutableSetOf<String>()

                    override fun markReceived(
                        deliveryId: String,
                        event: String,
                        receivedAt: Instant,
                    ) = seen.add(deliveryId)

                    override fun forget(deliveryId: String) {
                        seen.remove(deliveryId)
                    }
                }
            val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
            WebhookIntake(secret, WebhookPayloadParser(), deliveries, { published.add(it) }, clock)
        }

    /** A function URL request in payload format 2.0, as the Lambda documentation shows it. */
    private fun event(
        method: String,
        body: String = "",
        base64: Boolean = false,
        signature: String = WebhookSignature.header(secret, body.toByteArray()),
    ): ByteArray {
        val event =
            json.createObjectNode().apply {
                put("version", "2.0")
                put("routeKey", "\$default")
                put("rawPath", "/")
                put("rawQueryString", "")
                putObject("headers").apply {
                    put("content-type", "application/json")
                    put("x-github-event", "pull_request")
                    put("X-GitHub-Delivery", "d-100")
                    put("x-hub-signature-256", signature)
                }
                putObject("requestContext").putObject("http").put("method", method).put("path", "/")
                put("body", if (base64) Base64.getEncoder().encodeToString(body.toByteArray()) else body)
                put("isBase64Encoded", base64)
            }
        return json.writeValueAsBytes(event)
    }

    private fun call(event: ByteArray): ObjectNode {
        val output = ByteArrayOutputStream()
        function.handleRequest(event.inputStream(), output, null)
        return json.readTree(output.toByteArray()) as ObjectNode
    }

    @Test
    fun `hands a signed delivery to the intake and answers in the function URL format`() {
        val response = call(event("POST", payload))

        response.path("statusCode").intValue() shouldBe 202
        response.path("body").asString() shouldBe "ACCEPTED"
        response.path("isBase64Encoded").booleanValue() shouldBe false
        published.single().deliveryId shouldBe "d-100"
        val line = logs.toString(Charsets.UTF_8)
        line shouldContain "\"event\":\"pull_request\""
        line shouldContain "\"deliveryId\":\"d-100\""
        line shouldNotContain "octo/sandbox"
    }

    @Test
    fun `checks the signature over the decoded bytes of a base64 body`() {
        call(event("POST", payload, base64 = true)).path("statusCode").intValue() shouldBe 202

        call(event("POST", payload, signature = "sha256=" + "0".repeat(64))).path("statusCode").intValue() shouldBe 401
    }

    @Test
    fun `answers the smoke test without reading any secret, and only POST otherwise`() {
        call(event("GET")).let {
            it.path("statusCode").intValue() shouldBe 200
            it.path("body").asString() shouldBe "ok"
        }
        intakesBuilt shouldBe 0

        call(event("PUT")).path("statusCode").intValue() shouldBe 405
        published.shouldBeEmpty()
    }
}
