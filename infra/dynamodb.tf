# The single table of FirstPass (design 8, ADR 0004), defined like TableSetup in the persistence
# module: provisioned mode with 5 + 5 read units and 5 + 5 write units, the always-free allowance.
resource "aws_dynamodb_table" "firstpass" {
  #checkov:skip=CKV_AWS_28:Point-in-time recovery is billed; the data can be rebuilt from GitHub.
  #checkov:skip=CKV_AWS_119:A customer managed KMS key costs 1 USD a month; the AWS owned key encrypts the table.
  #checkov:skip=CKV2_AWS_16:Auto scaling could raise the capacity above the free allowance; it stays fixed on purpose.
  name                        = local.name
  billing_mode                = "PROVISIONED"
  read_capacity               = 5
  write_capacity              = 5
  hash_key                    = "pk"
  range_key                   = "sk"
  deletion_protection_enabled = true

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  attribute {
    name = "gsi1pk"
    type = "S"
  }

  attribute {
    name = "gsi1sk"
    type = "S"
  }

  global_secondary_index {
    name            = "GSI1"
    projection_type = "ALL"
    read_capacity   = 5
    write_capacity  = 5

    key_schema {
      attribute_name = "gsi1pk"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "gsi1sk"
      key_type       = "RANGE"
    }
  }

  # TTL deletes expired items within a few days; the code also ignores items past expiresAt.
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  # Written out so that a test can check it: point-in-time recovery is billed.
  point_in_time_recovery {
    enabled = false
  }
}
