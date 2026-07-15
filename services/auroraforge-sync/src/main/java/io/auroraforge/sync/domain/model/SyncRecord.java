package io.auroraforge.sync.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.Map;

/**
 * Represents one unit of data being synchronized across clouds.
 * Carries the vector clock for causal ordering and conflict detection.
 */
@Getter
@Builder
@With
public class SyncRecord {

    private final String      id;
    private final String      tenantId;
    private final String      aggregateType;
    private final String      aggregateId;
    private final String      sourceCloud;    // "aws" | "azure"
    private final byte[]      payload;
    private final String      encryptionKeyVersion;
    private final VectorClock vectorClock;
    private final long        wallClockTs;    // Unix epoch millis – tiebreaker for LWW
    private final int         schemaVersion;
    private final SyncStatus  syncStatus;
    private final Instant     createdAt;
    private final Instant     lastSyncedAt;
    private final Map<String, String> metadata;

    public boolean isConcurrentWith(SyncRecord other) {
        return vectorClock.compareTo(other.vectorClock) == VectorClock.CausalRelation.CONCURRENT;
    }

    public boolean happenedBefore(SyncRecord other) {
        return vectorClock.compareTo(other.vectorClock) == VectorClock.CausalRelation.HAPPENED_BEFORE;
    }
}
