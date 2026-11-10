package io.github.yassineh04.firstpass.core.webhook

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Reads the payload of one event; the github adapter implements it with a JSON library. */
fun interface WebhookEventParser {
    fun parse(
        event: String,
        body: ByteArray,
    ): ParsedWebhook
}

/** Remembers deliveries already received (design 7.1, step 4). */
interface DeliveryStore {
    /** Records [deliveryId]; false when it was already recorded, so the delivery is a duplicate. */
    fun markReceived(
        deliveryId: String,
        event: String,
        receivedAt: Instant,
    ): Boolean

    /** Forgets [deliveryId] after a failed hand-off, so that a manual redelivery is not taken for a duplicate. */
    fun forget(deliveryId: String)
}

/** Hands a job to the worker: the queue in production, a direct call locally. False when it could not. */
fun interface JobPublisher {
    fun publish(job: Job): Boolean
}

/** An HTTP request as the entry point received it. Header names are compared without case. */
class WebhookRequest(
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_INTERNAL_ERROR = 500

/** The answer to GitHub, with its HTTP status. */
enum class WebhookOutcome(
    val status: Int,
) {
    ACCEPTED(HTTP_ACCEPTED),
    IGNORED(HTTP_OK),
    DUPLICATE(HTTP_OK),
    UNAUTHORIZED(HTTP_UNAUTHORIZED),
    BAD_REQUEST(HTTP_BAD_REQUEST),
    FAILED(HTTP_INTERNAL_ERROR),
}

/** Decides which events become jobs (design 7.1, steps 3 and 5). */
object WebhookRouter {
    private val REVIEW_TRIGGERS =
        mapOf(
            "opened" to ReviewTrigger.OPENED,
            "reopened" to ReviewTrigger.REOPENED,
            "ready_for_review" to ReviewTrigger.READY_FOR_REVIEW,
            "synchronize" to ReviewTrigger.SYNCHRONIZE,
        )
    private val INSTALLATION_CHANGES =
        mapOf(
            "created" to InstallationChange.CREATED,
            "deleted" to InstallationChange.DELETED,
            "suspend" to InstallationChange.SUSPENDED,
            "unsuspend" to InstallationChange.UNSUSPENDED,
        )

    /** The job for [event], or null when its action needs no work. */
    fun route(
        deliveryId: String,
        receivedAt: Instant,
        event: WebhookEvent,
    ): Job? =
        when (event) {
            is WebhookEvent.PullRequest -> pullRequest(deliveryId, receivedAt, event)
            is WebhookEvent.Installation -> installation(deliveryId, receivedAt, event)
            is WebhookEvent.InstallationRepositories -> repositories(deliveryId, receivedAt, event)
        }

    private fun pullRequest(
        deliveryId: String,
        receivedAt: Instant,
        event: WebhookEvent.PullRequest,
    ): Job? {
        val trigger = REVIEW_TRIGGERS[event.action]
        return when {
            trigger != null -> {
                Job.Review(
                    deliveryId,
                    receivedAt,
                    event.installationId,
                    event.repository,
                    event.number,
                    event.headSha,
                    event.baseSha,
                    trigger,
                )
            }

            event.action == "closed" -> {
                Job.PullRequestClosed(
                    deliveryId,
                    receivedAt,
                    event.installationId,
                    event.repository,
                    event.number,
                    event.merged,
                )
            }

            else -> {
                null
            }
        }
    }

    private fun installation(
        deliveryId: String,
        receivedAt: Instant,
        event: WebhookEvent.Installation,
    ): Job? =
        INSTALLATION_CHANGES[event.action]?.let { change ->
            Job.Installation(
                deliveryId,
                receivedAt,
                event.installationId,
                change,
                event.accountLogin,
                event.accountType,
                event.repositories,
            )
        }

    private fun repositories(
        deliveryId: String,
        receivedAt: Instant,
        event: WebhookEvent.InstallationRepositories,
    ): Job? =
        when (event.action) {
            "added" -> {
                Job.Installation(
                    deliveryId,
                    receivedAt,
                    event.installationId,
                    InstallationChange.REPOSITORIES_ADDED,
                    null,
                    null,
                    event.added,
                )
            }

            "removed" -> {
                Job.Installation(
                    deliveryId,
                    receivedAt,
                    event.installationId,
                    InstallationChange.REPOSITORIES_REMOVED,
                    null,
                    null,
                    event.removed,
                )
            }

            else -> {
                null
            }
        }
}

/**
 * Receives one webhook delivery (design 7.1, steps 2 to 5, and FR-04 to FR-06): checks the
 * signature, ignores events FirstPass does not handle, drops duplicates and hands the job to the
 * worker, without any call to GitHub or to a model.
 */
class WebhookIntake(
    private val secret: WebhookSecret,
    private val parser: WebhookEventParser,
    private val deliveries: DeliveryStore,
    private val jobs: JobPublisher,
    private val clock: Clock,
) {
    fun receive(request: WebhookRequest): WebhookOutcome {
        val event = request.header(EVENT_HEADER)
        val delivery = request.header(DELIVERY_HEADER)
        return when {
            !WebhookSignature.isValid(
                secret,
                request.body,
                request.header(SIGNATURE_HEADER),
            ) -> WebhookOutcome.UNAUTHORIZED

            event.isNullOrBlank() || delivery.isNullOrBlank() -> WebhookOutcome.BAD_REQUEST

            else -> handle(event, delivery, request.body)
        }
    }

    /** Events and actions FirstPass does not handle are answered before anything is stored. */
    private fun handle(
        event: String,
        delivery: String,
        body: ByteArray,
    ): WebhookOutcome =
        when (val parsed = parser.parse(event, body)) {
            is ParsedWebhook.Malformed -> {
                WebhookOutcome.BAD_REQUEST
            }

            ParsedWebhook.Unhandled -> {
                WebhookOutcome.IGNORED
            }

            is ParsedWebhook.Handled -> {
                // Milliseconds are enough, and keep the time the same once stored and read back.
                val receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                WebhookRouter.route(delivery, receivedAt, parsed.event)?.let { dispatch(event, delivery, it) }
                    ?: WebhookOutcome.IGNORED
            }
        }

    private fun dispatch(
        event: String,
        delivery: String,
        job: Job,
    ): WebhookOutcome =
        when {
            !deliveries.markReceived(delivery, event, job.receivedAt) -> {
                WebhookOutcome.DUPLICATE
            }

            jobs.publish(job) -> {
                WebhookOutcome.ACCEPTED
            }

            else -> {
                // GitHub never redelivers on its own, and a manual redelivery keeps the same id.
                deliveries.forget(delivery)
                WebhookOutcome.FAILED
            }
        }

    private companion object {
        const val SIGNATURE_HEADER = "X-Hub-Signature-256"
        const val EVENT_HEADER = "X-GitHub-Event"
        const val DELIVERY_HEADER = "X-GitHub-Delivery"
    }
}
