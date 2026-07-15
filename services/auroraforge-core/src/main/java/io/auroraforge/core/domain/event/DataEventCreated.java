package io.auroraforge.core.domain.event;

import io.auroraforge.core.domain.model.DataClassification;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a new {@link io.auroraforge.core.domain.model.DataEvent} is first created.
 * Consumed by: processing service (starts pipeline), sync service (replication trigger).
 */
public record DataEventCreated(
        String eventId,
        String aggregateId,
        String tenantId,
        String schemaName,
        int schemaVersion,
        DataClassification classification,
        Instant occurredAt
) implements DomainEvent {

    public DataEventCreated(String aggregateId, String tenantId, String schemaName,
                             int schemaVersion, DataClassification classification,
                             Instant occurredAt) {
        this(UUID.randomUUID().toString(), aggregateId, tenantId,
             schemaName, schemaVersion, classification, occurredAt);
    }

    @Override
    public String eventType() {
        return "DataEventCreated";
    }
}
