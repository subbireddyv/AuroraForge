package io.auroraforge.sync.domain.model;

/**
 * Conflict resolution strategies dispatched by {@link io.auroraforge.sync.infrastructure.resolver.MultiStrategyConflictResolver}.
 *
 * Strategy is selected per aggregate-type via {@code auroraforge.dr.strategy-by-aggregate-type}.
 * Absent entries default to {@link #HIGHEST_VECTOR_CLOCK}.
 */
public enum ConflictStrategy {

    /**
     * Winner is the record with the highest wall-clock timestamp.
     * Safe for immutable event payloads; unreliable under clock skew (use NTP / AWS Time Sync Service).
     * Tiebreaker: lexicographic source-cloud ID (deterministic).
     */
    LAST_WRITE_WINS,

    /**
     * Winner is determined by vector-clock causal ordering.
     * Concurrent writes (clocks incomparable) fall back to {@link #LAST_WRITE_WINS}.
     * Preferred for mutable entity state where causality is tracked.
     */
    HIGHEST_VECTOR_CLOCK,

    /**
     * Configured primary cloud always wins; the secondary cloud's write is discarded on conflict.
     * Set via {@code auroraforge.dr.primary-cloud}.
     * Intended for authoritative reference data such as tenant configuration records.
     */
    CLOUD_PRIORITY,

    /**
     * JSON field-level three-way merge: all non-conflicting field changes are preserved.
     * Per-field conflicts fall back to {@link #LAST_WRITE_WINS} at the field granularity.
     * Requires the payload to be a valid UTF-8 JSON object.
     */
    FIELD_MERGE,

    /**
     * No automatic winner; the record is flagged {@link SyncStatus#CONFLICT_DETECTED} and
     * placed in the DLQ for human sign-off.
     * Intended for {@link io.auroraforge.core.domain.model.DataClassification#RESTRICTED}
     * records where automated resolution would violate compliance policy.
     */
    MANUAL_REVIEW
}
