package io.auroraforge.core.domain.event;

import java.time.Instant;
import java.util.UUID;

/** Emitted when a DataEvent successfully completes processing. */
public record DataEventProcessed(
        String eventId,
        String aggregateId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    public DataEventProcessed(String aggregateId, String tenantId, Instant occurredAt) {
        this(UUID.randomUUID().toString(), aggregateId, tenantId, occurredAt);
    }

    @Override
    public String eventType() {
        return "DataEventProcessed";
    }
}
