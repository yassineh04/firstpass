output "webhook_url" {
  description = "Webhook URL to set in the production GitHub App: the function URL of the live alias."
  value       = aws_lambda_function_url.webhook.function_url
}

output "review_queue_url" {
  description = "URL of the review queue."
  value       = aws_sqs_queue.jobs.url
}

output "dead_letter_queue_name" {
  description = "Name of the dead-letter queue, where jobs that failed 3 times wait."
  value       = aws_sqs_queue.dead_letters.name
}

output "table_name" {
  description = "Name of the DynamoDB table."
  value       = aws_dynamodb_table.firstpass.name
}
