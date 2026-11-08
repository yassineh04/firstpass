package io.github.yassineh04.firstpass.core.webhook

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The webhook secret shared with GitHub; never printed. */
class WebhookSecret(
    value: String,
) {
    internal val bytes: ByteArray = value.toByteArray(Charsets.UTF_8)

    init {
        require(value.isNotBlank()) { "The webhook secret cannot be blank" }
    }

    override fun toString(): String = "WebhookSecret(****)"
}

/**
 * GitHub signs each delivery with HMAC-SHA256 of the body and the webhook secret, sent as
 * `X-Hub-Signature-256: sha256=<hex>`. The check runs on the raw body, before any parsing, and
 * compares in constant time.
 */
object WebhookSignature {
    private const val PREFIX = "sha256="
    private const val ALGORITHM = "HmacSHA256"
    private val HEX = HexFormat.of()

    fun isValid(
        secret: WebhookSecret,
        body: ByteArray,
        header: String?,
    ): Boolean {
        val received = header?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let(::hexOrNull) ?: return false
        return MessageDigest.isEqual(sign(secret, body), received)
    }

    /** The header GitHub would send for [body]; used by tests and local tools. */
    fun header(
        secret: WebhookSecret,
        body: ByteArray,
    ): String = PREFIX + HEX.formatHex(sign(secret, body))

    private fun sign(
        secret: WebhookSecret,
        body: ByteArray,
    ): ByteArray = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.bytes, ALGORITHM)) }.doFinal(body)

    /** Upper or lower case; anything that is not hexadecimal is not a signature. */
    private fun hexOrNull(text: String): ByteArray? = runCatching { HEX.parseHex(text) }.getOrNull()
}
