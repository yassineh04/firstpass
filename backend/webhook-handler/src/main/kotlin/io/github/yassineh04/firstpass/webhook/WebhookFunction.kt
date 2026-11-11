package io.github.yassineh04.firstpass.webhook

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.yassineh04.firstpass.aws.JsonLog
import io.github.yassineh04.firstpass.aws.SqsJobPublisher
import io.github.yassineh04.firstpass.aws.SsmSettings
import io.github.yassineh04.firstpass.core.webhook.WebhookIntake
import io.github.yassineh04.firstpass.core.webhook.WebhookRequest
import io.github.yassineh04.firstpass.core.webhook.WebhookSecret
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import java.io.InputStream
import java.io.OutputStream

/**
 * The webhook Lambda behind its function URL (design 7.1, steps 1 to 5; ADR 0001). POST is a
 * GitHub delivery, given to the intake as received; GET answers "ok" for the smoke test of each
 * deployment. Handler: `io.github.yassineh04.firstpass.webhook.WebhookFunction`.
 */
class WebhookFunction(
    private val log: JsonLog = JsonLog(),
    intake: () -> WebhookIntake = { production(log) },
) : RequestStreamHandler {
    // Built on the first request, never during SnapStart's initialization: it reads a secret.
    private val intake by lazy(intake)

    override fun handleRequest(
        input: InputStream,
        output: OutputStream,
        context: Context?,
    ) {
        val started = System.nanoTime()
        val request = FunctionUrlRequest.read(input)
        val (status, text) =
            when (request.method) {
                "POST" -> intake.receive(WebhookRequest(request.headers, request.body)).let { it.status to it.name }
                "GET" -> HTTP_OK to "ok"
                else -> HTTP_METHOD_NOT_ALLOWED to "Use POST"
            }
        FunctionUrlResponse.write(output, status, text)
        log.info(
            "Webhook answered",
            mapOf(
                "event" to request.header("X-GitHub-Event"),
                "deliveryId" to request.header("X-GitHub-Delivery"),
                "method" to request.method,
                "status" to status,
                "outcome" to text,
                "durationMs" to (System.nanoTime() - started) / NANOS_PER_MILLI,
            ),
        )
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Production wiring: the secret from SSM under `SSM_PREFIX` (default `/firstpass`), the
         * table of `DYNAMODB_TABLE` and the queue of `JOBS_QUEUE_URL`, set by Terraform.
         */
        private fun production(log: JsonLog): WebhookIntake {
            val environment = System.getenv()
            val prefix = environment["SSM_PREFIX"] ?: "/firstpass"
            val secret =
                SsmSettings
                    .create()
                    .load(mapOf("$prefix/github/webhook-secret" to "GITHUB_WEBHOOK_SECRET"))
                    .getValue("GITHUB_WEBHOOK_SECRET")
            val queue = environment["JOBS_QUEUE_URL"] ?: error("JOBS_QUEUE_URL is not set on the function")
            return IntakeFactory.create(
                WebhookSecret(secret),
                FirstPassTable.fromSettings(environment),
                SqsJobPublisher.forQueue(queue, log),
            )
        }
    }
}
