##############################################################################
# AWS IAM – Service Account Roles (IRSA) and Lambda Rotation Role
#
# IRSA Pattern: Each Kubernetes service account gets its own IAM role with
# a trust policy scoped to that specific service account via OIDC sub claim.
# This ensures zero cross-service credential sharing and precise least-privilege.
#
# Least-privilege design per service:
#  - ingestion-sa: KMS encrypt/decrypt (app-data key), S3 put/get (raw bucket)
#  - processing-sa: S3 get (raw), S3 put (processed + checkpoints), Spark IAM passrole
#  - sync-sa: S3 get/put (all buckets), KMS encrypt/decrypt, Secrets read
#  - keymgmt-sa: KMS all operations, Secrets Manager rotate
#  - observability-sa: CloudWatch logs/metrics put
##############################################################################

data "aws_iam_openid_connect_provider" "eks" {
  url = module.eks.cluster_oidc_issuer_url
}

locals {
  oidc_provider_arn = data.aws_iam_openid_connect_provider.eks.arn
  oidc_provider_url = replace(module.eks.cluster_oidc_issuer_url, "https://", "")
  namespace         = "auroraforge"
}

# ── Trust policy factory (shared by all IRSA roles) ─────────────────────────
data "aws_iam_policy_document" "irsa_trust" {
  for_each = toset(["ingestion", "processing", "sync", "keymgmt", "observability"])

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${local.namespace}:auroraforge-${each.key}-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

# ── Ingestion service role ───────────────────────────────────────────────────
resource "aws_iam_role" "ingestion" {
  name               = "auroraforge-ingestion-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust["ingestion"].json
  tags               = local.common_tags
}

resource "aws_iam_policy" "ingestion" {
  name = "auroraforge-ingestion-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "KmsEncryptDecrypt"
        Effect = "Allow"
        Action = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [aws_kms_key.app_data.arn]
        Condition = {
          StringEquals = {
            "kms:ViaService"       = "s3.${var.aws_region}.amazonaws.com"
            "kms:CallerAccount"    = data.aws_caller_identity.current.account_id
          }
        }
      },
      {
        Sid    = "S3RawBucketWrite"
        Effect = "Allow"
        Action = ["s3:PutObject", "s3:GetObject", "s3:HeadObject", "s3:DeleteObject",
                  "s3:GetObjectAttributes"]
        Resource = ["${aws_s3_bucket.raw.arn}/tenants/*"]
      },
      {
        Sid    = "S3RawBucketList"
        Effect = "Allow"
        Action = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.raw.arn]
        Condition = { StringLike = { "s3:prefix" = ["tenants/*"] } }
      },
      {
        Sid    = "SecretsRead"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = [aws_secretsmanager_secret.rds_master_password.arn]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ingestion" {
  role       = aws_iam_role.ingestion.name
  policy_arn = aws_iam_policy.ingestion.arn
}

# ── Processing (Spark) service role ─────────────────────────────────────────
resource "aws_iam_role" "processing" {
  name               = "auroraforge-processing-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust["processing"].json
  tags               = local.common_tags
}

resource "aws_iam_policy" "processing" {
  name = "auroraforge-processing-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3ReadRaw"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:HeadObject", "s3:ListBucket"]
        Resource = [aws_s3_bucket.raw.arn, "${aws_s3_bucket.raw.arn}/*"]
      },
      {
        Sid    = "S3WriteProcessed"
        Effect = "Allow"
        Action = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject", "s3:ListBucket"]
        Resource = [aws_s3_bucket.processed.arn, "${aws_s3_bucket.processed.arn}/*",
                    aws_s3_bucket.spark_checkpoints.arn, "${aws_s3_bucket.spark_checkpoints.arn}/*"]
      },
      {
        Sid    = "KmsDecrypt"
        Effect = "Allow"
        Action = ["kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [aws_kms_key.app_data.arn, aws_kms_key.s3.arn]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "processing" {
  role       = aws_iam_role.processing.name
  policy_arn = aws_iam_policy.processing.arn
}

# ── Sync service role ────────────────────────────────────────────────────────
resource "aws_iam_role" "sync" {
  name               = "auroraforge-sync-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust["sync"].json
  tags               = local.common_tags
}

resource "aws_iam_policy" "sync" {
  name = "auroraforge-sync-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "S3FullAccess"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:HeadObject",
                  "s3:ListBucket", "s3:GetObjectTagging", "s3:PutObjectTagging"]
        Resource = [
          "${aws_s3_bucket.raw.arn}/*", "${aws_s3_bucket.processed.arn}/*",
          aws_s3_bucket.raw.arn, aws_s3_bucket.processed.arn
        ]
      },
      {
        Sid    = "KmsEncryptDecrypt"
        Effect = "Allow"
        Action = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = [aws_kms_key.app_data.arn]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "sync" {
  role       = aws_iam_role.sync.name
  policy_arn = aws_iam_policy.sync.arn
}

# ── Key management service role ──────────────────────────────────────────────
resource "aws_iam_role" "keymgmt" {
  name               = "auroraforge-keymgmt-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust["keymgmt"].json
  tags               = local.common_tags
}

resource "aws_iam_policy" "keymgmt" {
  name = "auroraforge-keymgmt-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "KmsAllOperations"
        Effect = "Allow"
        Action = [
          "kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey",
          "kms:GenerateDataKeyWithoutPlaintext", "kms:DescribeKey",
          "kms:ListKeys", "kms:ListAliases", "kms:GetKeyPolicy",
          "kms:GetKeyRotationStatus", "kms:EnableKeyRotation"
        ]
        Resource = [
          aws_kms_key.app_data.arn, aws_kms_key.rds.arn,
          aws_kms_key.s3.arn, aws_kms_key.cloudwatch.arn
        ]
      },
      {
        Sid    = "SecretsRotate"
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret",
          "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecretVersionStage",
          "secretsmanager:RotateSecret"
        ]
        Resource = ["arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:auroraforge/*"]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "keymgmt" {
  role       = aws_iam_role.keymgmt.name
  policy_arn = aws_iam_policy.keymgmt.arn
}

# ── Observability service role ───────────────────────────────────────────────
resource "aws_iam_role" "observability" {
  name               = "auroraforge-observability-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust["observability"].json
  tags               = local.common_tags
}

resource "aws_iam_policy" "observability" {
  name = "auroraforge-observability-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "CloudWatchWrite"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents",
          "logs:DescribeLogGroups", "logs:DescribeLogStreams",
          "cloudwatch:PutMetricData", "cloudwatch:GetMetricData",
          "cloudwatch:DescribeAlarms", "xray:PutTraceSegments",
          "xray:PutTelemetryRecords", "xray:GetSamplingRules",
          "xray:GetSamplingTargets"
        ]
        Resource = ["*"]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "observability" {
  role       = aws_iam_role.observability.name
  policy_arn = aws_iam_policy.observability.arn
}

# ── Lambda execution role for Secrets Manager rotation ──────────────────────
resource "aws_iam_role" "secrets_rotation_lambda" {
  name = "auroraforge-secrets-rotation-lambda"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "rotation_lambda_basic" {
  role       = aws_iam_role.secrets_rotation_lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_policy" "rotation_lambda" {
  name = "auroraforge-secrets-rotation-lambda-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SecretsManagerRotation"
        Effect = "Allow"
        Action = [
          "secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue",
          "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecretVersionStage"
        ]
        Resource = [aws_secretsmanager_secret.rds_master_password.arn]
      },
      {
        Sid    = "RdsSetPassword"
        Effect = "Allow"
        Action = ["rds:ModifyDBInstance"]
        Resource = [aws_db_instance.main.arn]
      },
      {
        Sid    = "VpcNetworkAccess"
        Effect = "Allow"
        Action = [
          "ec2:CreateNetworkInterface", "ec2:DeleteNetworkInterface",
          "ec2:DescribeNetworkInterfaces", "ec2:DescribeVpcs",
          "ec2:DescribeSubnets", "ec2:DescribeSecurityGroups"
        ]
        Resource = ["*"]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "rotation_lambda" {
  role       = aws_iam_role.secrets_rotation_lambda.name
  policy_arn = aws_iam_policy.rotation_lambda.arn
}
