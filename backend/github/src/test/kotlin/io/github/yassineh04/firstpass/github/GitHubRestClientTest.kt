package io.github.yassineh04.firstpass.github

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.yassineh04.firstpass.core.diff.DiffFile
import io.github.yassineh04.firstpass.core.diff.FileStatus
import io.github.yassineh04.firstpass.core.github.CheckConclusion
import io.github.yassineh04.firstpass.core.github.CheckRunState
import io.github.yassineh04.firstpass.core.github.InlineComment
import io.github.yassineh04.firstpass.core.github.PullRequestDetails
import io.github.yassineh04.firstpass.core.github.ReviewSubmission
import io.github.yassineh04.firstpass.core.publish.CheckRunText
import io.github.yassineh04.firstpass.core.settings.ConfigProblem
import io.github.yassineh04.firstpass.core.settings.ReviewLanguage
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.Base64

class GitHubRestClientTest {
    private val server = WireMockServer(options().dynamicPort())
    private val sandbox = RepositoryRef(7, "octo/sandbox", private = false)
    private lateinit var client: GitHubRestClient

    @BeforeEach
    fun start() {
        server.start()
        val clock = MovableClock(Instant.parse("2026-11-08T10:00:00Z"))
        val http = GitHubHttp(URI.create(server.baseUrl()), clock, { })
        client =
            GitHubRestClient(
                http,
                InstallationTokens(http, AppCredentials("Iv1.test-client", TestKeys.pair.private), clock),
            )
        server.stubFor(
            post("/app/installations/42/access_tokens")
                .willReturn(
                    aResponse()
                        .withStatus(
                            201,
                        ).withBody("""{"token":"ghs_test","expires_at":"2026-11-08T11:00:00Z"}"""),
                ),
        )
    }

    @AfterEach
    fun stop() = server.stop()

    @Test
    fun `reads a pull request as the installation`() {
        val pullRequest =
            """
            {"number": 3, "title": "Add a lookup", "body": null, "state": "closed", "draft": true,
             "user": {"login": "octocat", "type": "User"}, "additions": 10, "deletions": 5, "changed_files": 2,
             "head": {"ref": "feature", "sha": "head1"}, "base": {"ref": "main", "sha": "base1"}}
            """.trimIndent()
        server.stubFor(get("/repos/octo/sandbox/pulls/3").willReturn(aResponse().withBody(pullRequest)))

        client.pullRequest(42, sandbox, 3) shouldBe
            PullRequestDetails(
                3,
                "Add a lookup",
                null,
                false,
                true,
                "octocat",
                "User",
                10,
                5,
                2,
                "head1",
                "main",
                "base1",
            )
        server.verify(
            getRequestedFor(
                urlEqualTo("/repos/octo/sandbox/pulls/3"),
            ).withHeader("Authorization", equalTo("Bearer ghs_test")),
        )
    }

    @Test
    fun `reads every changed file across pages, with its status, patch and previous name`() {
        val base = server.baseUrl()
        val first =
            """
            [{"filename": "src/A.kt", "status": "modified", "patch": "@@ -1 +1 @@\n-a\n+b"},
             {"filename": "src/New.kt", "status": "renamed", "previous_filename": "src/Old.kt"}]
            """.trimIndent()
        val second =
            """
            [{"filename": "logo.png", "status": "added"}, {"filename": "src/B.kt", "status": "changed", "patch": "@@ -1 +1 @@\n-c\n+d"},
             {"filename": "src/Gone.kt", "status": "removed"}, {"filename": "src/Copy.kt", "status": "copied"}]
            """.trimIndent()
        server.stubFor(
            get("/repos/octo/sandbox/pulls/3/files?per_page=100")
                .willReturn(
                    aResponse()
                        .withHeader(
                            "link",
                            "<$base/repos/octo/sandbox/pulls/3/files?per_page=100&page=2>; rel=\"next\"",
                        ).withBody(first),
                ),
        )
        server.stubFor(
            get("/repos/octo/sandbox/pulls/3/files?per_page=100&page=2").willReturn(aResponse().withBody(second)),
        )

        client.pullRequestFiles(42, sandbox, 3) shouldContainExactly
            listOf(
                DiffFile("src/A.kt", FileStatus.MODIFIED, "@@ -1 +1 @@\n-a\n+b"),
                DiffFile("src/New.kt", FileStatus.RENAMED, null, previousPath = "src/Old.kt"),
                DiffFile("logo.png", FileStatus.ADDED, null),
                DiffFile("src/B.kt", FileStatus.MODIFIED, "@@ -1 +1 @@\n-c\n+d"),
                DiffFile("src/Gone.kt", FileStatus.REMOVED, null),
                DiffFile("src/Copy.kt", FileStatus.COPIED, null),
            )
    }

    @Test
    fun `reads the settings file of the base branch, or nothing when there is none`() {
        val yaml = Base64.getMimeEncoder().encodeToString("language: fr\n".toByteArray())
        server.stubFor(
            get(urlPathEqualTo("/repos/octo/sandbox/contents/.firstpass.yml"))
                .willReturn(
                    aResponse().withBody("""{"type": "file", "encoding": "base64", "size": 13, "content": "$yaml"}"""),
                ),
        )

        client
            .repositoryConfig(42, sandbox, "release/v1 #2")
            .shouldNotBeNull()
            .overrides.language shouldBe
            ReviewLanguage.FR
        server.verify(getRequestedFor(urlEqualTo("/repos/octo/sandbox/contents/.firstpass.yml?ref=release%2Fv1+%232")))

        server.stubFor(
            get(urlPathEqualTo("/repos/octo/sandbox/contents/.firstpass.yml")).willReturn(aResponse().withStatus(404)),
        )
        client.repositoryConfig(42, sandbox, "main").shouldBeNull()
    }

    @Test
    fun `reports a settings path that is a folder, or a file too large to be settings`() {
        val path = urlPathEqualTo("/repos/octo/sandbox/contents/.firstpass.yml")

        server.stubFor(get(path).willReturn(aResponse().withBody("""[{"type": "file", "name": "a.yml"}]""")))
        val folder = client.repositoryConfig(42, sandbox, "main").shouldNotBeNull()
        folder.issues.map { it.problem } shouldBe listOf(ConfigProblem.INVALID_FILE)

        val large = """{"type": "file", "encoding": "none", "size": 2000000, "content": ""}"""
        server.stubFor(get(path).willReturn(aResponse().withBody(large)))
        val tooLarge = client.repositoryConfig(42, sandbox, "main").shouldNotBeNull()
        tooLarge.issues.map { it.problem } shouldBe listOf(ConfigProblem.INVALID_FILE)
    }

    @Test
    fun `creates the FirstPass check run in progress, then completes it`() {
        server.stubFor(
            post("/repos/octo/sandbox/check-runs").willReturn(aResponse().withStatus(201).withBody("""{"id": 4242}""")),
        )
        server.stubFor(
            patch("/repos/octo/sandbox/check-runs/4242").willReturn(aResponse().withStatus(200).withBody("{}")),
        )

        client.createCheckRun(
            42,
            sandbox,
            "head1",
            CheckRunState.InProgress,
            CheckRunText("Review in progress", "Reviewing."),
        ) shouldBe
            4242
        client.updateCheckRun(
            42,
            sandbox,
            4242,
            CheckRunState.Completed(CheckConclusion.NEUTRAL),
            CheckRunText("Found 1 issue", "1 major."),
        )

        val created =
            """
            {"name": "FirstPass", "head_sha": "head1", "status": "in_progress",
             "output": {"title": "Review in progress", "summary": "Reviewing."}}
            """.trimIndent()
        val completed =
            """
            {"status": "completed", "conclusion": "neutral",
             "output": {"title": "Found 1 issue", "summary": "1 major."}}
            """.trimIndent()
        server.verify(
            postRequestedFor(urlEqualTo("/repos/octo/sandbox/check-runs")).withRequestBody(equalToJson(created)),
        )
        server.verify(
            patchRequestedFor(
                urlEqualTo("/repos/octo/sandbox/check-runs/4242"),
            ).withRequestBody(equalToJson(completed)),
        )
    }

    @Test
    fun `cuts a check run summary to the 65535 characters GitHub accepts`() {
        server.stubFor(
            post("/repos/octo/sandbox/check-runs").willReturn(aResponse().withStatus(201).withBody("""{"id": 1}""")),
        )

        client.createCheckRun(
            42,
            sandbox,
            "head1",
            CheckRunState.Completed(CheckConclusion.SUCCESS),
            CheckRunText("Done", "x".repeat(70_000)),
        )

        val sent =
            JSON.readTree(
                server.findAll(postRequestedFor(urlEqualTo("/repos/octo/sandbox/check-runs"))).single().bodyAsString,
            )
        sent.text("conclusion") shouldBe "success"
        sent.text("output.summary").length shouldBe 65_535
    }

    @Test
    fun `posts a comment on the pull request conversation`() {
        server.stubFor(
            post("/repos/octo/sandbox/issues/3/comments").willReturn(aResponse().withStatus(201).withBody("{}")),
        )

        client.postComment(42, sandbox, 3, "Too large to review.")

        server.verify(
            postRequestedFor(
                urlEqualTo("/repos/octo/sandbox/issues/3/comments"),
            ).withRequestBody(equalToJson("""{"body": "Too large to review."}""")),
        )
    }

    @Test
    fun `publishes a review of type COMMENT only, with single and multi-line comments on the new side`() {
        server.stubFor(
            post(
                "/repos/octo/sandbox/pulls/3/reviews",
            ).willReturn(aResponse().withStatus(200).withBody("""{"id": 1}""")),
        )
        val review =
            ReviewSubmission(
                commitId = "head1",
                body = "### FirstPass review",
                comments =
                    listOf(
                        InlineComment("src/A.kt", 12, null, "One line."),
                        InlineComment("src/A.kt", 20, 18, "Three lines."),
                    ),
            )

        client.publishReview(42, sandbox, 3, review) shouldBe true

        val expected =
            """
            {"commit_id": "head1", "body": "### FirstPass review", "event": "COMMENT",
             "comments": [
               {"path": "src/A.kt", "line": 12, "side": "RIGHT", "body": "One line."},
               {"path": "src/A.kt", "line": 20, "side": "RIGHT", "start_line": 18, "start_side": "RIGHT", "body": "Three lines."}
             ]}
            """.trimIndent()
        server.verify(
            postRequestedFor(urlEqualTo("/repos/octo/sandbox/pulls/3/reviews")).withRequestBody(equalToJson(expected)),
        )
    }

    @Test
    fun `answers false when GitHub refuses the inline comments, and fails on any other error`() {
        val review = ReviewSubmission("head1", "Body", listOf(InlineComment("src/A.kt", 99, null, "Outside the diff.")))

        server.stubFor(
            post(
                "/repos/octo/sandbox/pulls/3/reviews",
            ).willReturn(aResponse().withStatus(422).withBody("""{"message": "Unprocessable Entity"}""")),
        )
        client.publishReview(42, sandbox, 3, review) shouldBe false

        server.stubFor(
            post(
                "/repos/octo/sandbox/pulls/3/reviews",
            ).willReturn(aResponse().withStatus(404).withBody("""{"message": "Not Found"}""")),
        )
        shouldThrow<GitHubApiException> { client.publishReview(42, sandbox, 3, review) }.status shouldBe 404
        shouldThrow<GitHubApiException> { client.pullRequest(42, sandbox, 9) }
    }
}
