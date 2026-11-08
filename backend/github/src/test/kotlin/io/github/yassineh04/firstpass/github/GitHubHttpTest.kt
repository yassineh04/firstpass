package io.github.yassineh04.firstpass.github

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GitHubHttpTest {
    private val server = WireMockServer(options().dynamicPort())
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private val slept = mutableListOf<Duration>()
    private lateinit var http: GitHubHttp

    @BeforeEach
    fun start() {
        server.start()
        http = GitHubHttp(URI.create(server.baseUrl()), Clock.fixed(now, ZoneOffset.UTC), { slept += it })
    }

    @AfterEach
    fun stop() = server.stop()

    @Test
    fun `sends the headers GitHub asks for`() {
        server.stubFor(get("/zen").willReturn(aResponse().withStatus(200).withBody("Keep it logically awesome.")))

        http.get("/zen", "Bearer t0ken").body shouldBe "Keep it logically awesome."

        server.verify(
            getRequestedFor(urlEqualTo("/zen"))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("X-GitHub-Api-Version", equalTo("2026-03-10"))
                .withHeader("Authorization", equalTo("Bearer t0ken"))
                .withHeader("User-Agent", matching("FirstPass.*")),
        )
    }

    @Test
    fun `retries a server error after a short backoff`() {
        failOnce(aResponse().withStatus(502))

        http.get("/flaky", "Bearer t").status shouldBe 200
        slept.size shouldBe 1
    }

    @Test
    fun `follows retry-after, the rate limit reset, or waits a minute for a secondary limit`() {
        failOnce(aResponse().withStatus(429).withHeader("retry-after", "2"))
        http.get("/flaky", "Bearer t").status shouldBe 200

        failOnce(
            aResponse()
                .withStatus(403)
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", "${now.epochSecond + 30}"),
        )
        http.get("/flaky", "Bearer t").status shouldBe 200

        failOnce(aResponse().withStatus(403).withBody("""{"message":"You have exceeded a secondary rate limit."}"""))
        http.get("/flaky", "Bearer t").status shouldBe 200

        slept shouldContainExactly listOf(2.seconds, 30.seconds, 60.seconds)
    }

    @Test
    fun `does not retry a refusal that is not a rate limit, nor wait longer than the limit`() {
        server.stubFor(
            get(
                "/forbidden",
            ).willReturn(
                aResponse().withStatus(403).withBody("""{"message":"Resource not accessible by integration"}"""),
            ),
        )
        server.stubFor(get("/later").willReturn(aResponse().withStatus(429).withHeader("retry-after", "3600")))

        http.get("/forbidden", "Bearer t").status shouldBe 403
        http.get("/later", "Bearer t").status shouldBe 429

        slept.shouldBeEmpty()
        server.findAll(getRequestedFor(urlEqualTo("/forbidden"))).size shouldBe 1
    }

    @Test
    fun `gives up after 3 attempts, and reports GitHub as unreachable after network errors`() {
        server.stubFor(get("/down").willReturn(aResponse().withStatus(500)))
        server.stubFor(get("/reset").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))

        http.get("/down", "Bearer t").status shouldBe 500
        shouldThrow<GitHubApiException> { http.get("/reset", "Bearer t") }.status shouldBe 0

        server.findAll(getRequestedFor(urlEqualTo("/down"))).size shouldBe 3
        server.findAll(getRequestedFor(urlEqualTo("/reset"))).size shouldBe 3
    }

    @Test
    fun `follows the next links of a paginated list`() {
        val base = server.baseUrl()
        val firstLinks =
            "<$base/items?per_page=100&page=2>; rel=\"next\", " + "<$base/items?per_page=100&page=3>; rel=\"last\""
        server.stubFor(
            get(
                urlEqualTo("/items?per_page=100"),
            ).willReturn(aResponse().withHeader("link", firstLinks).withBody("[1, 2]")),
        )
        server.stubFor(
            get(urlEqualTo("/items?per_page=100&page=2"))
                .willReturn(
                    aResponse().withHeader("link", "<$base/items?per_page=100&page=3>; rel=\"next\"").withBody("[3]"),
                ),
        )
        server.stubFor(get(urlEqualTo("/items?per_page=100&page=3")).willReturn(aResponse().withBody("[4]")))

        http.getAll("/items?per_page=100", "Bearer t").map { it.asInt() } shouldContainExactly listOf(1, 2, 3, 4)
    }

    @Test
    fun `never sends the token to a next link on another host`() {
        val other = WireMockServer(options().dynamicPort()).apply { start() }
        try {
            other.stubFor(get(urlPathEqualTo("/items")).willReturn(aResponse().withBody("[2]")))
            val link = "<${other.baseUrl()}/items?page=2>; rel=\"next\""
            server.stubFor(
                get(urlPathEqualTo("/items")).willReturn(aResponse().withHeader("link", link).withBody("[1]")),
            )

            shouldThrow<GitHubApiException> { http.getAll("/items", "Bearer t") }.message shouldContain "outside"

            other.allServeEvents.shouldBeEmpty()
        } finally {
            other.stop()
        }
    }

    private fun failOnce(failure: com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder) {
        server.resetAll()
        server.stubFor(
            get(
                "/flaky",
            ).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED).willReturn(failure).willSetStateTo("ok"),
        )
        server.stubFor(
            get("/flaky").inScenario("flaky").whenScenarioStateIs("ok").willReturn(aResponse().withStatus(200)),
        )
    }
}
