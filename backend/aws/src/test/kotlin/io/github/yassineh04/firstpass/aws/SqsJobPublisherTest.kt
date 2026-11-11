package io.github.yassineh04.firstpass.aws

import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant

class SqsJobPublisherTest {
    private val job =
        Job.PullRequestClosed(
            "d-9",
            Instant.parse("2026-11-12T10:00:00Z"),
            42,
            RepositoryRef(7, "octo/sandbox", false),
            3,
            true,
        )
    private val logs = ByteArrayOutputStream()
    private val log = JsonLog(PrintStream(logs, true, Charsets.UTF_8))

    /** An SQS that records what it receives, or fails like a network error. */
    private class FakeSqs(
        private val failing: Boolean = false,
    ) : SqsClient {
        val sent = mutableListOf<SendMessageRequest>()

        override fun serviceName() = "sqs"

        override fun close() = Unit

        override fun sendMessage(request: SendMessageRequest): SendMessageResponse {
            if (failing) throw SdkClientException.create("Unable to execute HTTP request")
            sent += request
            return SendMessageResponse.builder().messageId("m-1").build()
        }
    }

    @Test
    fun `sends the job as JSON to the review queue`() {
        val sqs = FakeSqs()

        SqsJobPublisher(sqs, QUEUE, log).publish(job) shouldBe true

        val request = sqs.sent.single()
        request.queueUrl() shouldBe QUEUE
        JobJson.decode(request.messageBody()) shouldBe job
    }

    @Test
    fun `answers false when SQS cannot be reached, so that the delivery is forgotten`() {
        SqsJobPublisher(FakeSqs(failing = true), QUEUE, log).publish(job) shouldBe false

        val line = logs.toString(Charsets.UTF_8)
        line shouldContain "\"deliveryId\":\"d-9\""
        line shouldContain "SdkClientException"
        line shouldNotContain "octo/sandbox"
    }

    private companion object {
        const val QUEUE = "https://sqs.ca-central-1.amazonaws.com/123456789012/firstpass-review-jobs"
    }
}
