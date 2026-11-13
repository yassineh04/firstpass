# The review queue between the webhook and the worker, and its dead-letter queue (ADR 0002).
locals {
  worker_timeout_seconds = 300

  # Three attempts per job: each one may spend model calls, which have daily free limits.
  max_receive_count = 3
}

resource "aws_sqs_queue" "jobs" {
  name = "${local.name}-review-jobs"
  # Six times the worker timeout, so that a message is never given to a second worker mid-review.
  visibility_timeout_seconds = 6 * local.worker_timeout_seconds
  sqs_managed_sse_enabled    = true
}

resource "aws_sqs_queue" "dead_letters" {
  name                      = "${local.name}-review-jobs-dlq"
  message_retention_seconds = 14 * 24 * 60 * 60
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue_redrive_policy" "jobs" {
  queue_url = aws_sqs_queue.jobs.id
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letters.arn
    maxReceiveCount     = local.max_receive_count
  })
}
