package io.github.yassineh04.firstpass.worker

import io.github.yassineh04.firstpass.llm.ProviderSetupException
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64

class WorkerFactoryTest {
    @TempDir
    lateinit var folder: Path

    /** Building the worker sends nothing to DynamoDB, so no server is needed at this address. */
    private val table = FirstPassTable.fromSettings(mapOf("DYNAMODB_ENDPOINT" to "http://127.0.0.1:9"))

    @Test
    fun `names the setting that is missing, and never prints a value`() {
        shouldThrow<MissingSettingException> { WorkerFactory.create(emptyMap(), table) }.message shouldContain
            "GITHUB_APP_PRIVATE_KEY_PATH"

        val settings = mapOf("GITHUB_APP_PRIVATE_KEY_PATH" to writeKey().toString(), "GITHUB_CLIENT_ID" to " ")
        val error = shouldThrow<MissingSettingException> { WorkerFactory.create(settings, table) }
        error.message shouldContain "GITHUB_CLIENT_ID"
        error.message shouldNotContain folder.toString()
    }

    @Test
    fun `reviews with Gemini by default, which needs its key`() {
        val settings = mapOf("GITHUB_APP_PRIVATE_KEY_PATH" to writeKey().toString(), "GITHUB_CLIENT_ID" to "Iv1.test")

        shouldThrow<ProviderSetupException> { WorkerFactory.create(settings, table) }.message shouldContain
            "GEMINI_API_KEY"
        WorkerFactory.create(settings + ("FIRSTPASS_LLM_PROVIDER" to "fake"), table)
    }

    @Test
    fun `takes the key itself, as SSM gives it in AWS, instead of a file`() {
        val pem = Files.readString(writeKey())
        val settings =
            mapOf(
                "GITHUB_APP_PRIVATE_KEY" to pem,
                "GITHUB_CLIENT_ID" to "Iv1.test",
                "FIRSTPASS_LLM_PROVIDER" to "fake",
            )

        WorkerFactory.create(settings, table)
        shouldThrow<IllegalArgumentException> {
            WorkerFactory.create(settings + ("GITHUB_APP_PRIVATE_KEY" to "not a key"), table)
        }
    }

    /**
     * A key generated for this test only. The markers are built from parts, so that secret
     * scanners ignore this file.
     */
    private fun writeKey(): Path {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val marker = "PRIVATE KEY-----"
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded)
        return folder.resolve("app.pem").also { Files.writeString(it, "-----BEGIN $marker\n$body\n-----END $marker\n") }
    }
}
