package io.github.yassineh04.firstpass.aws

import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.JobPublisher
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.sqs.SqsClient

/**
 * Sends each job to the review queue (ADR 0002). A failed send answers false, so the intake
 * forgets the delivery and answers 500, and a manual redelivery from GitHub is not a duplicate.
 */
class SqsJobPublisher internal constructor(
    private val sqs: SqsClient,
    private val queueUrl: String,
    private val log: JsonLog,
) : JobPublisher {
    override fun publish(job: Job): Boolean =
        try {
            sqs.sendMessage { it.queueUrl(queueUrl).messageBody(JobJson.encode(job)) }
            true
        } catch (error: SdkException) {
            log.error(
                "The job could not be sent to the queue",
                mapOf(
                    "deliveryId" to job.deliveryId,
                    "error" to error.javaClass.simpleName,
                ),
            )
            false
        }

    companion object {
        /** On Lambda, the region and credentials come from the environment of the function. */
        fun forQueue(
            queueUrl: String,
            log: JsonLog,
        ): SqsJobPublisher =
            SqsJobPublisher(SqsClient.builder().httpClient(UrlConnectionHttpClient.create()).build(), queueUrl, log)
    }
}
