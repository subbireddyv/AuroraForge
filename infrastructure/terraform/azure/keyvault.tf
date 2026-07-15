# =============================================================================
# Azure Key Vault – Premium SKU (HSM-backed keys), RBAC authorization
# Soft delete + purge protection enforced.
# Keys for: application data, Cosmos DB CMK, storage CMK.
# Automatic rotation via Key Vault rotation policy.
# =============================================================================

resource "azurerm_key_vault" "main" {
  name                       = "${var.project_name}-kv-${var.environment}"
  location                   = azurerm_resource_group.main.location
  resource_group_name        = azurerm_resource_group.main.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = var.key_vault_sku  # premium = HSM-backed

  # Use RBAC instead of legacy access policies – simpler and auditable
  enable_rbac_authorization       = true
  enabled_for_disk_encryption     = false
  enabled_for_deployment          = false
  enabled_for_template_deployment = false

  # Protect against accidental data loss
  soft_delete_retention_days = var.key_vault_soft_delete_retention_days
  purge_protection_enabled   = true

  # Network: only private endpoint + AKS subnet
  public_network_access_enabled = false

  network_acls {
    default_action             = "Deny"
    bypass                     = "AzureServices"
    virtual_network_subnet_ids = [azurerm_subnet.aks.id, azurerm_subnet.services.id]
  }

  tags = var.tags
}

# Private endpoint for Key Vault
resource "azurerm_private_endpoint" "key_vault" {
  name                = "${var.project_name}-kv-pe"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.services.id

  private_service_connection {
    name                           = "keyvault-psc"
    private_connection_resource_id = azurerm_key_vault.main.id
    subresource_names              = ["vault"]
    is_manual_connection           = false
  }

  tags = var.tags
}

# ---- Application Data Key ---------------------------------------------------
resource "azurerm_key_vault_key" "app_data" {
  name         = "${var.project_name}-app-data-key"
  key_vault_id = azurerm_key_vault.main.id
  key_type     = "RSA-HSM"   # HSM-protected (premium SKU required)
  key_size     = 4096
  key_opts     = ["decrypt", "encrypt", "sign", "verify", "wrapKey", "unwrapKey"]

  # Automatic key rotation every 90 days
  rotation_policy {
    automatic {
      time_before_expiry = "P30D"  # Rotate 30 days before expiry
    }
    expire_after         = "P180D" # Key expires 180 days after creation
    notify_before_expiry = "P14D"  # Notify 14 days before expiry
  }

  tags = var.tags

  depends_on = [
    azurerm_role_assignment.terraform_kv_officer,
    azurerm_private_endpoint.key_vault
  ]
}

# ---- Cosmos DB CMK Key ------------------------------------------------------
resource "azurerm_key_vault_key" "cosmos" {
  name         = "${var.project_name}-cosmos-cmk"
  key_vault_id = azurerm_key_vault.main.id
  key_type     = "RSA-HSM"
  key_size     = 4096
  key_opts     = ["decrypt", "encrypt", "wrapKey", "unwrapKey"]

  rotation_policy {
    automatic {
      time_before_expiry = "P30D"
    }
    expire_after         = "P365D"
    notify_before_expiry = "P14D"
  }

  tags = var.tags

  depends_on = [
    azurerm_role_assignment.terraform_kv_officer,
    azurerm_private_endpoint.key_vault
  ]
}

# ---- Storage CMK Key --------------------------------------------------------
resource "azurerm_key_vault_key" "storage" {
  name         = "${var.project_name}-storage-cmk"
  key_vault_id = azurerm_key_vault.main.id
  key_type     = "RSA-HSM"
  key_size     = 4096
  key_opts     = ["decrypt", "encrypt", "wrapKey", "unwrapKey"]

  rotation_policy {
    automatic {
      time_before_expiry = "P30D"
    }
    expire_after         = "P365D"
    notify_before_expiry = "P14D"
  }

  tags = var.tags

  depends_on = [
    azurerm_role_assignment.terraform_kv_officer,
    azurerm_private_endpoint.key_vault
  ]
}

# ---- RBAC Assignments -------------------------------------------------------

# Terraform SP needs Key Vault Crypto Officer to create/manage keys
resource "azurerm_role_assignment" "terraform_kv_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# App Workload Identity: can only use (encrypt/decrypt/sign) – not manage
resource "azurerm_role_assignment" "app_kv_user" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Crypto User"
  principal_id         = azurerm_user_assigned_identity.app_workload.principal_id
}

# Key Vault Secrets User for reading application secrets
resource "azurerm_role_assignment" "app_kv_secrets_user" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.app_workload.principal_id
}

# AKS Key Vault Secrets Provider needs reader access
resource "azurerm_role_assignment" "aks_kv_secrets_user" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_kubernetes_cluster.main.key_vault_secrets_provider[0].secret_identity[0].object_id
}

# Cosmos DB CMK: Cosmos DB service principal needs Crypto Service Encryption User
resource "azurerm_role_assignment" "cosmos_kv_crypto" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Crypto Service Encryption User"
  principal_id         = azurerm_cosmosdb_account.main.identity[0].principal_id
}
