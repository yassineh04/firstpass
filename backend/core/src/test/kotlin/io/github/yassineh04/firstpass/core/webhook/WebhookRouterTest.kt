package io.github.yassineh04.firstpass.core.webhook

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class WebhookRouterTest {
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private val repository = RepositoryRef(id = 7, fullName = "octo/sandbox", private = false)

    private fun pullRequest(
        action: String,
        merged: Boolean = false,
    ) = WebhookEvent.PullRequest(
        action,
        installationId = 42,
        repository,
        number = 3,
        headSha = "head1",
        baseSha = "base1",
        merged,
    )

    @Test
    fun `turns the pull request actions that need a review into review jobs`() {
        mapOf(
            "opened" to ReviewTrigger.OPENED,
            "reopened" to ReviewTrigger.REOPENED,
            "ready_for_review" to ReviewTrigger.READY_FOR_REVIEW,
            "synchronize" to ReviewTrigger.SYNCHRONIZE,
        ).forEach { (action, trigger) ->
            WebhookRouter.route("d-1", at, pullRequest(action)) shouldBe
                Job.Review(
                    "d-1",
                    at,
                    42,
                    repository,
                    pullRequest = 3,
                    headSha = "head1",
                    baseSha = "base1",
                    trigger = trigger,
                )
        }
    }

    @Test
    fun `records a closed pull request, merged or not, and ignores the other actions`() {
        WebhookRouter.route("d-2", at, pullRequest("closed", merged = true)) shouldBe
            Job.PullRequestClosed("d-2", at, 42, repository, pullRequest = 3, merged = true)
        for (action in listOf("edited", "labeled", "assigned", "converted_to_draft", "review_requested")) {
            WebhookRouter.route("d-3", at, pullRequest(action)).shouldBeNull()
        }
    }

    @Test
    fun `follows the installation of the app and the repositories it may read`() {
        val installation = WebhookEvent.Installation("created", 42, "octo", "User", listOf(repository))

        WebhookRouter.route("d-4", at, installation) shouldBe
            Job.Installation("d-4", at, 42, InstallationChange.CREATED, "octo", "User", listOf(repository))
        mapOf(
            "deleted" to InstallationChange.DELETED,
            "suspend" to InstallationChange.SUSPENDED,
            "unsuspend" to InstallationChange.UNSUSPENDED,
        ).forEach { (action, change) ->
            WebhookRouter.route("d-5", at, installation.copy(action = action)) shouldBe
                Job.Installation("d-5", at, 42, change, "octo", "User", listOf(repository))
        }
        WebhookRouter.route("d-6", at, installation.copy(action = "new_permissions_accepted")).shouldBeNull()
    }

    @Test
    fun `passes on the repositories added to or removed from an installation`() {
        val other = RepositoryRef(8, "octo/other", private = true)
        val change =
            WebhookEvent.InstallationRepositories(
                "added",
                42,
                added = listOf(other),
                removed = listOf(repository),
            )

        WebhookRouter.route("d-7", at, change) shouldBe
            Job.Installation("d-7", at, 42, InstallationChange.REPOSITORIES_ADDED, null, null, listOf(other))
        WebhookRouter.route("d-8", at, change.copy(action = "removed")) shouldBe
            Job.Installation("d-8", at, 42, InstallationChange.REPOSITORIES_REMOVED, null, null, listOf(repository))
        WebhookRouter.route("d-9", at, change.copy(action = "renamed")).shouldBeNull()
    }

    @Test
    fun `splits the full name of a repository into owner and name`() {
        repository.owner shouldBe "octo"
        repository.name shouldBe "sandbox"
    }
}
