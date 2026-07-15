# =============================================================================
# S3 Buckets – versioned, encrypted, lifecycle-managed, cross-region replicated
# =============================================================================

locals {
  bucket_prefix = "${var.project_name}-${var.s3_bucket_suffix}"
}

# Replication IAM role
resource "aws_iam_role" "s3_replication" {
  name = "${var.project_name}-s3-replication-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "s3_replication" {
  name = "s3-replication-policy"
  role = aws_iam_role.s3_replication.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
        Resource = [aws_s3_bucket.raw.arn, aws_s3_bucket.processed.arn]
      },
      {
        Effect = "Allow"
        Action = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging"]
        Resource = ["${aws_s3_bucket.raw.arn}/*", "${aws_s3_bucket.processed.arn}/*"]
      },
      {
        Effect = "Allow"
        Action = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
        Resource = ["${aws_s3_bucket.raw_replica.arn}/*", "${aws_s3_bucket.processed_replica.arn}/*"]
      },
      {
        Effect = "Allow"
        Action = ["kms:Decrypt"]
        Resource = [aws_kms_key.s3.arn]
        Condition = { StringLike = { "kms:ViaService" = "s3.${var.aws_region}.amazonaws.com" } }
      }
    ]
  })
}

# ---- Raw Data Bucket (primary) ----------------------------------------------
resource "aws_s3_bucket" "raw" {
  bucket = "${local.bucket_prefix}-raw"
  tags   = { Name = "${local.bucket_prefix}-raw", DataTier = "raw" }
}

resource "aws_s3_bucket_versioning" "raw" {
  bucket = aws_s3_bucket.raw.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.s3.arn
    }
    bucket_key_enabled = true  # Reduces KMS API calls by ~99%
  }
}

resource "aws_s3_bucket_public_access_block" "raw" {
  bucket                  = aws_s3_bucket.raw.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id

  rule {
    id     = "transition-to-ia"
    status = "Enabled"
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
    transition {
      days          = 90
      storage_class = "GLACIER_IR"
    }
    expiration {
      days = 365
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

resource "aws_s3_bucket_replication_configuration" "raw" {
  bucket = aws_s3_bucket.raw.id
  role   = aws_iam_role.s3_replication.arn

  rule {
    id     = "replicate-to-secondary"
    status = "Enabled"

    destination {
      bucket        = aws_s3_bucket.raw_replica.arn
      storage_class = "STANDARD_IA"

      encryption_configuration {
        replica_kms_key_id = aws_kms_key.s3.arn
      }
    }

    source_selection_criteria {
      sse_kms_encrypted_objects { status = "Enabled" }
    }
  }

  depends_on = [aws_s3_bucket_versioning.raw]
}

# ---- Raw Data Bucket (replica in secondary region) -------------------------
resource "aws_s3_bucket" "raw_replica" {
  provider = aws.secondary
  bucket   = "${local.bucket_prefix}-raw-replica"
  tags     = { Name = "${local.bucket_prefix}-raw-replica", DataTier = "raw", Role = "replica" }
}

resource "aws_s3_bucket_versioning" "raw_replica" {
  provider = aws.secondary
  bucket   = aws_s3_bucket.raw_replica.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_public_access_block" "raw_replica" {
  provider                = aws.secondary
  bucket                  = aws_s3_bucket.raw_replica.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---- Processed Data Bucket --------------------------------------------------
resource "aws_s3_bucket" "processed" {
  bucket = "${local.bucket_prefix}-processed"
  tags   = { Name = "${local.bucket_prefix}-processed", DataTier = "processed" }
}

resource "aws_s3_bucket_versioning" "processed" {
  bucket = aws_s3_bucket.processed.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "processed" {
  bucket = aws_s3_bucket.processed.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.s3.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "processed" {
  bucket                  = aws_s3_bucket.processed.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Replica for processed bucket
resource "aws_s3_bucket" "processed_replica" {
  provider = aws.secondary
  bucket   = "${local.bucket_prefix}-processed-replica"
  tags     = { Name = "${local.bucket_prefix}-processed-replica", DataTier = "processed", Role = "replica" }
}

resource "aws_s3_bucket_versioning" "processed_replica" {
  provider = aws.secondary
  bucket   = aws_s3_bucket.processed_replica.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_public_access_block" "processed_replica" {
  provider                = aws.secondary
  bucket                  = aws_s3_bucket.processed_replica.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---- Spark Checkpoints Bucket -----------------------------------------------
resource "aws_s3_bucket" "spark_checkpoints" {
  bucket = "${local.bucket_prefix}-spark-checkpoints"
  tags   = { Name = "${local.bucket_prefix}-spark-checkpoints" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "spark_checkpoints" {
  bucket = aws_s3_bucket.spark_checkpoints.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.s3.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "spark_checkpoints" {
  bucket                  = aws_s3_bucket.spark_checkpoints.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "spark_checkpoints" {
  bucket = aws_s3_bucket.spark_checkpoints.id
  rule {
    id     = "expire-old-checkpoints"
    status = "Enabled"
    expiration { days = 7 }
  }
}
