package io.github.yassineh04.firstpass.github

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Path

/**
 * Real calls to GitHub as the development app, run by hand only: skipped unless the key path is
 * set, so CI never runs it. Writes what it found to build/live-github.json, without any token.
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_APP_PRIVATE_KEY_PATH", matches = ".+")
class LiveGitHubTest {
    @Test
    fun `authenticates as the app, then as its installations, and lists the repositories they may read`() {
        val credentials =
            AppCredentials(
                System.getenv("GITHUB_CLIENT_ID").orEmpty(),
                PrivateKeys.read(Path.of(System.getenv("GITHUB_APP_PRIVATE_KEY_PATH"))),
            )
        val http = GitHubHttp()
        val client = GitHubAppClient(http, credentials, InstallationTokens(http, credentials))

        val app = client.app()
        val installations = client.installations()
        val repositories =
            installations.map { installation ->
                val names =
                    client
                        .installationRepositories(
                            installation.id,
                        ).map { "${it.fullName} (private=${it.private})" }
                mapOf("id" to installation.id, "account" to installation.account, "repositories" to names)
            }
        val report = mapOf("app" to app, "installations" to repositories)
        File("build/live-github.json").writeText(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report))

        val expectedAppId = System.getenv("GITHUB_APP_ID")?.toLongOrNull()
        if (expectedAppId != null) withClue("App ID") { app.id shouldBe expectedAppId }
        withClue("See build/live-github.json") { installations.shouldNotBeEmpty() }
    }
}
