# Runs offline: the mocked provider needs no AWS account and no credentials.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

variables {
  webhook_package = "tests/fixtures/package.zip"
  worker_package  = "tests/fixtures/package.zip"
}

run "accepts_a_region_code" {
  command = plan

  variables {
    aws_region = "ca-central-1"
  }
}

run "rejects_a_value_that_is_not_a_region_code" {
  command = plan

  variables {
    aws_region = "Canada"
  }

  expect_failures = [var.aws_region]
}

run "rejects_an_ssm_prefix_with_a_trailing_slash" {
  command = plan

  variables {
    aws_region = "ca-central-1"
    ssm_prefix = "/firstpass/"
  }

  expect_failures = [var.ssm_prefix]
}

run "rejects_a_provider_the_worker_cannot_use" {
  command = plan

  variables {
    aws_region   = "ca-central-1"
    llm_provider = "openai"
  }

  expect_failures = [var.llm_provider]
}
