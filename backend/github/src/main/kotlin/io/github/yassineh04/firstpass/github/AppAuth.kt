package io.github.yassineh04.firstpass.github

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Reads the app's private key in PKCS#8 PEM, the format Java reads without extra libraries. */
object PrivateKeys {
    // The markers are built from two parts so that secret scanners do not take this file for a key.
    private const val MARKER = "PRIVATE KEY-----"
    private const val BEGIN = "-----BEGIN $MARKER"
    private const val END = "-----END $MARKER"
    private const val PKCS1 = "-----BEGIN RSA $MARKER"

    fun read(file: Path): PrivateKey = readPkcs8(Files.readString(file))

    fun readPkcs8(pem: String): PrivateKey {
        require(PKCS1 !in pem) {
            "This is a PKCS#1 key, the format GitHub downloads. Convert it once with: " +
                "openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in key.pem -out key.pkcs8.pem"
        }
        val start = pem.indexOf(BEGIN)
        val end = pem.indexOf(END)
        require(start >= 0 && end > start) { "Not a private key in PKCS#8 PEM format" }
        val der = Base64.getMimeDecoder().decode(pem.substring(start + BEGIN.length, end))
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (error: InvalidKeySpecException) {
            throw IllegalArgumentException("Not a valid RSA private key", error)
        }
    }
}

/**
 * What identifies the GitHub App: its Client ID, which GitHub recommends as the JWT issuer,
 * and its private key. Never printed.
 */
class AppCredentials(
    val clientId: String,
    internal val privateKey: PrivateKey,
) {
    init {
        require(clientId.isNotBlank()) { "The Client ID of the app cannot be blank" }
    }

    override fun toString(): String = "AppCredentials(clientId=$clientId, privateKey=****)"
}

/**
 * The JSON Web Token that authenticates the app itself (design 7.1, step 7): RS256, issued 60
 * seconds in the past against clock drift, valid 9 minutes (GitHub allows at most 10).
 */
object AppJwt {
    private val ISSUED_BEFORE: Duration = Duration.ofSeconds(60)
    private val LIFETIME: Duration = Duration.ofMinutes(9)
    private val BASE64_URL = Base64.getUrlEncoder().withoutPadding()
    private const val HEADER = """{"alg":"RS256","typ":"JWT"}"""

    fun create(
        credentials: AppCredentials,
        now: Instant,
    ): String {
        val claims =
            mapOf(
                "iat" to now.minus(ISSUED_BEFORE).epochSecond,
                "exp" to now.plus(LIFETIME).epochSecond,
                "iss" to credentials.clientId,
            )
        val unsigned = encode(HEADER.toByteArray()) + "." + encode(JSON.writeValueAsBytes(claims))
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(credentials.privateKey)
                update(unsigned.toByteArray())
                sign()
            }
        return unsigned + "." + encode(signature)
    }

    private fun encode(bytes: ByteArray): String = BASE64_URL.encodeToString(bytes)
}
