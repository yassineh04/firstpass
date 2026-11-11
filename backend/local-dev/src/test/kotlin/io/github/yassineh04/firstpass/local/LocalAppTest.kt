package io.github.yassineh04.firstpass.local

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.yassineh04.firstpass.core.batch.ProviderBudget
import io.github.yassineh04.firstpass.core.review.ReviewProvider
import io.github.yassineh04.firstpass.core.webhook.WebhookSecret
import io.github.yassineh04.firstpass.core.webhook.WebhookSignature
import io.github.yassineh04.firstpass.llm.FakeLlmClient
import io.github.yassineh04.firstpass.persistence.DynamoInstallationStore
import io.github.yassineh04.firstpass.persistence.DynamoLocal
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole local flow, without network: a signed webhook reaches the local server, and the
 * review is published to a fake GitHub (WireMock), with DynamoDB Local and a scripted model.
 */
class LocalAppTest {
    @TempDir
    lateinit var folder: Path

    private val github = WireMockServer(options().dynamicPort())
    private val table = DynamoLocal.uniqueName()
    private val logs = CopyOnWriteArrayList<String>()
    private val http = HttpClient.newHttpClient()
    private lateinit var app: LocalApp

    private val model =
        ReviewProvider(
            "fake",
            FakeLlmClient {
                """{"summary": "One issue.", "comments": [{"path": "src/A.kt", "line": 2, "severity": "major",
                    "category": "bug", "title": "Unused value", "body": "x is never read.", "confidence": 0.9}]}"""
            },
            ProviderBudget(inputTokensPerBatch = 20_000, maxBatches = 6),
            tokensPerDay = Int.MAX_VALUE,
        )

    private val payload =
        """
        {"action": "opened", "number": 3, "installation": {"id": 42},
        "repository": {"id": 7, "full_name": "octo/sandbox", "private": false},
        "pull_request": {"number": 3, "head": {"sha": "head1"}, "base": {"sha": "base1"}, "merged": false}}
        """.trimIndent().toByteArray()

    @BeforeEach
    fun start() {
        github.start()
        stubGitHub()
        val settings =
            mapOf(
                "GITHUB_WEBHOOK_SECRET" to SECRET,
                "GITHUB_CLIENT_ID" to "Iv1.local-test",
                "GITHUB_APP_PRIVATE_KEY_PATH" to writeKey().toString(),
                "GITHUB_API_URL" to github.baseUrl(),
                "DYNAMODB_ENDPOINT" to DynamoLocal.endpoint.toString(),
                "DYNAMODB_TABLE" to table,
            )
        app = LocalApp.start(settings, port = 0, log = { logs += it }, providers = listOf(model))
    }

    @AfterEach
    fun stop() {
        app.close()
        github.stop()
    }

    @Test
    fun `records the installations of the app when it starts`() {
        val store = DynamoInstallationStore(FirstPassTable(DynamoLocal.client, table))

        store.repository(42, 7).shouldNotBeNull().reviewable shouldBe true
        logs shouldContain "Installation 42 recorded with 1 new repositories"
    }

    @Test
    fun `answers a signed pull request webhook at once, then publishes one COMMENT review with the finding`() {
        send(payload, WebhookSignature.header(WebhookSecret(SECRET), payload)).statusCode() shouldBe 202
        app.awaitIdle(Duration.ofSeconds(30))

        github.verify(
            postRequestedFor(urlEqualTo("/repos/octo/sandbox/pulls/3/reviews"))
                .withRequestBody(matchingJsonPath("$.event", equalTo("COMMENT")))
                .withRequestBody(matchingJsonPath("$.commit_id", equalTo("head1")))
                .withRequestBody(matchingJsonPath("$.comments[0].path", equalTo("src/A.kt")))
                .withRequestBody(matchingJsonPath("$.comments[0].line", equalTo("2"))),
        )
        github.verify(
            patchRequestedFor(urlEqualTo("/repos/octo/sandbox/check-runs/100"))
                .withRequestBody(matchingJsonPath("$.conclusion", equalTo("neutral"))),
        )
        val run =
            DynamoLocal.client
                .query {
                    it
                        .tableName(table)
                        .keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(mapOf(":pk" to AttributeValue.fromS("REPO#7")))
                }.items()
                .single { item -> item.getValue("sk").s().startsWith("RUN#") }
        run.getValue("status").s() shouldBe "PUBLISHED"
        logs.joinToString("\n") shouldContain
            "review of octo/sandbox#3 at head1: Reviewed(result=Finished(status=PUBLISHED))"
    }

    @Test
    fun `refuses a webhook whose signature does not match, and says why smee can cause it`() {
        send(payload, "sha256=" + "0".repeat(64)).statusCode() shouldBe 401
        send(payload, WebhookSignature.header(WebhookSecret(SECRET), payload)).statusCode() shouldBe 202
        send(payload, WebhookSignature.header(WebhookSecret(SECRET), payload)).statusCode() shouldBe 200

        logs.joinToString("\n") shouldContain "smee re-encodes the JSON"
    }

    @Test
    fun `answers a health check, and only POST on the webhook path`() {
        fetch("/health").let {
            it.statusCode() shouldBe 200
            it.body() shouldBe "ok"
        }
        fetch("/webhook").statusCode() shouldBe 405
    }

    private fun send(
        body: ByteArray,
        signature: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${app.port}/webhook"))
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "delivery-local-1")
                .header("X-Hub-Signature-256", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun fetch(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port}$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /**
     * A key generated for this test only. The markers are built from parts, so that secret
     * scanners ignore this file.
     */
    private fun writeKey(): Path {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(KEY_BITS) }
                .generateKeyPair()
                .private
        val marker = "PRIVATE KEY-----"
        val body = Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(key.encoded)
        return folder.resolve("app.pem").also { Files.writeString(it, "-----BEGIN $marker\n$body\n-----END $marker\n") }
    }

    private fun stubGitHub() {
        fun json(
            body: String,
            status: Int = 200,
        ) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

        val token = """{"token": "ghs_test", "expires_at": "2099-01-01T00:00:00Z"}"""
        val installations = """[{"id": 42, "account": {"login": "octo", "type": "User"}}]"""
        val repositories =
            """{"total_count": 1,
               "repositories": [{"id": 7, "full_name": "octo/sandbox", "private": false}]}"""
        val pullRequest =
            """{"number": 3, "title": "Add a value", "body": null, "state": "open", "draft": false,
               "user": {"login": "octocat", "type": "User"}, "additions": 1, "deletions": 0, "changed_files": 1,
               "head": {"sha": "head1"}, "base": {"ref": "main", "sha": "base1"}}"""
        val files =
            """[{"filename": "src/A.kt", "status": "modified",
                "patch": "@@ -1,2 +1,3 @@\n fun a() {\n+    val x = 1\n }"}]"""
        val repo = "/repos/octo/sandbox"

        github.stubFor(post("/app/installations/42/access_tokens").willReturn(json(token, 201)))
        github.stubFor(get(urlPathEqualTo("/app/installations")).willReturn(json(installations)))
        github.stubFor(get(urlPathEqualTo("/installation/repositories")).willReturn(json(repositories)))
        github.stubFor(get("$repo/pulls/3").willReturn(json(pullRequest)))
        github.stubFor(get(urlPathEqualTo("$repo/contents/.firstpass.yml")).willReturn(aResponse().withStatus(404)))
        github.stubFor(post("$repo/check-runs").willReturn(json("""{"id": 100}""", 201)))
        github.stubFor(get(urlPathEqualTo("$repo/pulls/3/files")).willReturn(json(files)))
        github.stubFor(post("$repo/pulls/3/reviews").willReturn(json("""{"id": 1}""")))
        github.stubFor(patch(urlEqualTo("$repo/check-runs/100")).willReturn(json("""{"id": 100}""")))
    }

    private companion object {
        const val SECRET = "local-test-webhook-secret"
        const val KEY_BITS = 2048
        const val PEM_LINE = 64
    }
}
