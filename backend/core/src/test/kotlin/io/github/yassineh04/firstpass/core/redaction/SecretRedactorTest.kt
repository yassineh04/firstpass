package io.github.yassineh04.firstpass.core.redaction

import io.github.yassineh04.firstpass.core.diff.AnnotatedFile
import io.github.yassineh04.firstpass.core.diff.DiffAnnotator
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Fake secrets are assembled from pieces at run time, so that the secret scanner of the CI
 * (gitleaks) does not see a complete secret in this source file.
 */
class SecretRedactorTest {
    private fun join(vararg parts: String) = parts.joinToString("")

    private val awsKey = join("AK", "IA", "Z".repeat(16))
    private val githubToken = join("gh", "p_", "a1B2".repeat(9))
    private val fineGrainedToken = join("github", "_pat_", "x9".repeat(15))
    private val slackToken = join("xo", "xb-", "1234567890-abcdefghij")
    private val jwt = join("ey", "JhbGciOiJIUzI1NiJ9", ".", "ey", "JzdWIiOiIxMjMifQ", ".", "c2lnbmF0dXJlMTIz")
    private val password = join("Sup3r", "Secret", "Value!9")
    private val passwordKey = join("pass", "word")

    private fun annotated(vararg lines: String): AnnotatedFile {
        val patch = "@@ -0,0 +1,${lines.size} @@\n" + lines.joinToString("\n") { "+$it" }
        return DiffAnnotator.annotate(DiffFile(PATH, FileStatus.ADDED, patch)).shouldNotBeNull()
    }

    private fun contents(file: AnnotatedFile) = file.hunks.flatMap { hunk -> hunk.lines.map { it.content } }

    @Test
    fun `redacts an AWS access key and reports where it is, without the value`() {
        val result = SecretRedactor.redact(annotated("val key = \"$awsKey\""))

        result.file.render() shouldNotContain awsKey
        result.file.file.patch
            .shouldNotBeNull()
            .shouldNotContain(awsKey)
        result.file.render() shouldContain "val key = \"[REDACTED:aws-access-key]\""
        result.secrets shouldContainExactly listOf(DetectedSecret(PATH, 1, SecretType.AWS_ACCESS_KEY))
    }

    @Test
    fun `redacts GitHub and Slack tokens`() {
        val result =
            SecretRedactor.redact(
                annotated("a = \"$githubToken\"", "b = \"$fineGrainedToken\"", "c = \"$slackToken\""),
            )

        contents(result.file) shouldContainExactly
            listOf(
                "a = \"[REDACTED:github-token]\"",
                "b = \"[REDACTED:github-token]\"",
                "c = \"[REDACTED:slack-token]\"",
            )
        result.secrets.map { it.line to it.type } shouldContainExactly
            listOf(1 to SecretType.GITHUB_TOKEN, 2 to SecretType.GITHUB_TOKEN, 3 to SecretType.SLACK_TOKEN)
    }

    @Test
    fun `redacts a JSON web token`() {
        val result = SecretRedactor.redact(annotated("headers[\"Authorization\"] = \"Bearer $jwt\""))

        contents(result.file) shouldContainExactly listOf("headers[\"Authorization\"] = \"Bearer [REDACTED:jwt]\"")
        result.secrets.map { it.type } shouldContainExactly listOf(SecretType.JWT)
    }

    @Test
    fun `redacts the credentials of a connection string but keeps the scheme and the host`() {
        val result = SecretRedactor.redact(annotated("url = \"postgres://admin:$password@db.example.com:5432/app\""))

        contents(result.file) shouldContainExactly
            listOf("url = \"postgres://[REDACTED:connection-string]@db.example.com:5432/app\"")
        result.secrets.map { it.type } shouldContainExactly listOf(SecretType.CONNECTION_STRING)
    }

    @Test
    fun `redacts the value assigned to a password, secret, API key or token, and keeps its name`() {
        val result =
            SecretRedactor.redact(
                annotated(
                    "$passwordKey = \"$password\"",
                    "\"client_${join("sec", "ret")}\": \"$password\",",
                    "${join("API", "_KEY")}=${join("abc123", "XYZ789", "def456")}",
                    "val access${join("To", "ken")} = '$password'",
                ),
            )

        contents(result.file) shouldContainExactly
            listOf(
                "$passwordKey = \"[REDACTED:assigned-secret]\"",
                "\"client_${join("sec", "ret")}\": \"[REDACTED:assigned-secret]\",",
                "${join("API", "_KEY")}=[REDACTED:assigned-secret]",
                "val access${join("To", "ken")} = '[REDACTED:assigned-secret]'",
            )
        result.secrets.map { it.line to it.type } shouldContainExactly
            (1..4).map { it to SecretType.ASSIGNED_SECRET }
    }

    @Test
    fun `leaves short values, placeholders and code expressions alone`() {
        val lines =
            arrayOf(
                "$passwordKey = \"short\"",
                "$passwordKey = \"\${DB_PASSWORD}\"",
                "apiKey: \"<your-api-key-here>\"",
                "$passwordKey = config.getPassword()",
                "token = \"xxxxxxxxxxxxxxxx\"",
                "\"$passwordKey\": \"Mot de passe oublie\"",
                "tokenizer = \"whitespace-tokenizer-v2\"",
            )

        val result = SecretRedactor.redact(annotated(*lines))

        contents(result.file) shouldContainExactly lines.toList()
        result.secrets.shouldBeEmpty()
    }

    @Test
    fun `redacts a whole private key block and reports it once, at its first line`() {
        val begin = join("-----BEGIN ", "RSA PRIVATE ", "KEY-----")
        val end = join("-----END ", "RSA PRIVATE ", "KEY-----")

        val result =
            SecretRedactor.redact(
                annotated("val pem = \"\"\"", begin, "MIIEowIBAAKCAQEAx3", "zJ9fQ2bW", end, "\"\"\"", "val after = 1"),
            )

        contents(result.file) shouldContainExactly
            listOf(
                "val pem = \"\"\"",
                "[REDACTED:private-key]",
                "[REDACTED:private-key]",
                "[REDACTED:private-key]",
                "[REDACTED:private-key]",
                "\"\"\"",
                "val after = 1",
            )
        result.secrets shouldContainExactly listOf(DetectedSecret(PATH, 2, SecretType.PRIVATE_KEY))
    }

    @Test
    fun `redacts context and removed lines too, but reports only added lines`() {
        val patch = "@@ -1,2 +1,2 @@\n old = \"$awsKey\"\n-removed = \"$githubToken\"\n+added = \"$slackToken\""
        val file = DiffAnnotator.annotate(DiffFile(PATH, FileStatus.MODIFIED, patch)).shouldNotBeNull()

        val result = SecretRedactor.redact(file)

        listOf(awsKey, githubToken, slackToken).forEach { result.file.render() shouldNotContain it }
        result.secrets shouldContainExactly listOf(DetectedSecret(PATH, 2, SecretType.SLACK_TOKEN))
    }

    @Test
    fun `leaves a file without secrets unchanged`() {
        val file = annotated("fun greet(name: String) = \"Hello \$name\"")

        val result = SecretRedactor.redact(file)

        result.file.render() shouldBe file.render()
        result.secrets.shouldBeEmpty()
    }

    @Test
    fun `redacts free text such as a pull request description`() {
        val description = "Rotated the key $awsKey.\nTesting with $jwt"

        SecretRedactor.redactText(description) shouldBe
            "Rotated the key [REDACTED:aws-access-key].\nTesting with [REDACTED:jwt]"
    }

    private companion object {
        const val PATH = "src/Config.kt"
    }
}
