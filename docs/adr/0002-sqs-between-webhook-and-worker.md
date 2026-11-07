# 0002. SQS queue between the webhook handler and the review worker

- Status: Accepted
- Date: 2026-11-06

## Context

GitHub waits at most 10 seconds for a 2XX answer to a webhook delivery; after that, it closes the connection and records a failure ([best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks)). A review takes much longer: fetching the diff, several LLM calls, validation and publication.

GitHub does not redeliver failed deliveries automatically ([handling failed deliveries](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries)). A manual redelivery keeps the same `X-GitHub-Delivery` header.

## Decision

The webhook handler only verifies the signature, records the delivery ID for idempotence, sends a typed job (`review`, `command`, `installation`, `pr_closed`) to a standard SQS queue and answers `202`. A separate worker Lambda consumes the queue with a batch size of 1 and a maximum concurrency of 2.

- Visibility timeout: 30 minutes, six times the 5-minute worker timeout.
- After 3 failed receives, a message moves to a dead-letter queue, which raises an alarm.
- If sending to SQS fails, the handler deletes the delivery record and answers `500`, so that a manual redelivery is not rejected as a duplicate.

## Alternatives considered

- **Review inside the handler.** Cannot fit in 10 seconds, and a failure would lose the event.
- **Asynchronous Lambda invocation.** No queue to poll, built-in retries and an on-failure destination. Kept as plan B (see below), but it offers no `maximumConcurrency` cap, and the cap protects the free LLM rate limits.

## Consequences

- Positive: the handler answers in milliseconds; retries and the dead-letter queue come for free; bursts of pull requests are absorbed.
- Negative: SQS is at-least-once, so every worker step must be idempotent (review run status and finding fingerprints).
- Negative: Lambda polls the queue continuously, and each poll is billed as an SQS request ([SQS scaling](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-scaling.html)). Our estimate is 260,000 to 650,000 requests per month when idle, under the 1 million free requests. It is measured in phase 3; if it gets close to the limit, the handler switches to asynchronous invocation with the dead-letter queue as the failure destination.
