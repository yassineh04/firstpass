# Terraform and provider versions. The exact provider build is pinned in .terraform.lock.hcl.
# The HCP Terraform `cloud` block (remote state) is added in phase 3, once the workspace exists.
terraform {
  # CI runs Terraform 1.16.7. The minimum stays at 1.15 because Dependabot supports
  # Terraform up to 1.15.x when it updates the provider lock file.
  required_version = ">= 1.15.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }
}
