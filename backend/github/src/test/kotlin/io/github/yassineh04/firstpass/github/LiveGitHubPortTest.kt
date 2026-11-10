package io.github.yassineh04.firstpass.github

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Path

/**
 * Read-only calls to a real pull request of the sandbox repository, run by hand only: skipped
 * unless GITHUB_SANDBOX_PR is set. Nothing is written to GitHub. The result goes to
 * build/live-github-port.json, without any token.
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_SANDBOX_PR", matches = "\\d+")
class LiveGitHubPortTest {
    @Test
    fun `reads a sandbox pull request, its files and its settings file`() {
        val credentials =
            AppCredentials(
                System.getenv("GITHUB_CLIENT_ID").orEmpty(),
                PrivateKeys.read(Path.of(System.getenv("GITHUB_APP_PRIVATE_KEY_PATH"))),
            )
        val http = GitHubHttp()
        val tokens = InstallationTokens(http, credentials)
        val apps = GitHubAppClient(http, credentials, tokens)
        val installation = apps.installations().first()
        val sandbox =
            apps
                .installationRepositories(installation.id)
                .firstOrNull { it.name == "firstpass-sandbox" }
                .shouldNotBeNull()
        val number = System.getenv("GITHUB_SANDBOX_PR").toInt()
        val port = GitHubRestClient(http, tokens)

        val pullRequest = port.pullRequest(installation.id, sandbox, number)
        val files = port.pullRequestFiles(installation.id, sandbox, number)
        val config = port.repositoryConfig(installation.id, sandbox, pullRequest.baseRef)
        val report =
            mapOf(
                "pullRequest" to pullRequest,
                "files" to
                    files.map {
                        mapOf(
                            "path" to it.path,
                            "status" to it.status,
                            "patchLines" to it.patch?.lines()?.size,
                        )
                    },
                "settingsFile" to (config?.let { mapOf("overrides" to it.overrides, "issues" to it.issues) } ?: "none"),
            )
        File("build/live-github-port.json").writeText(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report))

        withClue("See build/live-github-port.json") { files.shouldNotBeEmpty() }
    }
}
