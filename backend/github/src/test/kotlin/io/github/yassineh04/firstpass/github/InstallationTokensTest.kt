package io.github.yassineh04.firstpass.github

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock the test moves forward. */
internal class MovableClock(
    var now: Instant,
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

class InstallationTokensTest {
    private val server = WireMockServer(options().dynamicPort())
    private val clock = MovableClock(Instant.parse("2026-11-08T10:00:00Z"))
    private val credentials = AppCredentials("Iv1.test-client", TestKeys.pair.private)
    private lateinit var http: GitHubHttp
    private lateinit var tokens: InstallationTokens

    @BeforeEach
    fun start() {
        server.start()
        http = GitHubHttp(URI.create(server.baseUrl()), clock, { })
        tokens = InstallationTokens(http, credentials, clock)
    }

    @AfterEach
    fun stop() = server.stop()

    private fun stubToken(
        token: String,
        expiresAt: String = "2026-11-08T11:00:00Z",
    ) {
        server.stubFor(
            post("/app/installations/42/access_tokens")
                .willReturn(
                    aResponse()
                        .withStatus(
                            201,
                        ).withBody(
                            """{"token":"$token","expires_at":"$expiresAt","repository_selection":"selected"}""",
                        ),
                ),
        )
    }

    @Test
    fun `creates an installation token with the app's JWT`() {
        stubToken("ghs_first")

        tokens.authorization(42) shouldBe "Bearer ghs_first"

        server.verify(
            postRequestedFor(
                urlEqualTo("/app/installations/42/access_tokens"),
            ).withHeader("Authorization", matching("Bearer ey[^.]+\\.[^.]+\\.[^.]+")),
        )
    }

    @Test
    fun `keeps the token until 5 minutes before it expires, then creates another`() {
        stubToken("ghs_first")
        tokens.authorization(42)

        clock.now = Instant.parse("2026-11-08T10:54:59Z")
        stubToken("ghs_second")
        tokens.authorization(42) shouldBe "Bearer ghs_first"

        clock.now = Instant.parse("2026-11-08T10:55:00Z")
        tokens.authorization(42) shouldBe "Bearer ghs_second"
        server.findAll(postRequestedFor(urlEqualTo("/app/installations/42/access_tokens"))).size shouldBe 2
    }

    @Test
    fun `fails clearly when GitHub refuses the token`() {
        server.stubFor(
            post(
                "/app/installations/42/access_tokens",
            ).willReturn(aResponse().withStatus(404).withBody("""{"message":"Not Found"}""")),
        )

        shouldThrow<GitHubApiException> { tokens.authorization(42) }.status shouldBe 404
    }

    @Test
    fun `reads the app, its installations and the repositories an installation may read`() {
        val client = GitHubAppClient(http, credentials, tokens, clock)
        stubToken("ghs_first")
        server.stubFor(
            get(
                "/app",
            ).willReturn(
                aResponse().withBody("""{"id":5671048,"slug":"firstpass-dev","name":"FirstPass Dev","node_id":"x"}"""),
            ),
        )
        val installations =
            """
            [{"id": 42, "account": {"login": "octo", "type": "User"}},
             {"id": 43, "account": {"slug": "big-corp"}}]
            """.trimIndent()
        val repositories =
            """
            {"total_count": 1, "repository_selection": "selected",
             "repositories": [{"id": 7, "name": "sandbox", "full_name": "octo/sandbox", "private": false}]}
            """.trimIndent()
        server.stubFor(get("/app/installations?per_page=100").willReturn(aResponse().withBody(installations)))
        server.stubFor(get("/installation/repositories?per_page=100").willReturn(aResponse().withBody(repositories)))

        client.app() shouldBe AppInfo(5671048, "firstpass-dev", "FirstPass Dev")
        client.installations() shouldContainExactly
            listOf(InstallationInfo(42, "octo", "User"), InstallationInfo(43, "big-corp", "Enterprise"))
        client.installationRepositories(42) shouldContainExactly
            listOf(RepositoryRef(7, "octo/sandbox", private = false))

        server.verify(getRequestedFor(urlEqualTo("/app")).withHeader("Authorization", matching("Bearer ey.+")))
        server.verify(
            getRequestedFor(
                urlEqualTo("/installation/repositories?per_page=100"),
            ).withHeader("Authorization", equalTo("Bearer ghs_first")),
        )
    }
}
