package io.github.yassineh04.firstpass.github

import io.github.yassineh04.firstpass.core.webhook.ParsedWebhook
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.WebhookEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WebhookPayloadParserTest {
    private val parser = WebhookPayloadParser()
    private val helloWorld = RepositoryRef(186853002, "Codertocat/Hello-World", private = false)

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/webhooks/$name.json")) { "Missing fixture $name" }.readBytes()

    @Test
    fun `reads a pull request event`() {
        parser.parse("pull_request", fixture("pull_request_opened")) shouldBe
            ParsedWebhook.Handled(
                WebhookEvent.PullRequest(
                    action = "opened",
                    installationId = 1,
                    repository = helloWorld,
                    number = 2,
                    headSha = "ec26c3e57ca3a959ca5aad62de7213c562f8c821",
                    baseSha = "f95f852bd8fca8fcc58a9a2d6c842781e32a215e",
                    merged = false,
                ),
            )
    }

    @Test
    fun `reads an installation event and the repositories it grants`() {
        parser.parse("installation", fixture("installation_created")) shouldBe
            ParsedWebhook.Handled(
                WebhookEvent.Installation("created", 957387, "Codertocat", "User", listOf(helloWorld)),
            )
    }

    @Test
    fun `reads the repositories added to or removed from an installation`() {
        parser.parse("installation_repositories", fixture("installation_repositories_added")) shouldBe
            ParsedWebhook.Handled(
                WebhookEvent.InstallationRepositories(
                    action = "added",
                    installationId = 957387,
                    added = listOf(RepositoryRef(186853007, "Codertocat/Space", private = false)),
                    removed = emptyList(),
                ),
            )
    }

    @Test
    fun `names an enterprise account by its slug, as it has no login`() {
        val payload =
            """{"action":"deleted","installation":{"id":5,"account":{"slug":"big-corp","name":"Big Corp"}}}"""

        parser.parse("installation", payload.toByteArray()) shouldBe
            ParsedWebhook.Handled(WebhookEvent.Installation("deleted", 5, "big-corp", "Enterprise", emptyList()))
    }

    @Test
    fun `leaves alone the events FirstPass does not handle`() {
        for (event in listOf("ping", "push", "issue_comment", "check_run")) {
            parser.parse(event, "{}".toByteArray()) shouldBe ParsedWebhook.Unhandled
        }
    }

    @Test
    fun `reports a payload that is not JSON or lacks a needed field`() {
        parser.parse("pull_request", "not json".toByteArray()).shouldBeInstanceOf<ParsedWebhook.Malformed>()

        val withoutInstallation = String(fixture("pull_request_opened")).replace("\"installation\"", "\"other\"")
        parser
            .parse(
                "pull_request",
                withoutInstallation.toByteArray(),
            ).shouldBeInstanceOf<ParsedWebhook.Malformed>()
            .reason shouldContain
            "installation.id"

        val repositoryWithoutName = String(fixture("installation_created")).replace("\"full_name\"", "\"nickname\"")
        parser.parse("installation", repositoryWithoutName.toByteArray()).shouldBeInstanceOf<ParsedWebhook.Malformed>()
    }
}
