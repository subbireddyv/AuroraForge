# =============================================================================
# KMS – Customer Managed Keys (CMKs)
# One key per data classification domain: application data, database, S3, CloudWatch.
# Automatic rotation enabled on all keys.
# =============================================================================

data "aws_caller_identity" "current" {}

# ---- Application Data CMK (used by Spring Boot KMS adapter) -----------------
resource "aws_kms_key" "app_data" {
  description              = "AuroraForge – application data encryption (CMK)"
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  enable_key_rotation      = var.kms_key_rotation_enabled
  deletion_window_in_days  = var.kms_key_deletion_window_days
  multi_region             = true   # Enables multi-region key replication

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowRootAccountFullControl"
        Effect = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.aws_account_id}:root" }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AllowEKSServiceAccountUsage"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.app_kms_role.arn
        }
        Action   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = "*"
      },
      {
        Sid    = "AllowCloudTrailLogging"
        Effect = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action   = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource = "*"
      }
    ]
  })

  tags = { Name = "${var.project_name}-app-data-cmk", DataClassification = "confidential" }
}

resource "aws_kms_alias" "app_data" {
  name          = "alias/${var.project_name}-app-data"
  target_key_id = aws_kms_key.app_data.key_id
}

# ---- RDS CMK ----------------------------------------------------------------
resource "aws_kms_key" "rds" {
  description              = "AuroraForge – RDS encryption at rest (CMK)"
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  enable_key_rotation      = var.kms_key_rotation_enabled
  deletion_window_in_days  = var.kms_key_deletion_window_days

  tags = { Name = "${var.project_name}-rds-cmk", DataClassification = "restricted" }
}

resource "aws_kms_alias" "rds" {
  name          = "alias/${var.project_name}-rds"
  target_key_id = aws_kms_key.rds.key_id
}

# ---- S3 CMK -----------------------------------------------------------------
resource "aws_kms_key" "s3" {
  description              = "AuroraForge – S3 SSE-KMS encryption (CMK)"
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  enable_key_rotation      = var.kms_key_rotation_enabled
  deletion_window_in_days  = var.kms_key_deletion_window_days

  tags = { Name = "${var.project_name}-s3-cmk" }
}

resource "aws_kms_alias" "s3" {
  name          = "alias/${var.project_name}-s3"
  target_key_id = aws_kms_key.s3.key_id
}

# ---- CloudWatch CMK (for VPC Flow Logs + EKS control plane logs) -----------
resource "aws_kms_key" "cloudwatch" {
  description              = "AuroraForge – CloudWatch Logs encryption (CMK)"
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"
  enable_key_rotation      = var.kms_key_rotation_enabled
  deletion_window_in_days  = var.kms_key_deletion_window_days

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowRootAccountFullControl"
        Effect = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.aws_account_id}:root" }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AllowCloudWatchLogsUsage"
        Effect = "Allow"
        Principal = { Service = "logs.${var.aws_region}.amazonaws.com" }
        Action   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource = "*"
        Condition = {
          ArnLike = {
            "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:*"
          }
        }
      }
    ]
  })

  tags = { Name = "${var.project_name}-cloudwatch-cmk" }
}

resource "aws_kms_alias" "cloudwatch" {
  name          = "alias/${var.project_name}-cloudwatch"
  target_key_id = aws_kms_key.cloudwatch.key_id
}

# ---- IAM role used by application pods to call KMS --------------------------
resource "aws_iam_role" "app_kms_role" {
  name = "${var.project_name}-app-kms-role"

  # IRSA: trust the EKS OIDC provider so pods assume this role directly
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = "arn:aws:iam::${var.aws_account_id}:oidc-provider/${replace(module.eks.cluster_oidc_issuer_url, "https://", "")}"
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${replace(module.eks.cluster_oidc_issuer_url, "https://", "")}:sub" = "system:serviceaccount:auroraforge:auroraforge-app"
          "${replace(module.eks.cluster_oidc_issuer_url, "https://", "")}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "app_kms" {
  name = "app-kms-policy"
  role = aws_iam_role.app_kms_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [aws_kms_key.app_data.arn, aws_kms_key.s3.arn]
      },
      {
        # Secrets Manager read – so app can retrieve DB credentials
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.aws_account_id}:secret:${var.project_name}/*"
      }
    ]
  })
}
