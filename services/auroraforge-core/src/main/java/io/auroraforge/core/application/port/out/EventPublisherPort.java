package io.auroraforge.core.application.port.out;

import io.auroraforge.core.domain.event.DomainEvent;

import java.util.List;

/**
 * Output port: async domain event publishing.
 * Implemented by the Kafka infrastructure adapter.
 * The application service calls this after the aggregate is persisted (outbox pattern).
 */
public interface EventPublisherPort {

    /** Publish a single domain event. Fire-and-forget; retry on failure is Kafka's responsibility. */
    void publish(DomainEvent event);

    /** Batch publish – more efficient for bulk ingestion scenarios. */
    void publishAll(List<DomainEvent> events);
}
