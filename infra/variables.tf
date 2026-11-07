variable "aws_region" {
  description = "AWS Region that hosts FirstPass, for example ca-central-1. Lambda SnapStart must be available there."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.aws_region))
    error_message = "The aws_region value must be an AWS Region code such as ca-central-1."
  }
}
