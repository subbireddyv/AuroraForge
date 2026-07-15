package io.auroraforge.core.domain.model;

import java.util.UUID;

/**
 * Strongly-typed identity for a {@link DataEvent} aggregate.
 * Using a record ensures structural equality and immutability at zero cost.
 */
public record EventId(String value) implements AggregateId {

    public EventId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EventId must not be blank");
        }
    }

    public static EventId generate() {
        return new EventId(UUID.randomUUID().toString());
    }

    public static EventId of(String value) {
        return new EventId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
