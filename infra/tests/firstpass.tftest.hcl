# Checks the settings that keep FirstPass at $0 and its roles minimal, offline: the mocked provider
# needs no AWS account, sends nothing to AWS and invents the values AWS would compute.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }

  mock_data "aws_kms_alias" {
    defaults = { target_key_arn = "arn:aws:kms:ca-central-1:123456789012:key/11111111-2222-3333-4444-555555555555" }
  }

  # The provider still validates the shape of ARNs and versions, so the invented values must look real.
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/firstpass-mock" }
  }

  mock_resource "aws_lambda_function" {
    defaults = {
      arn           = "arn:aws:lambda:ca-central-1:123456789012:function:firstpass-mock"
      qualified_arn = "arn:aws:lambda:ca-central-1:123456789012:function:firstpass-mock:1"
      version       = "1"
    }
  }

  mock_resource "aws_lambda_alias" {
    defaults = { arn = "arn:aws:lambda:ca-central-1:123456789012:function:firstpass-mock:live" }
  }

  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:ca-central-1:123456789012:firstpass-review-jobs"
      id  = "https://sqs.ca-central-1.amazonaws.com/123456789012/firstpass-review-jobs"
      url = "https://sqs.ca-central-1.amazonaws.com/123456789012/firstpass-review-jobs"
    }
  }
}

# Distinct values where a test tells two resources apart.
override_resource {
  target = aws_sqs_queue.dead_letters
  values = { arn = "arn:aws:sqs:ca-central-1:123456789012:firstpass-review-jobs-dlq" }
}

override_resource {
  target = aws_lambda_alias.worker
  values = { arn = "arn:aws:lambda:ca-central-1:123456789012:function:firstpass-worker:live" }
}

variables {
  aws_region      = "ca-central-1"
  webhook_package = "tests/fixtures/package.zip"
  worker_package  = "tests/fixtures/package.zip"
}

run "table_stays_within_the_free_provisioned_capacity" {
  command = apply

  assert {
    condition     = aws_dynamodb_table.firstpass.billing_mode == "PROVISIONED"
    error_message = "The table must use provisioned capacity, never on-demand."
  }

  assert {
    condition = (
      aws_dynamodb_table.firstpass.read_capacity + one(aws_dynamodb_table.firstpass.global_secondary_index).read_capacity == 10 &&
      aws_dynamodb_table.firstpass.write_capacity + one(aws_dynamodb_table.firstpass.global_secondary_index).write_capacity == 10
    )
    error_message = "The table and GSI1 must have 10 read and 10 write units in all."
  }

  assert {
    condition     = aws_dynamodb_table.firstpass.ttl[0].enabled && aws_dynamodb_table.firstpass.ttl[0].attribute_name == "expiresAt"
    error_message = "TTL must be on, on expiresAt."
  }

  assert {
    condition     = !aws_dynamodb_table.firstpass.point_in_time_recovery[0].enabled
    error_message = "Point-in-time recovery is billed and must stay off."
  }
}

run "logs_are_kept_seven_days" {
  command = apply

  assert {
    condition     = aws_cloudwatch_log_group.webhook.retention_in_days == 7 && aws_cloudwatch_log_group.worker.retention_in_days == 7
    error_message = "Both log groups must keep logs 7 days."
  }

  assert {
    condition = (
      aws_lambda_function.webhook.logging_config[0].log_group == aws_cloudwatch_log_group.webhook.name &&
      aws_lambda_function.worker.logging_config[0].log_group == aws_cloudwatch_log_group.worker.name
    )
    error_message = "Each function must log to the group Terraform created, whose retention is set."
  }
}

run "functions_use_snapstart_through_their_live_alias" {
  command = apply

  assert {
    condition = alltrue([
      for function in [aws_lambda_function.webhook, aws_lambda_function.worker] :
      function.runtime == "java21" && function.publish && function.snap_start[0].apply_on == "PublishedVersions"
    ])
    error_message = "Both functions must run Java 21 and publish versions for SnapStart."
  }

  assert {
    condition     = aws_lambda_function.webhook.timeout == 10 && aws_lambda_function.webhook.memory_size == 512
    error_message = "The webhook must answer within 10 seconds with 512 MB."
  }

  assert {
    condition     = aws_lambda_function.worker.timeout == 300 && aws_lambda_function.worker.memory_size == 1024
    error_message = "The worker must have 5 minutes and 1024 MB."
  }

  assert {
    condition     = aws_lambda_function_url.webhook.qualifier == "live" && aws_lambda_function_url.webhook.authorization_type == "NONE"
    error_message = "The webhook URL must point to the live alias; the signature is checked in the code."
  }

  assert {
    condition     = aws_lambda_alias.webhook.function_version == aws_lambda_function.webhook.version
    error_message = "The live alias must follow the last published version."
  }
}

run "the_queue_feeds_at_most_two_workers_and_keeps_failed_jobs_aside" {
  command = apply

  assert {
    condition = (
      aws_lambda_event_source_mapping.worker.function_name == aws_lambda_alias.worker.arn &&
      aws_lambda_event_source_mapping.worker.batch_size == 1 &&
      aws_lambda_event_source_mapping.worker.scaling_config[0].maximum_concurrency == 2 &&
      contains(aws_lambda_event_source_mapping.worker.function_response_types, "ReportBatchItemFailures")
    )
    error_message = "The worker's live alias must get one job at a time, at most 2 at once, with partial batch failures."
  }

  assert {
    condition     = aws_sqs_queue.jobs.visibility_timeout_seconds == 6 * aws_lambda_function.worker.timeout
    error_message = "The visibility timeout must be six times the worker timeout."
  }

  assert {
    condition = (
      jsondecode(aws_sqs_queue_redrive_policy.jobs.redrive_policy).maxReceiveCount == 3 &&
      jsondecode(aws_sqs_queue_redrive_policy.jobs.redrive_policy).deadLetterTargetArn == aws_sqs_queue.dead_letters.arn
    )
    error_message = "A job must move to the dead-letter queue after 3 attempts."
  }

  assert {
    condition     = aws_sqs_queue.jobs.sqs_managed_sse_enabled && aws_sqs_queue.dead_letters.sqs_managed_sse_enabled
    error_message = "Both queues must be encrypted with the free SQS-managed keys."
  }
}

run "roles_allow_only_what_each_function_does" {
  command = apply

  assert {
    condition = alltrue([
      for statement in concat(
        jsondecode(aws_iam_role_policy.webhook.policy).Statement,
        jsondecode(aws_iam_role_policy.worker.policy).Statement,
      ) : statement.Resource != "*"
    ])
    error_message = "No statement may apply to every resource."
  }

  assert {
    condition = (
      [for s in jsondecode(aws_iam_role_policy.webhook.policy).Statement : s if s.Sid == "RecordDeliveries"][0].Action == ["dynamodb:PutItem", "dynamodb:DeleteItem"] &&
      [for s in jsondecode(aws_iam_role_policy.webhook.policy).Statement : s if s.Sid == "RecordDeliveries"][0].Condition["ForAllValues:StringLike"]["dynamodb:LeadingKeys"] == ["DELIVERY#*"]
    )
    error_message = "The webhook may only write and delete delivery records."
  }

  assert {
    condition     = [for s in jsondecode(aws_iam_role_policy.webhook.policy).Statement : s if s.Sid == "ReadWebhookSecret"][0].Resource == "arn:aws:ssm:ca-central-1:123456789012:parameter/firstpass/github/webhook-secret"
    error_message = "The webhook may read the webhook secret only."
  }

  assert {
    condition = alltrue([
      for policy in [aws_iam_role_policy.webhook.policy, aws_iam_role_policy.worker.policy] :
      [for s in jsondecode(policy).Statement : s if s.Sid == "DecryptParameters"][0].Condition.StringEquals["kms:ViaService"] == "ssm.ca-central-1.amazonaws.com"
    ])
    error_message = "kms:Decrypt must be limited to calls made through SSM."
  }

  assert {
    condition = !contains(flatten([
      for s in jsondecode(aws_iam_role_policy.worker.policy).Statement : s.Action
    ]), "dynamodb:Scan")
    error_message = "No role may scan the table (design 8: no Scan)."
  }
}

run "alarms_watch_the_dead_letters_and_the_worker" {
  command = apply

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.dead_letters.dimensions["QueueName"] == aws_sqs_queue.dead_letters.name &&
      aws_cloudwatch_metric_alarm.dead_letters.threshold == 0 &&
      aws_cloudwatch_metric_alarm.dead_letters.comparison_operator == "GreaterThanThreshold"
    )
    error_message = "The dead-letter alarm must fire as soon as one job is there."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.worker_errors.threshold == 5 &&
      aws_cloudwatch_metric_alarm.worker_errors.period == 3600 &&
      aws_cloudwatch_metric_alarm.worker_errors.statistic == "Sum"
    )
    error_message = "The worker alarm must fire after more than 5 errors in an hour."
  }

  assert {
    condition     = output.webhook_url == aws_lambda_function_url.webhook.function_url
    error_message = "The webhook URL output must be the URL of the live alias."
  }
}
