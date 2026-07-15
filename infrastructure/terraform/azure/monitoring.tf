##############################################################################
# Azure Monitoring – Log Analytics, Monitor Alerts, Action Groups, Dashboards
#
# Architecture:
#  - Log Analytics Workspace aggregates AKS container logs, Cosmos DB telemetry,
#    and application-emitted custom metrics via the OTel Collector.
#  - Azure Monitor Alerts use Kusto (KQL) query-based alerts for complex
#    multi-dimensional conditions (e.g., p99 latency computed from log data).
#  - Action Group routes to email + webhook (configure PagerDuty/Slack
#    integration URL via var.alerting_webhook_url).
#  - Application Insights provides distributed tracing correlation when the
#    OTel Collector forwards to Azure Monitor OTLP endpoint.
##############################################################################

# ── Log Analytics Workspace ──────────────────────────────────────────────────
resource "azurerm_log_analytics_workspace" "main" {
  name                = "auroraforge-logs"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  sku                 = "PerGB2018"
  retention_in_days   = 90

  # CMK encryption for log data
  cmk_for_query_forced = true

  tags = local.common_tags
}

resource "azurerm_log_analytics_workspace_table" "audit" {
  name         = "AuroraForgeAudit_CL"
  workspace_id = azurerm_log_analytics_workspace.main.id
  retention_in_days = 365
  total_retention_in_days = 730
}

# ── Application Insights ─────────────────────────────────────────────────────
resource "azurerm_application_insights" "main" {
  name                = "auroraforge-appinsights"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  workspace_id        = azurerm_log_analytics_workspace.main.id
  application_type    = "web"
  retention_in_days   = 90
  tags                = local.common_tags
}

# ── Action Group (alert routing) ─────────────────────────────────────────────
resource "azurerm_monitor_action_group" "critical" {
  name                = "auroraforge-critical-alerts"
  resource_group_name = azurerm_resource_group.main.name
  short_name          = "af-critical"

  email_receiver {
    name                    = "ops-team"
    email_address           = var.ops_email
    use_common_alert_schema = true
  }

  webhook_receiver {
    name                    = "pagerduty"
    service_uri             = var.alerting_webhook_url
    use_common_alert_schema = true
  }

  tags = local.common_tags
}

resource "azurerm_monitor_action_group" "warning" {
  name                = "auroraforge-warning-alerts"
  resource_group_name = azurerm_resource_group.main.name
  short_name          = "af-warning"

  email_receiver {
    name                    = "ops-team"
    email_address           = var.ops_email
    use_common_alert_schema = true
  }

  tags = local.common_tags
}

# ── AKS Diagnostic Settings ──────────────────────────────────────────────────
resource "azurerm_monitor_diagnostic_setting" "aks" {
  name                       = "auroraforge-aks-diagnostics"
  target_resource_id         = azurerm_kubernetes_cluster.main.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category = "kube-apiserver"
  }
  enabled_log {
    category = "kube-controller-manager"
  }
  enabled_log {
    category = "kube-scheduler"
  }
  enabled_log {
    category = "kube-audit"
  }
  enabled_log {
    category = "cluster-autoscaler"
  }

  metric {
    category = "AllMetrics"
    enabled  = true
  }
}

# ── Cosmos DB Diagnostic Settings ───────────────────────────────────────────
resource "azurerm_monitor_diagnostic_setting" "cosmos" {
  name                       = "auroraforge-cosmos-diagnostics"
  target_resource_id         = azurerm_cosmosdb_account.main.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category = "DataPlaneRequests"
  }
  enabled_log {
    category = "QueryRuntimeStatistics"
  }
  enabled_log {
    category = "PartitionKeyStatistics"
  }
  enabled_log {
    category = "PartitionKeyRUConsumption"
  }
  enabled_log {
    category = "ControlPlaneRequests"
  }

  metric {
    category = "Requests"
    enabled  = true
  }
}

# ── Cosmos DB Alerts ─────────────────────────────────────────────────────────
resource "azurerm_monitor_metric_alert" "cosmos_ru_consumed" {
  name                = "auroraforge-cosmos-ru-high"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_cosmosdb_account.main.id]
  description         = "Cosmos DB RU/s consumption > 80% of provisioned"
  severity            = 2
  window_size         = "PT5M"
  frequency           = "PT1M"

  criteria {
    metric_namespace = "Microsoft.DocumentDB/databaseAccounts"
    metric_name      = "NormalizedRUConsumption"
    aggregation      = "Maximum"
    operator         = "GreaterThan"
    threshold        = 80
  }

  action {
    action_group_id = azurerm_monitor_action_group.warning.id
  }

  tags = local.common_tags
}

resource "azurerm_monitor_metric_alert" "cosmos_replication_lag" {
  name                = "auroraforge-cosmos-replication-lag"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_cosmosdb_account.main.id]
  description         = "Cosmos DB replication latency > 5 seconds"
  severity            = 1
  window_size         = "PT5M"
  frequency           = "PT1M"

  criteria {
    metric_namespace = "Microsoft.DocumentDB/databaseAccounts"
    metric_name      = "ReplicationLatency"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 5000  # milliseconds
  }

  action {
    action_group_id = azurerm_monitor_action_group.critical.id
  }
}

resource "azurerm_monitor_metric_alert" "cosmos_availability" {
  name                = "auroraforge-cosmos-availability-low"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_cosmosdb_account.main.id]
  description         = "Cosmos DB service availability < 99.9%"
  severity            = 0  # Critical
  window_size         = "PT5M"
  frequency           = "PT1M"

  criteria {
    metric_namespace = "Microsoft.DocumentDB/databaseAccounts"
    metric_name      = "ServiceAvailability"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = 99.9
  }

  action {
    action_group_id = azurerm_monitor_action_group.critical.id
  }
}

# ── AKS Alerts ───────────────────────────────────────────────────────────────
resource "azurerm_monitor_metric_alert" "aks_node_cpu" {
  name                = "auroraforge-aks-node-cpu-high"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_kubernetes_cluster.main.id]
  description         = "AKS node CPU utilization > 85%"
  severity            = 2
  window_size         = "PT15M"
  frequency           = "PT5M"

  criteria {
    metric_namespace = "Microsoft.ContainerService/managedClusters"
    metric_name      = "node_cpu_usage_percentage"
    aggregation      = "Average"
    operator         = "GreaterThan"
    threshold        = 85
  }

  action {
    action_group_id = azurerm_monitor_action_group.warning.id
  }
}

resource "azurerm_monitor_metric_alert" "aks_pod_failed" {
  name                = "auroraforge-aks-pod-failures"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_kubernetes_cluster.main.id]
  description         = "AKS pods in Failed phase detected"
  severity            = 1
  window_size         = "PT5M"
  frequency           = "PT1M"

  criteria {
    metric_namespace = "Microsoft.ContainerService/managedClusters"
    metric_name      = "kube_pod_status_phase"
    aggregation      = "Total"
    operator         = "GreaterThan"
    threshold        = 0

    dimension {
      name     = "phase"
      operator = "Include"
      values   = ["Failed"]
    }
  }

  action {
    action_group_id = azurerm_monitor_action_group.critical.id
  }
}

# ── Key Vault Alerts ─────────────────────────────────────────────────────────
resource "azurerm_monitor_metric_alert" "keyvault_availability" {
  name                = "auroraforge-keyvault-availability"
  resource_group_name = azurerm_resource_group.main.name
  scopes              = [azurerm_key_vault.main.id]
  description         = "Key Vault availability < 99.9%"
  severity            = 0
  window_size         = "PT5M"
  frequency           = "PT1M"

  criteria {
    metric_namespace = "Microsoft.KeyVault/vaults"
    metric_name      = "Availability"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = 99.9
  }

  action {
    action_group_id = azurerm_monitor_action_group.critical.id
  }
}

# ── Outputs ──────────────────────────────────────────────────────────────────
output "log_analytics_workspace_id" {
  description = "Log Analytics Workspace resource ID"
  value       = azurerm_log_analytics_workspace.main.id
}

output "application_insights_connection_string" {
  description = "Application Insights connection string for OTel Collector"
  value       = azurerm_application_insights.main.connection_string
  sensitive   = true
}

output "application_insights_instrumentation_key" {
  description = "Application Insights instrumentation key"
  value       = azurerm_application_insights.main.instrumentation_key
  sensitive   = true
}
