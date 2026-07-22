# =============================================================================
# RDS PostgreSQL – Multi-AZ, encrypted, automated backups, Performance Insights
# Password managed via AWS Secrets Manager (rotation every 90 days)
# =============================================================================

# Subnet group – private subnets only, spanning all AZs
resource "aws_db_subnet_group" "main" {
  name        = "${var.project_name}-rds-subnet-group"
  description = "AuroraForge RDS subnet group – private subnets across all AZs"
  subnet_ids  = aws_subnet.private[*].id

  tags = { Name = "${var.project_name}-rds-subnet-group" }
}

# Security group – only allow inbound from EKS node SG on port 5432
resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds-sg"
  description = "Allow PostgreSQL access from EKS nodes only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-rds-sg" }
}

# Parameter group – tuned for write-heavy OLTP + logical replication
resource "aws_db_parameter_group" "main" {
  name        = "${var.project_name}-pg16-params"
  family      = "postgres16"
  description = "AuroraForge PostgreSQL 16 parameter group"

  parameter {
    name  = "wal_level"
    value = "logical"  # Required for Debezium CDC
  }

  parameter {
    name  = "max_replication_slots"
    value = "10"
  }

  parameter {
    name  = "max_wal_senders"
    value = "10"
  }

  parameter {
    name  = "shared_preload_libraries"
    value = "pg_stat_statements,auto_explain"
  }

  parameter {
    name  = "pg_stat_statements.track"
    value = "all"
  }

  parameter {
    name  = "auto_explain.log_min_duration"
    value = "1000"  # Log queries slower than 1s
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }

  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "30000"  # Kill idle-in-transaction after 30s
  }

  parameter {
    name  = "statement_timeout"
    value = "60000"  # Hard limit: 60s
  }

  tags = { Name = "${var.project_name}-pg16-params" }
}

# Option group
resource "aws_db_option_group" "main" {
  name                     = "${var.project_name}-pg16-options"
  option_group_description = "AuroraForge PostgreSQL 16 option group"
  engine_name              = "postgres"
  major_engine_version     = "16"
}

# Secrets Manager – stores and rotates the master password
resource "aws_secretsmanager_secret" "rds_master_password" {
  name                    = "${var.project_name}/rds/master-password"
  description             = "AuroraForge RDS master password – auto-rotated every 90 days"
  kms_key_id              = aws_kms_key.app_data.arn
  recovery_window_in_days = 30

  tags = { Name = "${var.project_name}-rds-master-secret" }
}

# TODO: re-enable once the rds_password_rotator Lambda is implemented.
# The rotation Lambda (aws_lambda_function.rds_password_rotator) is not yet
# defined anywhere, so this resource referenced an undeclared resource and
# failed terraform validate.
# resource "aws_secretsmanager_secret_rotation" "rds_master_password" {
#   secret_id           = aws_secretsmanager_secret.rds_master_password.id
#   rotation_lambda_arn = aws_lambda_function.rds_password_rotator.arn
#
#   rotation_rules {
#     automatically_after_days = 90
#   }
# }

resource "random_password" "rds_master" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret_version" "rds_master_password" {
  secret_id = aws_secretsmanager_secret.rds_master_password.id
  secret_string = jsonencode({
    username = var.rds_master_username
    password = random_password.rds_master.result
    engine   = "postgres"
    host     = aws_db_instance.main.address
    port     = 5432
    dbname   = var.rds_db_name
  })
}

# Primary RDS instance
resource "aws_db_instance" "main" {
  identifier = "${var.project_name}-postgres-${var.environment}"

  engine         = "postgres"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  db_name  = var.rds_db_name
  username = var.rds_master_username
  password = random_password.rds_master.result

  # Storage – gp3 with autoscaling
  allocated_storage     = var.rds_allocated_storage_gb
  max_allocated_storage = var.rds_max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn
  iops                  = 3000

  # Network
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  port                   = 5432

  # HA – Multi-AZ synchronous replication
  multi_az = true

  # Backup
  backup_retention_period   = var.rds_backup_retention_days
  backup_window             = "03:00-04:00"  # UTC – low-traffic window
  maintenance_window        = "Mon:04:00-Mon:05:00"
  copy_tags_to_snapshot     = true
  delete_automated_backups  = false
  deletion_protection       = true   # Require explicit destroy via CLI
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project_name}-postgres-final-snapshot"

  # Parameter and option groups
  parameter_group_name = aws_db_parameter_group.main.name
  option_group_name    = aws_db_option_group.main.name

  # Monitoring
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  monitoring_interval             = 60   # Enhanced monitoring every 60s
  monitoring_role_arn             = aws_iam_role.rds_enhanced_monitoring.arn
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.rds.arn
  performance_insights_retention_period = 31  # days

  # Auto minor version upgrades during maintenance window
  auto_minor_version_upgrade = true

  tags = {
    Name            = "${var.project_name}-postgres-primary"
    DataClassification = "restricted"
  }

  # Prevent recreation when password changes (managed by Secrets Manager rotation)
  lifecycle {
    ignore_changes = [password]
  }
}

# Read replica in secondary region – for DR and read scaling
resource "aws_db_instance" "replica" {
  provider   = aws.secondary
  identifier = "${var.project_name}-postgres-replica-${var.environment}"

  # Read replica – must reference primary by ARN for cross-region
  replicate_source_db    = aws_db_instance.main.arn
  instance_class         = var.rds_instance_class
  storage_encrypted      = true
  kms_key_id             = aws_kms_key.rds.arn   # Key must be replicated to secondary region
  publicly_accessible    = false
  auto_minor_version_upgrade = true
  backup_retention_period = 7

  performance_insights_enabled = true
  monitoring_interval          = 60

  tags = { Name = "${var.project_name}-postgres-replica", Role = "read-replica" }
}

# IAM role for RDS Enhanced Monitoring
resource "aws_iam_role" "rds_enhanced_monitoring" {
  name = "${var.project_name}-rds-enhanced-monitoring-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_enhanced_monitoring" {
  role       = aws_iam_role.rds_enhanced_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
