package io.github.yassineh04.firstpass.github

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

/**
 * The key pair is generated at run time: no key is ever stored in the repository. The PEM markers
 * are built from two parts so that secret scanners do not take this file for a key.
 */
internal object TestKeys {
    const val MARKER = "PRIVATE KEY-----"

    val pair: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    fun pkcs8Pem(lineBreak: String = "\n"): String =
        "-----BEGIN $MARKER$lineBreak" +
            Base64.getMimeEncoder(64, lineBreak.toByteArray()).encodeToString(pair.private.encoded) +
            "$lineBreak-----END $MARKER$lineBreak"
}

class AppAuthTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    @Test
    fun `reads a PKCS#8 key, with Unix or Windows line endings, from text or from a file`(
        @TempDir dir: Path,
    ) {
        PrivateKeys.readPkcs8(TestKeys.pkcs8Pem()) shouldBe TestKeys.pair.private
        PrivateKeys.readPkcs8(TestKeys.pkcs8Pem("\r\n")) shouldBe TestKeys.pair.private
        val file = dir.resolve("app.pkcs8.pem").also { Files.writeString(it, TestKeys.pkcs8Pem()) }
        PrivateKeys.read(file) shouldBe TestKeys.pair.private
    }

    @Test
    fun `explains how to convert the PKCS#1 key GitHub downloads, and refuses anything else`() {
        val pkcs1 = "-----BEGIN RSA ${TestKeys.MARKER}\nAAAA\n-----END RSA ${TestKeys.MARKER}\n"

        shouldThrow<IllegalArgumentException> { PrivateKeys.readPkcs8(pkcs1) }.message shouldContain "openssl pkcs8"
        shouldThrow<IllegalArgumentException> { PrivateKeys.readPkcs8("not a key") }
    }

    @Test
    fun `signs an RS256 token issued a minute ago, valid 9 minutes, with the Client ID as issuer`() {
        val jwt = AppJwt.create(AppCredentials("Iv1.test-client", TestKeys.pair.private), now)
        val (header, payload, signature) = jwt.split(".")
        val decoder = Base64.getUrlDecoder()

        jwt shouldNotContain "="
        JSON.readTree(decoder.decode(header)).let {
            it.text("alg") shouldBe "RS256"
            it.text("typ") shouldBe "JWT"
        }
        JSON.readTree(decoder.decode(payload)).let {
            it.long("iat") shouldBe now.epochSecond - 60
            it.long("exp") shouldBe now.epochSecond + 9 * 60
            it.text("iss") shouldBe "Iv1.test-client"
        }
        Signature
            .getInstance("SHA256withRSA")
            .apply {
                initVerify(TestKeys.pair.public)
                update("$header.$payload".toByteArray())
            }.verify(decoder.decode(signature)) shouldBe true
    }

    @Test
    fun `never prints the private key and needs a Client ID`() {
        AppCredentials("Iv1.test-client", TestKeys.pair.private).toString() shouldBe
            "AppCredentials(clientId=Iv1.test-client, privateKey=****)"
        shouldThrow<IllegalArgumentException> { AppCredentials(" ", TestKeys.pair.private) }
    }
}
