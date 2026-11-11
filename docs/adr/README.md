# Architecture decision records

Each file records one structural decision: its context, the decision, the alternatives that were rejected and the consequences. Records are never rewritten after they are accepted; a new record supersedes an old one.

To add a record, copy [template.md](template.md), give it the next number and open a pull request.

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-lambda-function-urls.md) | Lambda function URLs instead of API Gateway | Accepted |
| [0002](0002-sqs-between-webhook-and-worker.md) | SQS queue between the webhook handler and the review worker | Accepted |
| [0003](0003-plain-kotlin-lambdas-spring-boot-api.md) | Plain Kotlin for event Lambdas, Spring Boot for the dashboard API | Accepted |
| [0004](0004-dynamodb-single-table-provisioned.md) | DynamoDB single-table design in provisioned mode | Accepted |
| [0005](0005-github-app-user-authorization.md) | Dashboard sign-in through GitHub App user authorization | Accepted |
| [0006](0006-session-jwt-in-memory.md) | Session JWT kept in memory, with silent re-login | Accepted |
| [0007](0007-comment-only-reviews.md) | COMMENT-only reviews | Accepted |
| [0008](0008-config-from-base-branch.md) | Read the configuration from the base branch | Accepted |
| [0009](0009-openai-compatible-llm-client.md) | One OpenAI-compatible chat client for several LLM providers | Accepted |
| [0010](0010-hash-routing-github-pages.md) | Hash routing for the dashboard on GitHub Pages | Accepted |
| [0011](0011-terraform-with-hcp-state.md) | Terraform with state in HCP Terraform, not AWS CDK | Accepted |
| [0012](0012-spring-boot-4.md) | Spring Boot 4 for the dashboard API | Accepted |
| [0013](0013-ssm-parameters-outside-terraform.md) | SSM parameters created by hand, outside Terraform | Accepted |
| [0014](0014-low-level-dynamodb-client.md) | Low-level DynamoDB client with explicit mappers | Accepted |
| [0015](0015-json-logs-without-powertools.md) | JSON logs with a small logger, without Powertools | Accepted |
