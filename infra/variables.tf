variable "aws_region" {
  description = "AWS Region that hosts FirstPass, for example ca-central-1. Lambda SnapStart must be available there."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.aws_region))
    error_message = "The aws_region value must be an AWS Region code such as ca-central-1."
  }
}

variable "webhook_package" {
  description = "Path of the webhook Lambda package, built by ./gradlew :webhook-handler:lambdaZip in backend/."
  type        = string
  default     = "../backend/webhook-handler/build/lambda/webhook-handler.zip"
}

variable "worker_package" {
  description = "Path of the review worker Lambda package, built by ./gradlew :review-worker:lambdaZip in backend/."
  type        = string
  default     = "../backend/review-worker/build/lambda/review-worker.zip"
}

variable "ssm_prefix" {
  description = "Path under which the SSM parameters were created by hand (ADR 0013), without a trailing slash."
  type        = string
  default     = "/firstpass"

  validation {
    condition     = can(regex("^(/[A-Za-z0-9_.-]+)+$", var.ssm_prefix))
    error_message = "The ssm_prefix value must start with / and must not end with /, for example /firstpass."
  }
}

variable "llm_provider" {
  description = "Main review provider of the worker: gemini, with Groq as fallback when its key exists, or groq."
  type        = string
  default     = "gemini"

  validation {
    condition     = contains(["gemini", "groq"], var.llm_provider)
    error_message = "The llm_provider value must be gemini or groq."
  }
}

variable "transparency_url" {
  description = "Page linked from every review, explaining what is sent to the AI providers and what is kept."
  type        = string
  default     = "https://github.com/yassineh04/firstpass#readme"
}
