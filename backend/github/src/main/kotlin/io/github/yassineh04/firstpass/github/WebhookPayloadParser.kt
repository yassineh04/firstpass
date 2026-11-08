package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.webhook.ParsedWebhook
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.WebhookEvent
import io.github.yassineh04.firstpass.core.webhook.WebhookEventParser
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode

/**
 * Reads the payloads of the events FirstPass handles: `pull_request`, `installation` and
 * `installation_repositories`. Field paths follow GitHub's payload examples (octokit/webhooks).
 */
class WebhookPayloadParser : WebhookEventParser {
    private val readers: Map<String, (JsonNode) -> WebhookEvent> =
        mapOf(
            "pull_request" to ::pullRequest,
            "installation" to ::installation,
            "installation_repositories" to ::installationRepositories,
        )

    override fun parse(
        event: String,
        body: ByteArray,
    ): ParsedWebhook {
        val reader = readers[event] ?: return ParsedWebhook.Unhandled
        return try {
            ParsedWebhook.Handled(reader(JSON.readTree(body)))
        } catch (error: JacksonException) {
            ParsedWebhook.Malformed("Not JSON: ${error.message?.take(MAX_REASON)}")
        } catch (error: MissingField) {
            ParsedWebhook.Malformed(error.message.orEmpty())
        }
    }

    private fun pullRequest(json: JsonNode): WebhookEvent =
        WebhookEvent.PullRequest(
            action = json.text("action"),
            installationId = json.long("installation.id"),
            repository = repositoryOf(json.dig("repository")),
            number = json.int("pull_request.number"),
            headSha = json.text("pull_request.head.sha"),
            baseSha = json.text("pull_request.base.sha"),
            merged = json.flag("pull_request.merged"),
        )

    /** An enterprise account has no login: its slug names it. */
    private fun installation(json: JsonNode): WebhookEvent =
        WebhookEvent.Installation(
            action = json.text("action"),
            installationId = json.long("installation.id"),
            accountLogin = json.textOrNull("installation.account.login") ?: json.text("installation.account.slug"),
            accountType = json.textOrNull("installation.account.type") ?: ENTERPRISE,
            repositories = repositories(json.dig("repositories")),
        )

    private fun installationRepositories(json: JsonNode): WebhookEvent =
        WebhookEvent.InstallationRepositories(
            action = json.text("action"),
            installationId = json.long("installation.id"),
            added = repositories(json.dig("repositories_added")),
            removed = repositories(json.dig("repositories_removed")),
        )

    private fun repositories(node: JsonNode): List<RepositoryRef> =
        if (node.isArray) node.values().map(::repositoryOf) else emptyList()

    private companion object {
        const val ENTERPRISE = "Enterprise"
        const val MAX_REASON = 200
    }
}
