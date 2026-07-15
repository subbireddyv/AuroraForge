# =============================================================================
# AWS Module – Input Variables
# =============================================================================

variable "aws_region" {
  description = "Primary AWS region for all resources."
  type        = string
  default     = "us-east-1"
}

variable "aws_secondary_region" {
  description = "Secondary AWS region used for S3 replication and RDS read replicas."
  type        = string
  default     = "us-west-2"
}

variable "aws_account_id" {
  description = "AWS account ID. Used for ARN construction and remote state bucket naming."
  type        = string
}

variable "environment" {
  description = "Deployment environment (dev | staging | prod)."
  type        = string
  default     = "prod"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}

variable "project_name" {
  description = "Project identifier used for resource naming."
  type        = string
  default     = "auroraforge"
}

# --- VPC ---
variable "vpc_cidr" {
  description = "CIDR block for the primary VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of AZs to deploy into. Minimum 3 required for HA."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets (one per AZ)."
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (one per AZ) – NAT gateways and load balancers only."
  type        = list(string)
  default     = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]
}

# --- EKS ---
variable "eks_cluster_version" {
  description = "Kubernetes version for the EKS cluster."
  type        = string
  default     = "1.29"
}

variable "eks_node_instance_types" {
  description = "EC2 instance types for the EKS managed node group."
  type        = list(string)
  default     = ["m6i.xlarge", "m6a.xlarge"]
}

variable "eks_node_group_min_size" {
  description = "Minimum node count per node group."
  type        = number
  default     = 2
}

variable "eks_node_group_max_size" {
  description = "Maximum node count per node group (for cluster autoscaler)."
  type        = number
  default     = 10
}

variable "eks_node_group_desired_size" {
  description = "Initial node count per node group."
  type        = number
  default     = 3
}

# --- RDS ---
variable "rds_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.r6g.xlarge"
}

variable "rds_engine_version" {
  description = "PostgreSQL engine version."
  type        = string
  default     = "16.1"
}

variable "rds_allocated_storage_gb" {
  description = "Initial storage in GB. Autoscaling enabled up to rds_max_allocated_storage_gb."
  type        = number
  default     = 100
}

variable "rds_max_allocated_storage_gb" {
  description = "Maximum autoscaled storage in GB."
  type        = number
  default     = 1000
}

variable "rds_db_name" {
  description = "Initial database name."
  type        = string
  default     = "auroraforge"
}

variable "rds_master_username" {
  description = "Master username. Password managed by AWS Secrets Manager."
  type        = string
  default     = "auroraforge_admin"
}

variable "rds_backup_retention_days" {
  description = "Number of days to retain automated backups."
  type        = number
  default     = 30
}

# --- S3 ---
variable "s3_bucket_suffix" {
  description = "Unique suffix appended to all S3 bucket names to avoid global naming conflicts."
  type        = string
}

# --- KMS ---
variable "kms_key_deletion_window_days" {
  description = "Waiting period before KMS key deletion (7–30 days)."
  type        = number
  default     = 30
}

variable "kms_key_rotation_enabled" {
  description = "Enable automatic annual KMS key rotation."
  type        = bool
  default     = true
}
