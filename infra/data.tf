# The account that runs the configuration, to build the ARNs of the SSM parameters.
data "aws_caller_identity" "current" {}

# The AWS managed key that encrypts the SecureString parameters. It is created by AWS with the
# first SecureString parameter, so the parameters must exist before the first apply (ADR 0013).
data "aws_kms_alias" "ssm" {
  name = "alias/aws/ssm"
}

locals {
  name = "firstpass"

  # The ARN of each SSM parameter a function reads; never its value (ADR 0013).
  ssm_parameter_arn = "arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_prefix}"

  # Decrypting a SecureString parameter needs kms:Decrypt, even with the AWS managed key; the
  # condition limits it to calls made through SSM.
  decrypt_parameters = {
    Sid      = "DecryptParameters"
    Effect   = "Allow"
    Action   = "kms:Decrypt"
    Resource = data.aws_kms_alias.ssm.target_key_arn
    Condition = {
      StringEquals = { "kms:ViaService" = "ssm.${var.aws_region}.amazonaws.com" }
    }
  }
}
