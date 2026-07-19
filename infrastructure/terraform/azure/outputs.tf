# =============================================================================
# Azure Module – Outputs
# =============================================================================

output "resource_group_name" {
  value = azurerm_resource_group.main.name
}

output "aks_cluster_name" {
  value = azurerm_kubernetes_cluster.main.name
}

output "aks_oidc_issuer_url" {
  description = "AKS OIDC issuer URL – used for Workload Identity federation."
  value       = azurerm_kubernetes_cluster.main.oidc_issuer_url
}

output "cosmos_db_endpoint" {
  description = "Cosmos DB document endpoint."
  value       = azurerm_cosmosdb_account.main.endpoint
  sensitive   = true
}

output "cosmos_db_primary_key" {
  description = "Cosmos DB primary master key. Prefer Managed Identity in production."
  value       = azurerm_cosmosdb_account.main.primary_key
  sensitive   = true
}

output "key_vault_uri" {
  description = "Key Vault URI used by the AzureKeyVaultAdapter."
  value       = azurerm_key_vault.main.vault_uri
}

output "app_data_key_id" {
  description = "Versionless ID of the application data HSM key."
  value       = azurerm_key_vault_key.app_data.versionless_id
}

output "app_workload_identity_client_id" {
  description = "Client ID of the app workload managed identity (used in K8s ServiceAccount annotation)."
  value       = azurerm_user_assigned_identity.app_workload.client_id
}

