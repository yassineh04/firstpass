# 0013. SSM parameters created by hand, outside Terraform

- Status: Accepted
- Date: 2026-11-06

## Context

FirstPass needs eight configuration values in SSM Parameter Store: the GitHub App ID, private key, webhook secret, client ID and client secret, two LLM API keys, and the session signing key. Six are secrets and use the `SecureString` type with the AWS managed KMS key, which has no monthly fee ([KMS pricing](https://aws.amazon.com/kms/pricing/)).

If Terraform managed these parameters, their values would be written to the Terraform state, which is stored in HCP Terraform. Reading them with a Terraform data source would also copy them into the state. CloudFormation-based tools could not create them at all, because CloudFormation does not support the `SecureString` type ([`AWS::SSM::Parameter`](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-parameter.html)).

## Decision

- The maintainer creates the parameters once, by hand, with `aws ssm put-parameter` under the `/firstpass/` path. The App ID and the client ID are `String`; the six others are `SecureString`.
- Terraform only builds the parameter ARNs to grant each Lambda role read access to the paths it needs. It never declares the parameters as resources and never reads them with a data source.
- Lambdas read the values once per cold start and keep them in memory. They never log them.

## Alternatives considered

- **`aws_ssm_parameter` resources in Terraform.** Fully declarative, but the secret values end up in the state.
- **AWS Secrets Manager.** Built-in rotation, but billed per secret per month.
- **Lambda environment variables.** Simple, but values become visible in the console and in the Terraform configuration.

## Consequences

- Positive: secret values exist only in SSM and in Lambda memory.
- Negative: one manual setup step per environment, and rotation is manual. Both are documented in the setup guide and in `SECURITY.md`.
