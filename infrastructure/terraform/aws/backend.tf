# =============================================================================
# Remote State – S3 + DynamoDB lock
# Bootstrap: see README.md "Infrastructure Provisioning" section.
# =============================================================================

terraform {
  backend "s3" {
    bucket         = "auroraforge-tfstate-${var.aws_account_id}"
    key            = "aws/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    kms_key_id     = "alias/terraform-state-key"
    dynamodb_table = "auroraforge-tf-locks"
  }
}
