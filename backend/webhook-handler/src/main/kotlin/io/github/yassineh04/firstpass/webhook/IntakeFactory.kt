package io.github.yassineh04.firstpass.webhook

import io.github.yassineh04.firstpass.core.webhook.JobPublisher
import io.github.yassineh04.firstpass.core.webhook.WebhookIntake
import io.github.yassineh04.firstpass.core.webhook.WebhookSecret
import io.github.yassineh04.firstpass.github.WebhookPayloadParser
import io.github.yassineh04.firstpass.persistence.DynamoDeliveryStore
import io.github.yassineh04.firstpass.persistence.FirstPassTable
import java.time.Clock

/**
 * Builds the webhook intake (design 7.1, steps 2 to 5): the signature check with [secret], the
 * GitHub payload parser, the deliveries recorded in [table] and the hand-off to [jobs], which is
 * the queue in production and a direct call locally.
 */
object IntakeFactory {
    fun create(
        secret: WebhookSecret,
        table: FirstPassTable,
        jobs: JobPublisher,
        clock: Clock = Clock.systemUTC(),
    ): WebhookIntake = WebhookIntake(secret, WebhookPayloadParser(), DynamoDeliveryStore(table), jobs, clock)
}
