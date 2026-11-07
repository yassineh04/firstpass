provider "aws" {
  region = var.aws_region

  # Every resource is tagged, so FirstPass resources are easy to find in the console and the bill.
  default_tags {
    tags = {
      Project   = "firstpass"
      ManagedBy = "terraform"
    }
  }
}
