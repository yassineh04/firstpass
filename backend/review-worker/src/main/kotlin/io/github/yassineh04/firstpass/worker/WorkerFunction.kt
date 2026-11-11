package io.github.yassineh04.firstpass.worker

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.yassineh04.firstpass.aws.JobJson
import io.github.yassineh04.firstpass.aws.JsonLog
import io.github.yassineh04.firstpass.aws.SsmSettings
import io.github.yassineh04.firstpass.core.job.JobHandler
import io.github.yassineh04.firstpass.core.job.JobOutcome
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.io.OutputStream

/**
 * The review worker Lambda, fed by the SQS queue (design 7.1, steps 6 to 18; ADR 0002). Each
 * message is one job; the messages that fail are reported in `batchItemFailures`, so SQS gives
 * back only those, up to 3 times, then moves them to the dead-letter queue.
 * Handler: `io.github.yassineh04.firstpass.worker.WorkerFunction`.
 */
class WorkerFunction(
    private val log: JsonLog = JsonLog(),
    handler: () -> (Job) -> JobOutcome = { production()::handle },
) : RequestStreamHandler {
    // Built on the first message, never during SnapStart's initialization: it reads secrets.
    private val handle by lazy(handler)

    override fun handleRequest(
        input: InputStream,
        output: OutputStream,
        context: Context?,
    ) {
        val failed =
            JSON
                .readTree(input)
                .path("Records")
                .values()
                .filterNot(::process)
        val response = JSON.createObjectNode()
        val list = response.putArray("batchItemFailures")
        failed.forEach { list.addObject().put("itemIdentifier", it.text("messageId")) }
        output.write(JSON.writeValueAsBytes(response))
    }

    // One failed job must not stop the others, whatever went wrong: SQS gives it back later.
    @Suppress("TooGenericExceptionCaught")
    private fun process(message: JsonNode): Boolean {
        val started = System.nanoTime()
        val delivery =
            mapOf(
                "messageId" to message.text("messageId"),
                "receiveCount" to message.path("attributes").text(RECEIVES),
            )
        return try {
            val job = JobJson.decode(message.text("body"))
            val outcome = handle(job)
            log.info("Job done", delivery + fields(job) + mapOf("outcome" to outcome, "durationMs" to elapsed(started)))
            true
        } catch (error: Exception) {
            val cause = mapOf("error" to error.javaClass.simpleName, "detail" to error.message)
            log.error("Job failed", delivery + cause + mapOf("durationMs" to elapsed(started)))
            false
        }
    }

    companion object {
        private val JSON: JsonMapper = JsonMapper.builder().build()
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val RECEIVES = "ApproximateReceiveCount"

        private fun JsonNode.text(name: String): String = path(name).takeIf { it.isString }?.asString().orEmpty()

        private fun elapsed(started: Long) = (System.nanoTime() - started) / NANOS_PER_MILLI

        /** What identifies a job in the logs: never code, a secret or a token. */
        private fun fields(job: Job): Map<String, Any?> {
            val kind =
                when (job) {
                    is Job.Review -> pullRequest("review", job.repository.fullName, job.pullRequest)
                    is Job.PullRequestClosed -> pullRequest("pr_closed", job.repository.fullName, job.pullRequest)
                    is Job.Installation -> mapOf("job" to "installation", "change" to job.change.name)
                }
            return mapOf("deliveryId" to job.deliveryId, "installationId" to job.installationId) + kind
        }

        private fun pullRequest(
            kind: String,
            repository: String,
            number: Int,
        ) = mapOf("job" to kind, "repository" to repository, "pullRequest" to number)

        /**
         * Production wiring: the secrets from SSM under `SSM_PREFIX` (default `/firstpass`); the
         * other settings (`DYNAMODB_TABLE`, `FIRSTPASS_LLM_PROVIDER`, ...) come from the
         * environment of the function, set by Terraform. The Groq key is optional.
         */
        private fun production(): JobHandler {
            val environment = System.getenv()
            val prefix = environment["SSM_PREFIX"] ?: "/firstpass"
            val groq = "$prefix/llm/groq-api-key"
            val secrets =
                SsmSettings.create().load(
                    mapOf(
                        "$prefix/github/client-id" to "GITHUB_CLIENT_ID",
                        "$prefix/github/private-key" to "GITHUB_APP_PRIVATE_KEY",
                        "$prefix/llm/gemini-api-key" to "GEMINI_API_KEY",
                        groq to "GROQ_API_KEY",
                    ),
                    optional = setOf(groq),
                )
            return WorkerFactory.create(environment + secrets, FirstPassTable.fromSettings(environment)).jobs
        }
    }
}
