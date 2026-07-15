package io.auroraforge.core.domain.model;

import java.io.Serializable;

/**
 * Marker interface for all strongly-typed aggregate identifiers.
 * Implementing value objects should be Java records for immutability
 * and structural equality.
 */
public interface AggregateId extends Serializable {
    String value();
}
