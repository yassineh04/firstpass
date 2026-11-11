package io.github.yassineh04.firstpass.aws

import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeParseException

/** A queue message that is not a job: it fails, is retried, then goes to the dead-letter queue. */
class MalformedJobException(
    reason: String,
    cause: Throwable? = null,
) : IllegalArgumentException("Malformed job: $reason", cause)

/**
 * The JSON of a job in the review queue (ADR 0002): its `type` (`review`, `pr_closed` or
 * `installation`), the delivery id, the reception time, the installation and the fields of the
 * job. Choices are written by their name in FirstPass.
 */
object JobJson {
    private val JSON: JsonMapper = JsonMapper.builder().build()
    private const val REVIEW = "review"
    private const val CLOSED = "pr_closed"
    private const val INSTALLATION = "installation"

    fun encode(job: Job): String {
        val node = JSON.createObjectNode()
        node.put("deliveryId", job.deliveryId)
        node.put("receivedAt", job.receivedAt.toString())
        node.put("installationId", job.installationId)
        when (job) {
            is Job.Review -> {
                node.put("type", REVIEW)
                node.set("repository", repository(job.repository))
                node.put("pullRequest", job.pullRequest)
                node.put("headSha", job.headSha)
                node.put("baseSha", job.baseSha)
                node.put("trigger", job.trigger.name)
            }

            is Job.PullRequestClosed -> {
                node.put("type", CLOSED)
                node.set("repository", repository(job.repository))
                node.put("pullRequest", job.pullRequest)
                node.put("merged", job.merged)
            }

            is Job.Installation -> {
                node.put("type", INSTALLATION)
                node.put("change", job.change.name)
                node.put("accountLogin", job.accountLogin)
                node.put("accountType", job.accountType)
                node.putArray("repositories").also { list -> job.repositories.forEach { list.add(repository(it)) } }
            }
        }
        return JSON.writeValueAsString(node)
    }

    fun decode(text: String): Job {
        val node =
            try {
                JSON.readTree(text)
            } catch (error: JacksonException) {
                throw MalformedJobException(error.originalMessage.orEmpty(), error)
            }
        val deliveryId = node.text("deliveryId")
        val receivedAt = node.instant("receivedAt")
        val installationId = node.long("installationId")
        return when (val type = node.text("type")) {
            REVIEW -> {
                Job.Review(
                    deliveryId = deliveryId,
                    receivedAt = receivedAt,
                    installationId = installationId,
                    repository = repositoryOf(node.path("repository")),
                    pullRequest = node.long("pullRequest").toInt(),
                    headSha = node.text("headSha"),
                    baseSha = node.text("baseSha"),
                    trigger = node.choice<ReviewTrigger>("trigger"),
                )
            }

            CLOSED -> {
                Job.PullRequestClosed(
                    deliveryId = deliveryId,
                    receivedAt = receivedAt,
                    installationId = installationId,
                    repository = repositoryOf(node.path("repository")),
                    pullRequest = node.long("pullRequest").toInt(),
                    merged = node.flag("merged"),
                )
            }

            INSTALLATION -> {
                Job.Installation(
                    deliveryId = deliveryId,
                    receivedAt = receivedAt,
                    installationId = installationId,
                    change = node.choice<InstallationChange>("change"),
                    accountLogin = node.textOrNull("accountLogin"),
                    accountType = node.textOrNull("accountType"),
                    repositories = node.path("repositories").values().map(::repositoryOf),
                )
            }

            else -> {
                throw MalformedJobException("unknown type \"$type\"")
            }
        }
    }

    private fun repository(repository: RepositoryRef): ObjectNode =
        JSON
            .createObjectNode()
            .put("id", repository.id)
            .put("fullName", repository.fullName)
            .put("private", repository.private)

    private fun repositoryOf(node: JsonNode) =
        RepositoryRef(node.long("id"), node.text("fullName"), node.flag("private"))

    private fun JsonNode.textOrNull(name: String): String? = path(name).takeIf { it.isString }?.asString()

    private fun JsonNode.text(name: String): String =
        textOrNull(name) ?: throw MalformedJobException("\"$name\" is missing")

    private fun JsonNode.long(name: String): Long =
        path(name).takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
            ?: throw MalformedJobException("\"$name\" is missing")

    private fun JsonNode.flag(name: String): Boolean =
        path(name).takeIf { it.isBoolean }?.booleanValue() ?: throw MalformedJobException("\"$name\" is missing")

    private fun JsonNode.instant(name: String): Instant =
        try {
            Instant.parse(text(name))
        } catch (_: DateTimeParseException) {
            throw MalformedJobException("\"$name\" is not a time")
        }

    private inline fun <reified T : Enum<T>> JsonNode.choice(name: String): T {
        val value = text(name)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw MalformedJobException("unknown $name \"$value\"")
    }
}
