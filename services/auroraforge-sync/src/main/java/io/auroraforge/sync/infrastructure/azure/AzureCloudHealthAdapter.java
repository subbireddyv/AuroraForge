package io.auroraforge.sync.infrastructure.azure;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.models.CosmosDatabaseRequestOptions;
import io.auroraforge.sync.domain.model.CloudHealth;
import io.auroraforge.sync.domain.port.CloudHealthPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Azure cloud health probe.
 *
 * Reads the Cosmos DB database metadata — a lightweight control-plane call
 * that confirms network connectivity and Cosmos account reachability.
 * No documents are read or written.
 */
@Slf4j
@Component
public class AzureCloudHealthAdapter implements CloudHealthPort {

    private final CosmosClient cosmosClient;
    private final String database;

    public AzureCloudHealthAdapter(
            CosmosClient cosmosClient,
            @Value("${auroraforge.azure.cosmos.database:auroraforge}") String database) {
        this.cosmosClient = cosmosClient;
        this.database     = database;
    }

    @Override
    public String cloudProvider() {
        return "azure";
    }

    @Override
    public CloudHealth probe() {
        long start = System.currentTimeMillis();
        try {
            cosmosClient.getDatabase(database).read(new CosmosDatabaseRequestOptions());
            long latencyMs = System.currentTimeMillis() - start;
            log.debug("Azure health OK: latencyMs={}", latencyMs);
            return CloudHealth.healthy("azure", latencyMs);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("Azure health check FAILED after {}ms: {}", latencyMs, e.getMessage());
            return CloudHealth.unreachable("azure", e.getMessage());
        }
    }
}
