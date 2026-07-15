package io.auroraforge.core.domain.event;

import io.auroraforge.core.domain.model.EventStatus;

import java.time.Instant;
import java.util.UUID;

/** Emitted on every status transition of a DataEvent aggregate. */
public record DataEventStatusChanged(
        String eventId,
        String aggregateId,
        String tenantId,
        EventStatus previousStatus,
        EventStatus newStatus,
        Instant occurredAt
) implements DomainEvent {

    public DataEventStatusChanged(String aggregateId, String tenantId,
                                   EventStatus previousStatus, EventStatus newStatus,
                                   Instant occurredAt) {
        this(UUID.randomUUID().toString(), aggregateId, tenantId,
             previousStatus, newStatus, occurredAt);
    }

    @Override
    public String eventType() {
        return "DataEventStatusChanged";
    }
}
