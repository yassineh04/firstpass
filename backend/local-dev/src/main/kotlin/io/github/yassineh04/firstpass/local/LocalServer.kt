package io.github.yassineh04.firstpass.local

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.yassineh04.firstpass.core.webhook.WebhookIntake
import io.github.yassineh04.firstpass.core.webhook.WebhookOutcome
import io.github.yassineh04.firstpass.core.webhook.WebhookRequest
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The local HTTP server, with the JDK's built-in server: `POST /webhook` gives each delivery to
 * the intake, as the webhook Lambda will, and `GET /health` answers "ok". It listens on this
 * machine only, never on the network.
 */
internal class LocalServer(
    private val intake: WebhookIntake,
    port: Int,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    /** The port in use, useful when [port] was 0 (any free port). */
    val port: Int get() = server.address.port

    init {
        server.createContext("/webhook") { exchange -> exchange.answer { webhook(it) } }
        server.createContext("/health") { exchange -> exchange.answer { HTTP_OK to "ok" } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun webhook(exchange: HttpExchange): Pair<Int, String> {
        if (exchange.requestMethod != "POST") return HTTP_METHOD_NOT_ALLOWED to "Use POST"
        val headers = exchange.requestHeaders.mapValues { it.value.first() }
        val outcome = intake.receive(WebhookRequest(headers, exchange.requestBody.readAllBytes()))
        // The JDK compares header names without case, as GitHub's names may arrive in any case.
        val event = exchange.requestHeaders.getFirst("X-GitHub-Event")
        log("Webhook $event, delivery ${exchange.requestHeaders.getFirst("X-GitHub-Delivery")}: $outcome")
        if (outcome == WebhookOutcome.UNAUTHORIZED) log(SIGNATURE_HINT)
        return outcome.status to outcome.name
    }

    private fun HttpExchange.answer(handle: (HttpExchange) -> Pair<Int, String>) {
        try {
            val (status, text) = handle(this)
            val bytes = text.toByteArray()
            responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        } finally {
            close()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val SIGNATURE_HINT =
            "  The signature does not match: check GITHUB_WEBHOOK_SECRET. Through smee, a payload with escaped or " +
                "non-ASCII characters can also fail, because smee re-encodes the JSON (docs/github-app-setup.md, step 7)."
    }
}
