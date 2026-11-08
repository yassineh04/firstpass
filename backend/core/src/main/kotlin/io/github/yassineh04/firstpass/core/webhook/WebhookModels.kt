package io.github.yassineh04.firstpass.core.webhook

/** A repository as webhooks describe it: `fullName` is `owner/name`. */
data class RepositoryRef(
    val id: Long,
    val fullName: String,
    val private: Boolean,
) {
    val owner: String get() = fullName.substringBefore('/')
    val name: String get() = fullName.substringAfter('/')
}

/** The webhook events FirstPass handles, as the github adapter reads them from the JSON payload. */
sealed interface WebhookEvent {
    data class PullRequest(
        val action: String,
        val installationId: Long,
        val repository: RepositoryRef,
        val number: Int,
        val headSha: String,
        val baseSha: String,
        val merged: Boolean,
    ) : WebhookEvent

    data class Installation(
        val action: String,
        val installationId: Long,
        val accountLogin: String,
        val accountType: String,
        val repositories: List<RepositoryRef>,
    ) : WebhookEvent

    data class InstallationRepositories(
        val action: String,
        val installationId: Long,
        val added: List<RepositoryRef>,
        val removed: List<RepositoryRef>,
    ) : WebhookEvent
}

/** The result of reading a payload: a handled event, an event FirstPass ignores, or a broken payload. */
sealed interface ParsedWebhook {
    data class Handled(
        val event: WebhookEvent,
    ) : ParsedWebhook

    data object Unhandled : ParsedWebhook

    data class Malformed(
        val reason: String,
    ) : ParsedWebhook
}

/** What triggered a review. Commands (`/firstpass review`) come in phase 5. */
enum class ReviewTrigger {
    OPENED,
    REOPENED,
    READY_FOR_REVIEW,
    SYNCHRONIZE,
}

enum class InstallationChange {
    CREATED,
    DELETED,
    SUSPENDED,
    UNSUSPENDED,
    REPOSITORIES_ADDED,
    REPOSITORIES_REMOVED,
}

/** A unit of work for the worker, sent through the queue. Every job carries its delivery id for tracing. */
sealed interface Job {
    val deliveryId: String
    val installationId: Long

    data class Review(
        override val deliveryId: String,
        override val installationId: Long,
        val repository: RepositoryRef,
        val pullRequest: Int,
        val headSha: String,
        val baseSha: String,
        val trigger: ReviewTrigger,
    ) : Job

    /** Records the closing; the feedback on the comments is collected from it in phase 5. */
    data class PullRequestClosed(
        override val deliveryId: String,
        override val installationId: Long,
        val repository: RepositoryRef,
        val pullRequest: Int,
        val merged: Boolean,
    ) : Job

    /** [accountLogin] and [accountType] are known for installation events, not for repository changes. */
    data class Installation(
        override val deliveryId: String,
        override val installationId: Long,
        val change: InstallationChange,
        val accountLogin: String?,
        val accountType: String?,
        val repositories: List<RepositoryRef>,
    ) : Job
}
