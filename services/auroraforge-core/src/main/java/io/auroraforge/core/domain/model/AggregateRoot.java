package io.auroraforge.core.domain.model;

import io.auroraforge.core.domain.event.DomainEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for all aggregate roots in AuroraForge.
 *
 * Aggregates collect domain events during state transitions; the application
 * service drains and publishes them after persisting the aggregate.
 * This keeps event publishing consistent with database writes without
 * requiring a distributed transaction.
 *
 * @param <ID> the aggregate's strongly-typed identifier type
 */
public abstract class AggregateRoot<ID extends AggregateId> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    public abstract ID getId();

    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** Called by the application service after the aggregate is persisted. */
    public List<DomainEvent> drainEvents() {
        List<DomainEvent> snapshot = List.copyOf(domainEvents);
        domainEvents.clear();
        return snapshot;
    }

    public List<DomainEvent> peekEvents() {
        return Collections.unmodifiableList(domainEvents);
    }
}
