# The two alarms of design 11, among the 10 alarms CloudWatch offers for free. Their notification
# (SNS or a scheduled GitHub Actions workflow) is chosen once the account's Free Tier page is checked.

# A job failed three times and was moved aside: someone must look at it.
resource "aws_cloudwatch_metric_alarm" "dead_letters" {
  alarm_name          = "${local.name}-dead-letters"
  alarm_description   = "A review job failed 3 times and is in the dead-letter queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dead_letters.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  # An idle queue sends no data, which is the normal state.
  treat_missing_data = "notBreaching"
}

# Crashes and timeouts of the worker. A job that fails is not one of them: it is reported to SQS,
# retried, then caught by the dead-letter alarm.
resource "aws_cloudwatch_metric_alarm" "worker_errors" {
  alarm_name          = "${local.name}-worker-errors"
  alarm_description   = "The review worker crashed or timed out more than 5 times in an hour."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.worker.function_name }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}
