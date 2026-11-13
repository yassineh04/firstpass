# Deploying FirstPass to your own AWS account

> **This guide has not been run against a real account.** This project is kept ready to deploy but is not deployed, so that no payment method is used anywhere ([ADR 0016](adr/0016-deployment-ready-without-a-cloud-account.md)). The configuration it applies is checked on every pull request (`terraform validate`, `terraform test` with a mocked provider, TFLint and Checkov), but the steps below have never met a real AWS account. Read each command before you run it.

FirstPass is designed for AWS always-free allowances: two Lambda functions, one SQS queue and its dead-letter queue, one provisioned DynamoDB table, SSM parameters, CloudWatch logs and alarms. No S3, no API Gateway, no VPC. Your account, its plan and any charge remain your responsibility.

## Prerequisites

- An AWS account. AWS may ask for a payment method at sign-up. On the Free plan, AWS states that nothing is charged unless you move to a paid plan, but the account closes after six months ([AWS Free Tier](https://aws.amazon.com/free/)).
- A Region where Lambda SnapStart is available: all commercial Regions except Asia Pacific (New Zealand) and Asia Pacific (Taipei), according to the [SnapStart documentation](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html). The examples use `ca-central-1`.
- The [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html), signed in with an administrator identity that is not the root user ([IAM Identity Center](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html) gives temporary credentials).
- Terraform 1.16.7 (or its Docker image), JDK 21.
- A **production GitHub App**, created like the development app of [github-app-setup.md](github-app-setup.md) (steps 2 to 5), with its own webhook secret and private key converted to PKCS#8. Leave its webhook inactive for now: its URL exists only after the first apply.

## 1. Set a budget alert first

In the console, open **Billing and Cost Management**, then **Budgets**, **Create budget**, **Use a template (simplified)** and **Zero spend budget**. It emails you as soon as spending exceeds the free allowances. It warns; it does not stop anything.

## 2. Create the secrets in SSM Parameter Store

Terraform never sees these values (ADR 0013). Create them before the first apply: the AWS managed key `aws/ssm`, which Terraform looks up, is created with the first SecureString parameter.

Put each secret in a file outside the repository, pass it with `file://`, then delete the file, so that no secret stays in your shell history.

```bash
aws ssm put-parameter --name /firstpass/github/client-id      --type String       --value "<Client ID of the production app>"
aws ssm put-parameter --name /firstpass/github/private-key    --type SecureString --value file://path/to/production-key.pkcs8.pem
aws ssm put-parameter --name /firstpass/github/webhook-secret --type SecureString --value file://path/to/webhook-secret.txt
aws ssm put-parameter --name /firstpass/llm/gemini-api-key    --type SecureString --value file://path/to/gemini-key.txt
# Optional: Groq as the fallback provider.
aws ssm put-parameter --name /firstpass/llm/groq-api-key      --type SecureString --value file://path/to/groq-key.txt
```

## 3. Build the Lambda packages

```bash
cd backend
./gradlew :webhook-handler:lambdaZip :review-worker:lambdaZip
```

The packages land in `backend/webhook-handler/build/lambda/` and `backend/review-worker/build/lambda/`. The build fails if one exceeds the 50 MB a direct upload allows.

## 4. Apply the configuration

```bash
cd infra
terraform init
terraform apply -var aws_region=ca-central-1
```

Without a `backend` or `cloud` block, the state is the local file `infra/terraform.tfstate`, which git ignores. It holds resource identifiers and no secret value, but you need it to update or remove the deployment: keep it safe.

## 5. Connect the GitHub App

```bash
terraform output -raw webhook_url
curl "$(terraform output -raw webhook_url)"
```

`curl` must print `ok`: the function answers `GET` without reading any secret. A `403` means the function URL lacks its resource-based permissions; check them with `aws lambda get-policy --function-name firstpass-webhook --qualifier live`.

In the production app's settings, set the webhook URL to that address, make the webhook active and save. GitHub then sends a `ping` event: under **Advanced**, **Recent deliveries**, it must show a `200` response.

## 6. Try a review

Install the production app on a test repository and open a pull request. Follow the worker's logs:

```bash
aws logs tail /aws/lambda/firstpass-worker --follow
```

## 7. Remove everything

The table has deletion protection. Turn it off, then destroy the configuration and delete the parameters:

```bash
aws dynamodb update-table --table-name firstpass --no-deletion-protection-enabled
terraform destroy -var aws_region=ca-central-1
aws ssm delete-parameters --names /firstpass/github/client-id /firstpass/github/private-key /firstpass/github/webhook-secret /firstpass/llm/gemini-api-key /firstpass/llm/groq-api-key
```
