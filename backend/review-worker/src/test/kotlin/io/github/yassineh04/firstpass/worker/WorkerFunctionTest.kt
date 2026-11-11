package io.github.yassineh04.firstpass.worker

import io.github.yassineh04.firstpass.aws.JobJson
import io.github.yassineh04.firstpass.aws.JsonLog
import io.github.yassineh04.firstpass.core.job.JobOutcome
import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

class WorkerFunctionTest {
    private val json = JsonMapper.builder().build()
    private val logs = ByteArrayOutputStream()
    private val handled = mutableListOf<Job>()
    private var handlersBuilt = 0

    private val function =
        WorkerFunction(JsonLog(PrintStream(logs, true, Charsets.UTF_8))) {
            handlersBuilt++
            { job ->
                check(job.deliveryId != "d-broken") { "GitHub answered 502" }
                handled += job
                JobOutcome.ClosingRecorded(known = true)
            }
        }

    private fun job(deliveryId: String) =
        Job.Installation(
            deliveryId,
            Instant.parse("2026-11-12T10:00:00Z"),
            42,
            InstallationChange.REPOSITORIES_ADDED,
            null,
            null,
            listOf(RepositoryRef(7, "octo/sandbox", false)),
        )

    /** An SQS event as the Lambda documentation shows it, with one record per message body. */
    private fun event(vararg bodies: Pair<String, String>): ByteArray {
        val event = json.createObjectNode()
        val records = event.putArray("Records")
        bodies.forEach { (messageId, body) ->
            records.addObject().apply {
                put("messageId", messageId)
                put("receiptHandle", "handle-$messageId")
                put("body", body)
                putObject("attributes").put("ApproximateReceiveCount", "1").put("SentTimestamp", "1762941600000")
                putObject("messageAttributes")
                put("eventSource", "aws:sqs")
                put("eventSourceARN", "arn:aws:sqs:ca-central-1:123456789012:firstpass-review-jobs")
                put("awsRegion", "ca-central-1")
            }
        }
        return json.writeValueAsBytes(event)
    }

    private fun call(event: ByteArray): List<String> {
        val output = ByteArrayOutputStream()
        function.handleRequest(event.inputStream(), output, null)
        return json.readTree(output.toByteArray()).path("batchItemFailures").values().map {
            it.path("itemIdentifier").asString()
        }
    }

    @Test
    fun `handles each job and reports none as failed when all succeed`() {
        call(event("m-1" to JobJson.encode(job("d-1")), "m-2" to JobJson.encode(job("d-2")))) shouldBe emptyList()

        handled.map { it.deliveryId } shouldContainExactly listOf("d-1", "d-2")
        handlersBuilt shouldBe 1
        logs.toString(Charsets.UTF_8) shouldContain "\"deliveryId\":\"d-1\""
    }

    @Test
    fun `reports only the messages that failed, so that SQS gives back only those`() {
        val failures =
            call(
                event(
                    "m-1" to JobJson.encode(job("d-broken")),
                    "m-2" to "{not a job",
                    "m-3" to JobJson.encode(job("d-3")),
                ),
            )

        failures shouldContainExactly listOf("m-1", "m-2")
        handled.map { it.deliveryId } shouldContainExactly listOf("d-3")
        val lines = logs.toString(Charsets.UTF_8)
        lines shouldContain "\"messageId\":\"m-1\""
        lines shouldContain "IllegalStateException"
        lines shouldContain "MalformedJobException"
    }
}
