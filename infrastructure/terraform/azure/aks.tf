# =============================================================================
# AKS Cluster – Production-grade configuration
# - System + Application + Spark node pools
# - Workload Identity (federated credentials) instead of legacy pod identity
# - OIDC issuer for Workload Identity
# - Azure CNI with network policies
# - Azure AD RBAC integration
# - Defender for Containers enabled
# =============================================================================

resource "azurerm_user_assigned_identity" "aks_control_plane" {
  name                = "${var.project_name}-aks-identity"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags
}

# Grant the AKS identity access to create load balancers in the subnet
resource "azurerm_role_assignment" "aks_subnet_contributor" {
  scope                = azurerm_subnet.aks.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks_control_plane.principal_id
}

resource "azurerm_log_analytics_workspace" "aks" {
  name                = "${var.project_name}-aks-logs-${var.environment}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = 90
  tags                = var.tags
}

resource "azurerm_kubernetes_cluster" "main" {
  name                      = "${var.project_name}-aks-${var.environment}"
  location                  = azurerm_resource_group.main.location
  resource_group_name       = azurerm_resource_group.main.name
  dns_prefix                = "${var.project_name}-aks"
  kubernetes_version        = var.aks_kubernetes_version
  automatic_channel_upgrade = "patch"  # Auto-patch minor/patch versions

  # Private cluster – API server not accessible from internet
  private_cluster_enabled             = true
  private_cluster_public_fqdn_enabled = false

  # OIDC + Workload Identity (replacement for AAD Pod Identity)
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks_control_plane.id]
  }

  # System node pool – reserved for kube-system, monitoring
  default_node_pool {
    name                 = "system"
    vm_size              = var.aks_system_node_vm_size
    node_count           = 2
    vnet_subnet_id       = azurerm_subnet.aks.id
    os_disk_size_gb      = 128
    os_disk_type         = "Ephemeral"
    enable_auto_scaling  = true
    min_count            = 2
    max_count            = 4
    max_pods             = 110
    only_critical_addons_enabled = true  # Taint: CriticalAddonsOnly

    node_labels = { role = "system" }

    upgrade_settings {
      max_surge = "33%"
    }
  }

  # Network – Azure CNI for full VNet IP integration + Calico network policies
  network_profile {
    network_plugin     = "azure"
    network_policy     = "calico"
    load_balancer_sku  = "standard"
    outbound_type      = "loadBalancer"
    service_cidr       = "172.16.0.0/16"
    dns_service_ip     = "172.16.0.10"
  }

  # Azure AD RBAC integration
  azure_active_directory_role_based_access_control {
    managed            = true
    azure_rbac_enabled = true
  }

  # Add-ons
  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.aks.id
    msi_auth_for_monitoring_enabled = true
  }

  microsoft_defender {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.aks.id
  }

  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  tags = var.tags
}

# Application node pool
resource "azurerm_kubernetes_cluster_node_pool" "application" {
  name                  = "application"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.main.id
  vm_size               = var.aks_app_node_vm_size
  vnet_subnet_id        = azurerm_subnet.aks.id
  os_disk_size_gb       = 128
  os_disk_type          = "Ephemeral"
  enable_auto_scaling   = true
  min_count             = var.aks_app_node_min_count
  max_count             = var.aks_app_node_max_count
  max_pods              = 110
  node_labels           = { role = "application" }

  upgrade_settings {
    max_surge = "33%"
  }

  tags = var.tags
}

# Spark node pool – spot VMs for cost-effective batch jobs
resource "azurerm_kubernetes_cluster_node_pool" "spark" {
  name                  = "spark"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.main.id
  vm_size               = "Standard_F16s_v2"
  vnet_subnet_id        = azurerm_subnet.aks.id
  priority              = "Spot"
  eviction_policy       = "Delete"
  spot_max_price        = -1  # Pay up to on-demand price
  enable_auto_scaling   = true
  min_count             = 0
  max_count             = 20
  max_pods              = 110

  node_labels = {
    role                                       = "spark"
    "kubernetes.azure.com/scalesetpriority"    = "spot"
  }

  node_taints = [
    "spark-workload=true:NoSchedule",
    "kubernetes.azure.com/scalesetpriority=spot:NoSchedule"
  ]

  tags = var.tags
}

# Managed Identity for application workloads (Workload Identity)
resource "azurerm_user_assigned_identity" "app_workload" {
  name                = "${var.project_name}-app-workload-identity"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = var.tags
}

# Federated credential links the K8s ServiceAccount to the managed identity
resource "azurerm_federated_identity_credential" "app_workload" {
  name                = "${var.project_name}-federated-app"
  resource_group_name = azurerm_resource_group.main.name
  parent_id           = azurerm_user_assigned_identity.app_workload.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.main.oidc_issuer_url
  subject             = "system:serviceaccount:auroraforge:auroraforge-app"
}
