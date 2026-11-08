package io.github.yassineh04.firstpass.github

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A call to the GitHub REST API that failed for good; [status] is 0 when GitHub could not be reached. */
class GitHubApiException(
    val status: Int,
    message: String,
) : RuntimeException(message)

/** Waits between attempts; replaced by a recorder in tests, so that no test really sleeps. */
fun interface Pause {
    fun sleep(duration: Duration)

    companion object {
        val THREAD = Pause { Thread.sleep(it.inWholeMilliseconds) }
    }
}

internal class GitHubResponse(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    fun json(): JsonNode = JSON.readTree(body)

    /** The error to throw when this response is not the expected one; GitHub's message, if any, explains it. */
    fun failure(call: String): GitHubApiException {
        val message =
            try {
                json().textOrNull("message")
            } catch (_: JacksonException) {
                null
            }
        return GitHubApiException(
            status,
            "$call failed with HTTP $status${message?.let { ": ${it.take(MAX_MESSAGE)}" }.orEmpty()}",
        )
    }

    private companion object {
        const val MAX_MESSAGE = 200
    }
}

/**
 * The REST API over the JDK HTTP client, with the headers GitHub asks for and the retries of design
 * section 11: up to 3 attempts on server errors, network errors and rate limits, following
 * `retry-after` and `x-ratelimit-reset`, or waiting a minute for a secondary limit without them.
 * A wait longer than [MAX_WAIT] is not worth it: the call fails and the job is retried later.
 */
class GitHubHttp(
    private val baseUrl: URI = URI.create("https://api.github.com"),
    private val clock: Clock = Clock.systemUTC(),
    private val pause: Pause = Pause.THREAD,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    internal fun get(
        path: String,
        authorization: String,
    ): GitHubResponse = send("GET", path, authorization, body = null)

    internal fun send(
        method: String,
        path: String,
        authorization: String,
        body: String?,
    ): GitHubResponse {
        val request = request(method, resolve(path), authorization, body)
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            val exchange = exchange(request)
            val wait = retryDelay(exchange, attempt) ?: return exchange.responseOrThrow()
            pause.sleep(wait)
        }
        return exchange(request).responseOrThrow()
    }

    /** Every page of a list endpoint, following the `link` header's `rel="next"`. */
    internal fun getAll(
        path: String,
        authorization: String,
        items: (JsonNode) -> List<JsonNode> = { it.values().toList() },
    ): List<JsonNode> {
        val all = mutableListOf<JsonNode>()
        var next: String? = path
        while (next != null) {
            val response = get(next, authorization)
            if (response.status != HTTP_OK) throw response.failure("GET $next")
            all += items(response.json())
            next = nextLink(response.header("link"))
        }
        return all
    }

    private sealed interface Exchange {
        class Answered(
            val response: GitHubResponse,
        ) : Exchange

        class Unreachable(
            val reason: String,
        ) : Exchange

        fun responseOrThrow(): GitHubResponse =
            when (this) {
                is Answered -> response
                is Unreachable -> throw GitHubApiException(0, "GitHub could not be reached: $reason")
            }
    }

    private fun exchange(request: HttpRequest): Exchange =
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            Exchange.Answered(GitHubResponse(response.statusCode(), response.body(), response.headers().map()))
        } catch (error: IOException) {
            Exchange.Unreachable(error.message ?: error.javaClass.simpleName)
        }

    /** The wait before the next attempt, or null when the result is final. */
    private fun retryDelay(
        exchange: Exchange,
        attempt: Int,
    ): Duration? =
        when (exchange) {
            is Exchange.Unreachable -> {
                backoff(attempt)
            }

            is Exchange.Answered -> {
                val status = exchange.response.status
                when {
                    status >= HTTP_INTERNAL_ERROR -> backoff(attempt)
                    status == HTTP_FORBIDDEN || status == HTTP_TOO_MANY_REQUESTS -> rateLimitDelay(exchange.response)
                    else -> null
                }
            }
        }

    /** One second, then two, plus up to a second of jitter so that workers do not retry together. */
    private fun backoff(attempt: Int): Duration = BACKOFF_BASE * (1 shl attempt) + BACKOFF_BASE * Random.nextDouble()

    /** GitHub's rate limit rules; null for a 403 that is not a rate limit, or a wait beyond [MAX_WAIT]. */
    private fun rateLimitDelay(response: GitHubResponse): Duration? {
        val retryAfter = response.header("retry-after")?.toLongOrNull()
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()
        val delay =
            when {
                retryAfter != null -> {
                    retryAfter.seconds
                }

                response.header("x-ratelimit-remaining") == "0" && reset != null -> {
                    (reset - clock.instant().epochSecond).coerceAtLeast(0).seconds
                }

                response.status == HTTP_TOO_MANY_REQUESTS || "rate limit" in response.body.lowercase() -> {
                    SECONDARY_WAIT
                }

                else -> {
                    null
                }
            }
        return delay?.takeIf { it <= MAX_WAIT }
    }

    private fun request(
        method: String,
        uri: URI,
        authorization: String,
        body: String?,
    ): HttpRequest =
        HttpRequest
            .newBuilder(uri)
            .timeout(TIMEOUT.toJavaDuration())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", authorization)
            .header("User-Agent", USER_AGENT)
            .apply { if (body != null) header("Content-Type", "application/json") }
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
            ).build()

    /** A path of the API, or an absolute next link, which must point to the API itself: the token never leaves it. */
    private fun resolve(pathOrUrl: String): URI {
        if (!pathOrUrl.startsWith("http")) return URI.create(baseUrl.toString().trimEnd('/') + pathOrUrl)
        val uri = URI.create(pathOrUrl)
        if (uri.scheme != baseUrl.scheme || uri.host != baseUrl.host || uri.port != baseUrl.port) {
            throw GitHubApiException(0, "Refusing to follow a link outside $baseUrl")
        }
        return uri
    }

    private fun nextLink(header: String?): String? =
        header
            ?.split(',')
            ?.firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<')
            ?.substringBefore('>')

    private companion object {
        /** Checked on 2026-11-08: its breaking changes touch no field FirstPass reads. */
        const val API_VERSION = "2026-03-10"
        const val USER_AGENT = "FirstPass"
        const val MAX_ATTEMPTS = 3
        const val HTTP_OK = 200
        const val HTTP_TOO_MANY_REQUESTS = 429
        val BACKOFF_BASE = 1.seconds
        val SECONDARY_WAIT = 60.seconds
        val MAX_WAIT = 90.seconds
        val TIMEOUT = 30.seconds
    }
}
