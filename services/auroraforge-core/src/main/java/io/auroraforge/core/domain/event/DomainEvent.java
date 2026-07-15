package io.auroraforge.core.domain.event;

import java.time.Instant;

/**
 * Marker interface for all domain events emitted by AuroraForge aggregates.
 *
 * Domain events:
 *  - Are immutable facts about something that happened in the domain.
 *  - Carry enough context to be useful without additional queries.
 *  - Are named in past tense (DataEventCreated, DataEventProcessed, etc.).
 *  - Are published asynchronously to Kafka after the aggregate is persisted.
 */
public interface DomainEvent {

    /** Globally unique event ID for idempotent consumer de-duplication. */
    String eventId();

    /** ISO-8601 timestamp when this domain event occurred. */
    Instant occurredAt();

    /** Discriminator used for Kafka topic routing and deserialization. */
    String eventType();
}
