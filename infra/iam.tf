# One role per Lambda, each allowed only what its code does (design 10).
locals {
  lambda_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role" "webhook" {
  name               = "${local.name}-webhook"
  assume_role_policy = local.lambda_trust_policy
}

resource "aws_iam_role_policy" "webhook" {
  name = "${local.name}-webhook"
  role = aws_iam_role.webhook.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.webhook.arn}:*"
      },
      {
        # Delivery records only: their partition keys start with DELIVERY#.
        Sid      = "RecordDeliveries"
        Effect   = "Allow"
        Action   = ["dynamodb:PutItem", "dynamodb:DeleteItem"]
        Resource = aws_dynamodb_table.firstpass.arn
        Condition = {
          "ForAllValues:StringLike" = { "dynamodb:LeadingKeys" = ["DELIVERY#*"] }
        }
      },
      {
        Sid      = "SendJobs"
        Effect   = "Allow"
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.jobs.arn
      },
      {
        Sid      = "ReadWebhookSecret"
        Effect   = "Allow"
        Action   = "ssm:GetParameters"
        Resource = "${local.ssm_parameter_arn}/github/webhook-secret"
      },
      local.decrypt_parameters,
    ]
  })
}

resource "aws_iam_role" "worker" {
  name               = "${local.name}-worker"
  assume_role_policy = local.lambda_trust_policy
}

resource "aws_iam_role_policy" "worker" {
  name = "${local.name}-worker"
  role = aws_iam_role.worker.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WriteLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.worker.arn}:*"
      },
      {
        Sid    = "UseTable"
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
          "dynamodb:Query",
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem",
        ]
        Resource = aws_dynamodb_table.firstpass.arn
      },
      {
        # What the SQS event source needs (AWSLambdaSQSQueueExecutionRole), on this queue only.
        Sid      = "ReceiveJobs"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
        Resource = aws_sqs_queue.jobs.arn
      },
      {
        Sid    = "ReadWorkerSecrets"
        Effect = "Allow"
        Action = "ssm:GetParameters"
        Resource = [
          "${local.ssm_parameter_arn}/github/client-id",
          "${local.ssm_parameter_arn}/github/private-key",
          "${local.ssm_parameter_arn}/llm/gemini-api-key",
          "${local.ssm_parameter_arn}/llm/groq-api-key",
        ]
      },
      local.decrypt_parameters,
    ]
  })
}
