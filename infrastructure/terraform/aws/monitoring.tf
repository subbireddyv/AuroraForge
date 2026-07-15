##############################################################################
# AWS Monitoring – CloudWatch Log Groups, Alarms, Dashboard, SNS
#
# Design principles:
#  - All log groups encrypted with the cloudwatch CMK (enforces KMS policy).
#  - Retention is 90 days for app logs, 365 days for audit/security logs.
#  - Alarms use composite alarm pattern: anomaly detector threshold on metrics
#    rather than static thresholds where possible.
#  - SNS topic for alarm notifications — subscribers (PagerDuty, Slack) are
#    managed outside Terraform (ops team responsibility).
##############################################################################

# ── SNS Topic for alarm delivery ────────────────────────────────────────────
resource "aws_sns_topic" "alerts" {
  name              = "auroraforge-alerts"
  kms_master_key_id = aws_kms_key.cloudwatch.arn
  tags              = local.common_tags
}

resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "CloudWatchAlarmsPublish"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.alerts.arn
      }
    ]
  })
}

# ── CloudWatch Log Groups ────────────────────────────────────────────────────
locals {
  log_groups = {
    ingestion    = { retention = 90,  path = "/auroraforge/ingestion" }
    processing   = { retention = 90,  path = "/auroraforge/processing" }
    sync         = { retention = 90,  path = "/auroraforge/sync" }
    keymgmt      = { retention = 365, path = "/auroraforge/keymgmt" }
    audit        = { retention = 365, path = "/auroraforge/audit" }
    eks_control  = { retention = 90,  path = "/aws/eks/${var.cluster_name}/cluster" }
    rds          = { retention = 90,  path = "/aws/rds/instance/${var.cluster_name}-rds/postgresql" }
  }
}

resource "aws_cloudwatch_log_group" "services" {
  for_each          = local.log_groups
  name              = each.value.path
  retention_in_days = each.value.retention
  kms_key_id        = aws_kms_key.cloudwatch.arn
  tags              = merge(local.common_tags, { Service = each.key })
}

# ── RDS Alarms ──────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "auroraforge-rds-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU utilization > 80% for 15 minutes"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_freeable_memory" {
  alarm_name          = "auroraforge-rds-memory-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "FreeableMemory"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 536870912  # 512 MB
  alarm_description   = "RDS freeable memory < 512 MB"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_storage_space" {
  alarm_name          = "auroraforge-rds-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 3600
  statistic           = "Minimum"
  threshold           = 10737418240  # 10 GB
  alarm_description   = "RDS free storage < 10 GB"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.id
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_replication_lag" {
  alarm_name          = "auroraforge-rds-replication-lag"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ReplicaLag"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 30  # 30 seconds
  alarm_description   = "RDS read replica lag > 30 seconds — cross-region reads may be stale"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBInstanceIdentifier = aws_db_instance.replica.id
  }
}

# ── EKS / Application Alarms ────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "eks_node_cpu" {
  alarm_name          = "auroraforge-eks-node-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "node_cpu_usage_total"
  namespace           = "ContainerInsights"
  period              = 300
  statistic           = "Average"
  threshold           = 85
  alarm_description   = "EKS node CPU > 85% for 15 minutes — consider scaling up"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.cluster_name
  }
}

resource "aws_cloudwatch_metric_alarm" "ingestion_error_rate" {
  alarm_name          = "auroraforge-ingestion-errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "auroraforge.ingestion.events.rejected"
  namespace           = "AuroraForge/Application"
  period              = 300
  statistic           = "Sum"
  threshold           = 50
  alarm_description   = "More than 50 rejected events in 5 minutes"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "sync_errors" {
  alarm_name          = "auroraforge-sync-errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "auroraforge.sync.errors"
  namespace           = "AuroraForge/Application"
  period              = 300
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "More than 10 unrecoverable sync errors in 5 minutes"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

# ── KMS API Throttle Alarm ───────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "kms_throttling" {
  alarm_name          = "auroraforge-kms-throttling"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ThrottledRequests"
  namespace           = "AWS/KMS"
  period              = 60
  statistic           = "Sum"
  threshold           = 5
  alarm_description   = "KMS API throttling detected — check bucket key enablement on S3"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  treat_missing_data  = "notBreaching"
}

# ── CloudWatch Dashboard ─────────────────────────────────────────────────────
resource "aws_cloudwatch_dashboard" "auroraforge" {
  dashboard_name = "AuroraForge-Operations"

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        width  = 12
        height = 6
        properties = {
          title   = "Ingestion Throughput"
          metrics = [
            ["AuroraForge/Application", "auroraforge.ingestion.events.ingested", { stat = "Sum", period = 60 }],
            ["AuroraForge/Application", "auroraforge.ingestion.events.rejected",  { stat = "Sum", period = 60, color = "#ff0000" }]
          ]
          view   = "timeSeries"
          region = var.aws_region
          period = 300
        }
      },
      {
        type   = "metric"
        width  = 12
        height = 6
        properties = {
          title   = "Ingestion P99 Latency"
          metrics = [
            ["AuroraForge/Application", "auroraforge.ingestion.latency", { stat = "p99", period = 60, label = "p99" }],
            ["AuroraForge/Application", "auroraforge.ingestion.latency", { stat = "p95", period = 60, label = "p95" }],
            ["AuroraForge/Application", "auroraforge.ingestion.latency", { stat = "p50", period = 60, label = "p50" }]
          ]
          view   = "timeSeries"
          region = var.aws_region
        }
      },
      {
        type   = "metric"
        width  = 8
        height = 6
        properties = {
          title   = "RDS CPU & Connections"
          metrics = [
            ["AWS/RDS", "CPUUtilization",    { stat = "Average", period = 60 }, { dimensions = { DBInstanceIdentifier = aws_db_instance.main.id } }],
            ["AWS/RDS", "DatabaseConnections", { stat = "Average", period = 60 }, { dimensions = { DBInstanceIdentifier = aws_db_instance.main.id } }]
          ]
          view   = "timeSeries"
          region = var.aws_region
        }
      },
      {
        type   = "metric"
        width  = 8
        height = 6
        properties = {
          title   = "Cross-Cloud Sync"
          metrics = [
            ["AuroraForge/Application", "auroraforge.sync.success", "cloud", "aws",   { stat = "Sum", period = 60, label = "AWS success" }],
            ["AuroraForge/Application", "auroraforge.sync.success", "cloud", "azure", { stat = "Sum", period = 60, label = "Azure success" }],
            ["AuroraForge/Application", "auroraforge.sync.conflicts", { stat = "Sum", period = 60, label = "Conflicts", color = "#ff9900" }],
            ["AuroraForge/Application", "auroraforge.sync.errors",    { stat = "Sum", period = 60, label = "Errors",    color = "#ff0000" }]
          ]
          view   = "timeSeries"
          region = var.aws_region
        }
      },
      {
        type   = "metric"
        width  = 8
        height = 6
        properties = {
          title   = "KMS Operations"
          metrics = [
            ["AuroraForge/Application", "auroraforge.keymgmt.encrypt.latency", { stat = "p99", period = 60, label = "Encrypt p99" }],
            ["AuroraForge/Application", "auroraforge.keymgmt.errors",           { stat = "Sum", period = 60, label = "Errors", color = "#ff0000" }]
          ]
          view   = "timeSeries"
          region = var.aws_region
        }
      }
    ]
  })
}

# ── Outputs ──────────────────────────────────────────────────────────────────
output "alerts_sns_topic_arn" {
  description = "SNS topic ARN for alarm subscriptions (PagerDuty / Slack)"
  value       = aws_sns_topic.alerts.arn
}

output "cloudwatch_dashboard_url" {
  description = "CloudWatch dashboard URL"
  value       = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${aws_cloudwatch_dashboard.auroraforge.dashboard_name}"
}
