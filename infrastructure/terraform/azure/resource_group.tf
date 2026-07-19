# =============================================================================
# Resource Group
# =============================================================================

locals {
  common_tags = merge(var.tags, { Environment = var.environment, Project = var.project_name })
}

resource "azurerm_resource_group" "main" {
  name     = var.resource_group_name
  location = var.location
  tags     = merge(var.tags, { Environment = var.environment })
}
