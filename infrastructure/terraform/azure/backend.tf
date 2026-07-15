# =============================================================================
# Remote State – Azure Blob Storage
# Bootstrap: see README.md "Infrastructure Provisioning" section.
# =============================================================================

terraform {
  backend "azurerm" {
    resource_group_name  = "auroraforge-tfstate-rg"
    storage_account_name = "auroraforgetfstate"
    container_name       = "tfstate"
    key                  = "azure/terraform.tfstate"
    # ARM_ACCESS_KEY env var provides storage account access key
  }
}
