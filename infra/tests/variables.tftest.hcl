# Runs offline: the mocked provider needs no AWS account and no credentials.
mock_provider "aws" {}

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
