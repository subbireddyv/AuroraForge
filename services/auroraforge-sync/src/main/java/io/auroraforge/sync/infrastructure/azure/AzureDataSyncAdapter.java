package io.auroraforge.sync.infrastructure.azure;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import io.auroraforge.sync.domain.model.SyncRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Infrastructure adapter: syncs data to Azure Cosmos DB NoSQL API.
 *
 * Uses the synchronous Cosmos DB Java SDK with optimistic concurrency via ETags.
 * ETag is mapped to the VectorClock version to enable server-side conflict detection.
 */
@Slf4j
@Component
public class AzureDataSyncAdapter {

    private final CosmosContainer container;

    public AzureDataSyncAdapter(CosmosClient cosmosClient,
                                 @Value("${auroraforge.azure.cosmos.database:auroraforge}")
                                 String database,
                                 @Value("${auroraforge.azure.cosmos.container:aggregates}")
                                 String containerName) {
        this.container = cosmosClient.getDatabase(database).getContainer(containerName);
    }

    public void upsert(SyncRecord record) {
        CosmosDocument doc  = CosmosDocument.from(record);
        CosmosItemRequestOptions options = new CosmosItemRequestOptions();

        // Optimistic concurrency: only update if ETag matches
        if (record.getVectorClock() != null) {
            options.setIfMatchETag(record.getVectorClock().clocks().toString());
        }

        container.upsertItem(doc, new PartitionKey(record.getTenantId()), options);
        log.debug("Upserted to Cosmos DB: tenantId={} aggregateId={}",
                  record.getTenantId(), record.getAggregateId());
    }

    public Optional<SyncRecord> findExisting(String tenantId, String aggregateId) {
        if (aggregateId == null) return Optional.empty();
        try {
            var response = container.readItem(aggregateId, new PartitionKey(tenantId),
                                              CosmosDocument.class);
            return Optional.of(response.getItem().toSyncRecord());
        } catch (com.azure.cosmos.CosmosException e) {
            if (e.getStatusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    /** Internal DTO for Cosmos DB serialization. */
    record CosmosDocument(
            String id,
            String tenantId,
            String aggregateType,
            String aggregateId,
            String sourceCloud,
            String vectorClockJson,
            long   wallClockTs,
            int    schemaVersion,
            byte[] payload
    ) {
        static CosmosDocument from(SyncRecord r) {
            return new CosmosDocument(
                    r.getAggregateId() != null ? r.getAggregateId() : r.getId(),
                    r.getTenantId(),
                    r.getAggregateType(),
                    r.getAggregateId(),
                    r.getSourceCloud(),
                    r.getVectorClock() != null ? r.getVectorClock().toString() : "{}",
                    r.getWallClockTs(),
                    r.getSchemaVersion(),
                    r.getPayload());
        }

        SyncRecord toSyncRecord() {
            return SyncRecord.builder()
                    .id(id)
                    .tenantId(tenantId)
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .sourceCloud(sourceCloud)
                    .wallClockTs(wallClockTs)
                    .schemaVersion(schemaVersion)
                    .payload(payload)
                    .vectorClock(io.auroraforge.sync.domain.model.VectorClock.empty())
                    .syncStatus(io.auroraforge.sync.domain.model.SyncStatus.SYNCED)
                    .build();
        }
    }
}
