package io.github.yassineh04.firstpass.core.webhook

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebhookSignatureTest {
    // Test values published by GitHub in "Validating webhook deliveries".
    private val secret = WebhookSecret("It's a Secret to Everybody")
    private val body = "Hello, World!".toByteArray()
    private val documented = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"

    @Test
    fun `computes and accepts the signature of GitHub's documented example`() {
        WebhookSignature.header(secret, body) shouldBe documented
        WebhookSignature.isValid(secret, body, documented) shouldBe true
        WebhookSignature.isValid(secret, body, documented.uppercase().replace("SHA256=", "sha256=")) shouldBe true
    }

    @Test
    fun `rejects a missing, malformed, legacy or wrong signature`() {
        val digest = documented.removePrefix("sha256=")

        for (header in listOf(
            null,
            "",
            "sha256=",
            "sha1=$digest",
            digest,
            "sha256=${digest.dropLast(2)}",
            "sha256=zz${digest.drop(2)}",
        )) {
            WebhookSignature.isValid(secret, body, header) shouldBe false
        }
        WebhookSignature.isValid(secret, "Hello, World?".toByteArray(), documented) shouldBe false
        WebhookSignature.isValid(WebhookSecret("another secret"), body, documented) shouldBe false
    }

    @Test
    fun `never prints the secret and refuses a blank one`() {
        secret.toString() shouldBe "WebhookSecret(****)"
        shouldThrow<IllegalArgumentException> { WebhookSecret(" ") }
    }
}
