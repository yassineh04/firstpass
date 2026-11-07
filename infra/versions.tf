# Terraform and provider versions. The exact provider build is pinned in .terraform.lock.hcl.
# The HCP Terraform `cloud` block (remote state) is added in phase 3, once the workspace exists.
terraform {
  required_version = ">= 1.16.7, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }
}
