# =============================================================================
# Azure Cosmos DB – Multi-region active-active write
# Using the NoSQL API (document model) for maximum geo-distribution flexibility.
# Consistency: BoundedStaleness for cross-region reads (low-latency + strong ordering).
# Conflict resolution: Last-Write-Wins on _ts field (application-level merge for
#                      domain objects via conflict feed + ConflictResolverService).
# =============================================================================

resource "azurerm_cosmosdb_account" "main" {
  name                = "${var.project_name}-cosmos-${var.environment}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  offer_type          = "Standard"
  kind                = "GlobalDocumentDB"

  # CMK encryption using Key Vault key
  key_vault_key_id = azurerm_key_vault_key.cosmos.versionless_id

  # IP restriction – only allow AKS egress IPs and private endpoints
  is_virtual_network_filter_enabled = true

  virtual_network_rule {
    id = azurerm_subnet.services.id
  }

  # BoundedStaleness: up to 300 seconds or 100K operations behind primary
  # This guarantees ordering while keeping cross-region reads fast
  consistency_policy {
    consistency_level       = "BoundedStaleness"
    max_interval_in_seconds = 300
    max_staleness_prefix    = 100000
  }

  # Active-active: both regions accept writes
  geo_location {
    location          = var.location
    failover_priority = 0
    zone_redundant    = true
  }

  geo_location {
    location          = var.secondary_location
    failover_priority = 1
    zone_redundant    = true
  }

  # Automatic failover when primary region is unavailable
  enable_automatic_failover       = true
  enable_multiple_write_locations = true  # Active-active writes

  # Continuous backup for point-in-time restore (up to 30 days)
  backup {
    type                = "Continuous"
    tier                = "Continuous30Days"
  }

  # Analytics store for Synapse Link (zero-ETL analytics on operational data)
  analytical_storage_enabled = true
  analytical_storage {
    schema_type = "WellDefined"
  }

  # Disable public access – only private endpoints
  public_network_access_enabled  = false
  network_acl_bypass_for_azure_services = false

  tags = merge(var.tags, { DataClassification = "restricted" })
}

# ---- Database ---------------------------------------------------------------
resource "azurerm_cosmosdb_sql_database" "main" {
  name                = "auroraforge"
  resource_group_name = azurerm_resource_group.main.name
  account_name        = azurerm_cosmosdb_account.main.name
  # No throughput at DB level – provisioned per container for isolation
}

# ---- Events Container (real-time event store) --------------------------------
resource "azurerm_cosmosdb_sql_container" "events" {
  name                  = "events"
  resource_group_name   = azurerm_resource_group.main.name
  account_name          = azurerm_cosmosdb_account.main.name
  database_name         = azurerm_cosmosdb_sql_database.main.name
  partition_key_path    = "/tenantId"     # High-cardinality partition for even distribution
  partition_key_version = 2

  throughput = var.cosmos_db_throughput

  indexing_policy {
    indexing_mode = "consistent"

    included_path { path = "/*" }

    excluded_path { path = "/payload/*" }     # Binary payload – not indexed
    excluded_path { path = "/_etag/?" }

    composite_index {
      index { path = "/tenantId"; order = "Ascending" }
      index { path = "/eventTime"; order = "Descending" }
    }
  }

  # Last-Write-Wins conflict resolution (by event timestamp)
  conflict_resolution_policy {
    mode                          = "LastWriterWins"
    conflict_resolution_path      = "/_ts"
  }

  unique_key {
    paths = ["/eventId"]
  }

  # TTL – events expire after 90 days by default; containers can override
  default_ttl = 7776000  # 90 days in seconds

  analytical_storage_ttl = -1  # Keep forever in analytical store (Synapse Link)
}

# ---- Aggregates Container (domain aggregates / entity state) ----------------
resource "azurerm_cosmosdb_sql_container" "aggregates" {
  name                  = "aggregates"
  resource_group_name   = azurerm_resource_group.main.name
  account_name          = azurerm_cosmosdb_account.main.name
  database_name         = azurerm_cosmosdb_sql_database.main.name
  partition_key_path    = "/aggregateType"
  partition_key_version = 2

  throughput = var.cosmos_db_throughput

  indexing_policy {
    indexing_mode = "consistent"
    included_path { path = "/*" }
    excluded_path { path = "/data/binaryPayload/?" }
  }

  # Custom conflict resolution: resolved by ConflictResolverService via stored procedure
  conflict_resolution_policy {
    mode                             = "Custom"
    conflict_resolution_procedure_id = azurerm_cosmosdb_sql_stored_procedure.conflict_resolver.name
  }

  unique_key {
    paths = ["/aggregateId", "/version"]
  }
}

# Stored procedure for custom conflict resolution
resource "azurerm_cosmosdb_sql_stored_procedure" "conflict_resolver" {
  name                = "resolveConflict"
  resource_group_name = azurerm_resource_group.main.name
  account_name        = azurerm_cosmosdb_account.main.name
  database_name       = azurerm_cosmosdb_sql_database.main.name
  container_name      = azurerm_cosmosdb_sql_container.aggregates.name

  # Stored proc reads the conflict feed and applies vector-clock merge logic.
  # The Java ConflictResolverService triggers this via the Change Feed.
  body = <<-JAVASCRIPT
    function resolveConflict(incomingItem, existingItem, isTombstone, conflictingItems) {
      // Vector clock comparison: higher version wins; on tie, higher _ts wins.
      if (!existingItem) { return incomingItem; }
      if (isTombstone) { return null; }

      var incoming = incomingItem.version || 0;
      var existing = existingItem.version || 0;

      if (incoming > existing) { return incomingItem; }
      if (existing > incoming) { return existingItem; }

      // Same version – use wall clock as tiebreaker
      return (incomingItem._ts >= existingItem._ts) ? incomingItem : existingItem;
    }
  JAVASCRIPT
}

# ---- Private Endpoint for Cosmos DB ----------------------------------------
resource "azurerm_private_endpoint" "cosmos" {
  name                = "${var.project_name}-cosmos-pe"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  subnet_id           = azurerm_subnet.services.id

  private_service_connection {
    name                           = "cosmos-psc"
    private_connection_resource_id = azurerm_cosmosdb_account.main.id
    subresource_names              = ["Sql"]
    is_manual_connection           = false
  }

  tags = var.tags
}
