# =============================================================================
# Azure Module – Input Variables
# =============================================================================

variable "resource_group_name" {
  description = "Name of the primary Azure resource group."
  type        = string
  default     = "auroraforge-rg"
}

variable "location" {
  description = "Primary Azure region."
  type        = string
  default     = "eastus"
}

variable "secondary_location" {
  description = "Secondary Azure region for Cosmos DB failover and geo-replication."
  type        = string
  default     = "westus2"
}

variable "environment" {
  description = "Deployment environment (dev | staging | prod)."
  type        = string
  default     = "prod"
}

variable "project_name" {
  description = "Project identifier used for resource naming."
  type        = string
  default     = "auroraforge"
}

variable "tags" {
  description = "Common tags applied to all resources."
  type        = map(string)
  default = {
    Project   = "AuroraForge"
    ManagedBy = "Terraform"
    Owner     = "platform-team"
  }
}

# --- Virtual Network ---
variable "vnet_address_space" {
  description = "Address space for the primary VNet."
  type        = list(string)
  default     = ["10.1.0.0/16"]
}

variable "aks_subnet_cidr" {
  description = "CIDR for the AKS node subnet."
  type        = string
  default     = "10.1.1.0/24"
}

variable "services_subnet_cidr" {
  description = "CIDR for internal services (databases, cache)."
  type        = string
  default     = "10.1.2.0/24"
}

# --- AKS ---
variable "aks_kubernetes_version" {
  description = "Kubernetes version for the AKS cluster."
  type        = string
  default     = "1.29"
}

variable "aks_system_node_vm_size" {
  description = "VM size for AKS system node pool."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "aks_app_node_vm_size" {
  description = "VM size for AKS application node pool."
  type        = string
  default     = "Standard_D8s_v5"
}

variable "aks_app_node_min_count" {
  description = "Minimum node count for AKS application pool."
  type        = number
  default     = 2
}

variable "aks_app_node_max_count" {
  description = "Maximum node count for AKS application pool."
  type        = number
  default     = 10
}

# --- Cosmos DB ---
variable "cosmos_db_throughput" {
  description = "Default throughput (RU/s) for Cosmos DB containers."
  type        = number
  default     = 1000
}

variable "cosmos_db_secondary_region" {
  description = "Secondary region for Cosmos DB active-active replication."
  type        = string
  default     = "westus2"
}

# --- Key Vault ---
variable "key_vault_sku" {
  description = "Key Vault SKU: standard or premium (premium supports HSM-backed keys)."
  type        = string
  default     = "premium"
}

variable "key_vault_soft_delete_retention_days" {
  description = "Soft-delete retention period in days (7–90)."
  type        = number
  default     = 90
}
