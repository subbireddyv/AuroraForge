# =============================================================================
# AWS Module – Outputs (consumed by Crossplane provider configs + CI/CD)
# =============================================================================

output "vpc_id" {
  description = "ID of the primary VPC."
  value       = aws_vpc.main.id
}

output "private_subnet_ids" {
  description = "List of private subnet IDs."
  value       = aws_subnet.private[*].id
}

output "eks_cluster_name" {
  description = "EKS cluster name."
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS API server endpoint (private)."
  value       = module.eks.cluster_endpoint
  sensitive   = true
}

output "eks_cluster_ca_data" {
  description = "EKS cluster CA certificate data (base64)."
  value       = module.eks.cluster_certificate_authority_data
  sensitive   = true
}

output "eks_oidc_provider_arn" {
  description = "OIDC provider ARN – used for IRSA role trust policies."
  value       = module.eks.oidc_provider_arn
}

output "rds_endpoint" {
  description = "RDS primary endpoint (write)."
  value       = aws_db_instance.main.endpoint
  sensitive   = true
}

output "rds_reader_endpoint" {
  description = "RDS read replica endpoint."
  value       = aws_db_instance.replica.endpoint
  sensitive   = true
}

output "rds_secret_arn" {
  description = "Secrets Manager ARN for the RDS master password."
  value       = aws_secretsmanager_secret.rds_master_password.arn
}

output "kms_app_data_key_arn" {
  description = "ARN of the application data CMK."
  value       = aws_kms_key.app_data.arn
}

output "kms_app_data_key_alias" {
  description = "Alias of the application data CMK."
  value       = aws_kms_alias.app_data.name
}

output "s3_raw_bucket_name" {
  description = "Name of the raw data S3 bucket."
  value       = aws_s3_bucket.raw.id
}

output "s3_processed_bucket_name" {
  description = "Name of the processed data S3 bucket."
  value       = aws_s3_bucket.processed.id
}

output "s3_spark_checkpoints_bucket_name" {
  description = "Name of the Spark checkpoints S3 bucket."
  value       = aws_s3_bucket.spark_checkpoints.id
}

output "app_kms_iam_role_arn" {
  description = "IAM role ARN for application pods to call KMS and Secrets Manager (via IRSA)."
  value       = aws_iam_role.app_kms_role.arn
}
