package io.github.yassineh04.firstpass.aws

import io.github.yassineh04.firstpass.core.webhook.InstallationChange
import io.github.yassineh04.firstpass.core.webhook.Job
import io.github.yassineh04.firstpass.core.webhook.RepositoryRef
import io.github.yassineh04.firstpass.core.webhook.ReviewTrigger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

class JobJsonTest {
    private val at = Instant.parse("2026-11-12T10:00:00.250Z")
    private val sandbox = RepositoryRef(7, "octo/sandbox", private = false)

    @Test
    fun `writes a review job with the field names the queue relies on`() {
        val job = Job.Review("d-1", at, 42, sandbox, 3, "head1", "base1", ReviewTrigger.SYNCHRONIZE)

        JobJson.encode(job) shouldBe
            """{"deliveryId":"d-1","receivedAt":"2026-11-12T10:00:00.250Z","installationId":42,"type":"review",""" +
            """"repository":{"id":7,"fullName":"octo/sandbox","private":false},"pullRequest":3,"headSha":"head1",""" +
            """"baseSha":"base1","trigger":"SYNCHRONIZE"}"""
    }

    @Test
    fun `reads back every kind of job exactly as it was written`() {
        val jobs =
            listOf(
                Job.Review("d-1", at, 42, sandbox, 3, "head1", "base1", ReviewTrigger.OPENED),
                Job.PullRequestClosed("d-2", at, 42, sandbox, 3, merged = true),
                Job.Installation("d-3", at, 42, InstallationChange.CREATED, "octo", "Organization", listOf(sandbox)),
                Job.Installation("d-4", at, 42, InstallationChange.REPOSITORIES_REMOVED, null, null, emptyList()),
            )

        jobs.forEach { JobJson.decode(JobJson.encode(it)) shouldBe it }
    }

    @Test
    fun `refuses a message that is not a job, so that it ends in the dead-letter queue`() {
        val valid = JobJson.encode(Job.PullRequestClosed("d-2", at, 42, sandbox, 3, merged = false))

        shouldThrow<MalformedJobException> { JobJson.decode("not JSON") }
        shouldThrow<MalformedJobException> {
            JobJson.decode(
                valid.replace("pr_closed", "deploy"),
            )
        }.message shouldContain
            "unknown type"
        shouldThrow<MalformedJobException> { JobJson.decode(valid.replace("\"merged\":false", "\"merged\":\"no\"")) }
        shouldThrow<MalformedJobException> { JobJson.decode(valid.replace("2026-11-12T10:00:00.250Z", "yesterday")) }
        val review = JobJson.encode(Job.Review("d-1", at, 42, sandbox, 3, "head1", "base1", ReviewTrigger.OPENED))
        shouldThrow<MalformedJobException> { JobJson.decode(review.replace("OPENED", "LABELED")) }.message shouldContain
            "LABELED"
    }
}
