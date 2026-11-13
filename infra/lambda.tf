# The two event Lambdas (design 6): plain Kotlin on Java 21, SnapStart on published versions, an
# alias "live" that triggers and URLs point to, and packages uploaded directly, without S3 (ADR 0011).
locals {
  runtime = "java21"
  # Logs stay 7 days: the free allowance covers them, and CloudWatch would keep them forever otherwise.
  log_retention_days = 7
}

resource "aws_cloudwatch_log_group" "webhook" {
  #checkov:skip=CKV_AWS_158:A customer managed KMS key costs 1 USD a month; CloudWatch encrypts logs by default.
  #checkov:skip=CKV_AWS_338:Logs are kept 7 days on purpose, within the free allowance (design 11).
  name              = "/aws/lambda/${local.name}-webhook"
  retention_in_days = local.log_retention_days
}

resource "aws_cloudwatch_log_group" "worker" {
  #checkov:skip=CKV_AWS_158:A customer managed KMS key costs 1 USD a month; CloudWatch encrypts logs by default.
  #checkov:skip=CKV_AWS_338:Logs are kept 7 days on purpose, within the free allowance (design 11).
  name              = "/aws/lambda/${local.name}-worker"
  retention_in_days = local.log_retention_days
}

resource "aws_lambda_function" "webhook" {
  #checkov:skip=CKV_AWS_50:X-Ray is outside the $0 services; JSON logs carry the delivery id instead.
  #checkov:skip=CKV_AWS_115:Reserved concurrency would take from a small account pool; GitHub sends few webhooks.
  #checkov:skip=CKV_AWS_116:Called synchronously through its URL: a failure answers 500, and GitHub can redeliver.
  #checkov:skip=CKV_AWS_117:No VPC: a NAT gateway would be needed to reach GitHub, and it is billed.
  #checkov:skip=CKV_AWS_173:The environment holds no secret, only names; secrets come from SSM.
  #checkov:skip=CKV_AWS_272:Code signing needs a signing profile outside the $0 services; CI builds the package.
  function_name    = "${local.name}-webhook"
  role             = aws_iam_role.webhook.arn
  runtime          = local.runtime
  handler          = "io.github.yassineh04.firstpass.webhook.WebhookFunction"
  filename         = var.webhook_package
  source_code_hash = filebase64sha256(var.webhook_package)
  memory_size      = 512
  timeout          = 10
  publish          = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.webhook.name
  }

  environment {
    variables = {
      DYNAMODB_TABLE = aws_dynamodb_table.firstpass.name
      JOBS_QUEUE_URL = aws_sqs_queue.jobs.url
      SSM_PREFIX     = var.ssm_prefix
    }
  }

  depends_on = [aws_iam_role_policy.webhook]
}

resource "aws_lambda_alias" "webhook" {
  name             = "live"
  function_name    = aws_lambda_function.webhook.function_name
  function_version = aws_lambda_function.webhook.version
}

# Public on purpose: the code checks GitHub's signature before anything else (ADR 0001). With
# NONE, the provider adds the two resource-based permissions the URL needs.
resource "aws_lambda_function_url" "webhook" {
  #checkov:skip=CKV_AWS_258:GitHub cannot sign AWS requests; the webhook signature is checked in the code (ADR 0001).
  function_name      = aws_lambda_function.webhook.function_name
  qualifier          = aws_lambda_alias.webhook.name
  authorization_type = "NONE"
}

resource "aws_lambda_function" "worker" {
  #checkov:skip=CKV_AWS_50:X-Ray is outside the $0 services; JSON logs carry the delivery id instead.
  #checkov:skip=CKV_AWS_115:The SQS event source limits the worker to 2 concurrent runs instead.
  #checkov:skip=CKV_AWS_116:Failed jobs go to the dead-letter queue of the SQS queue, not of the function.
  #checkov:skip=CKV_AWS_117:No VPC: a NAT gateway would be needed to reach GitHub and the AI providers, and it is billed.
  #checkov:skip=CKV_AWS_173:The environment holds no secret, only names; secrets come from SSM.
  #checkov:skip=CKV_AWS_272:Code signing needs a signing profile outside the $0 services; CI builds the package.
  function_name    = "${local.name}-worker"
  role             = aws_iam_role.worker.arn
  runtime          = local.runtime
  handler          = "io.github.yassineh04.firstpass.worker.WorkerFunction"
  filename         = var.worker_package
  source_code_hash = filebase64sha256(var.worker_package)
  memory_size      = 1024
  timeout          = local.worker_timeout_seconds
  publish          = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.worker.name
  }

  environment {
    variables = {
      DYNAMODB_TABLE             = aws_dynamodb_table.firstpass.name
      FIRSTPASS_LLM_PROVIDER     = var.llm_provider
      FIRSTPASS_TRANSPARENCY_URL = var.transparency_url
      SSM_PREFIX                 = var.ssm_prefix
    }
  }

  depends_on = [aws_iam_role_policy.worker]
}

resource "aws_lambda_alias" "worker" {
  name             = "live"
  function_name    = aws_lambda_function.worker.function_name
  function_version = aws_lambda_function.worker.version
}

# One job at a time per run, at most 2 runs at once (the minimum the setting allows), and only the
# failed messages given back to the queue.
resource "aws_lambda_event_source_mapping" "worker" {
  event_source_arn        = aws_sqs_queue.jobs.arn
  function_name           = aws_lambda_alias.worker.arn
  batch_size              = 1
  function_response_types = ["ReportBatchItemFailures"]

  scaling_config {
    maximum_concurrency = 2
  }
}
