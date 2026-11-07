# 0011. Terraform with state in HCP Terraform, not AWS CDK

- Status: Accepted
- Date: 2026-11-06

## Context

The project must cost $0 per month, including after AWS credits expire.

- In CloudFormation, the code of a Java Lambda function must come from an S3 object or an ECR image; inline code is only for Node.js and Python ([Lambda `Code` property](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-lambda-function-code.html)).
- AWS CDK therefore needs a bootstrap that creates an S3 bucket and an ECR repository ([CDK bootstrapping](https://docs.aws.amazon.com/cdk/v2/guide/bootstrapping.html)). AWS SAM is built on CloudFormation and has the same need.
- The S3 pricing page shows no always-free allowance for S3, only credits ([S3 pricing](https://aws.amazon.com/s3/pricing/)).
- The Lambda API accepts a zipped package of up to 50 MB uploaded directly, and up to 250 MB unzipped including at most 5 layers ([Lambda quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html)).

## Decision

Infrastructure is written in Terraform (HCL) with the AWS provider.

- Lambda packages are uploaded directly through the Lambda API (`filename` argument), never through S3.
- If a package exceeds 50 MB zipped (most likely the Spring Boot API), dependencies move to Lambda layers.
- The Terraform state lives in a workspace of HCP Terraform Free, which allows up to 500 managed resources ([HCP Terraform pricing](https://www.hashicorp.com/products/terraform/pricing)); we expect about 40.
- `terraform test`, tflint and Checkov run in CI; a test fails if the configuration contains a resource type outside the allowed list.

## Alternatives considered

- **AWS CDK in TypeScript.** Same language as the frontend and typed constructs, but it requires the S3 bootstrap bucket.
- **AWS SAM.** Same S3 requirement.
- **Pulumi in TypeScript with Pulumi Cloud Free.** Free state and same language as the frontend, but direct upload of Lambda code is not stated in its documentation, and it is less common in job offers.
- **OpenTofu with encrypted state committed to git.** No third-party account, but unusual, with no locking.

## Consequences

- Positive: no S3 anywhere, so no storage bill; Terraform is the most widespread infrastructure-as-code tool.
- Negative: a new language (HCL) and a HashiCorp account. Terraform CLI uses the Business Source License, which allows our use.
- Negative: we depend on a third-party free tier. The state can be exported if the offer changes.
- Negative: the 50 MB direct-upload limit constrains package size.
