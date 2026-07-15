package io.auroraforge.sync.infrastructure.resolver;

import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.VectorClock;
import io.auroraforge.sync.domain.model.VectorClock.CausalRelation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Conflict resolution strategies for concurrent cross-cloud writes.
 *
 * Strategy selection based on aggregate type:
 *  - COUNTERS / SETS   → CRDT merge (commutative, associative)
 *  - ENTITY STATE      → vector clock ordering; fallback to LWW on tie
 *  - AUDIT RECORDS     → append-only; no conflict possible
 *
 * The ConflictResolverService is called by both the Cosmos DB change feed listener
 * (Azure side) and the DynamoDB Streams consumer (AWS side – if extended to DynamoDB).
 */
@Slf4j
@Service
public class ConflictResolverService {

    /**
     * Resolves a conflict between two concurrent versions of the same aggregate.
     *
     * @param local   the version currently stored in the local cloud
     * @param remote  the incoming version from the remote cloud
     * @return the winner record that should be persisted
     */
    public SyncRecord resolve(SyncRecord local, SyncRecord remote) {
        CausalRelation relation = local.getVectorClock().compareTo(remote.getVectorClock());

        return switch (relation) {
            case HAPPENED_BEFORE -> {
                log.debug("Remote wins (causal): aggregateId={}", local.getAggregateId());
                yield remote;
            }
            case HAPPENED_AFTER -> {
                log.debug("Local wins (causal): aggregateId={}", local.getAggregateId());
                yield local;
            }
            case EQUAL -> local;  // Same version – idempotent sync
            case CONCURRENT -> resolveConcurrent(local, remote);
        };
    }

    /**
     * Resolves a list of conflicting versions (e.g., from the Cosmos DB conflict feed).
     */
    public SyncRecord resolveAll(List<SyncRecord> conflicts) {
        if (conflicts.isEmpty()) throw new IllegalArgumentException("Conflict list is empty");
        if (conflicts.size() == 1) return conflicts.getFirst();

        return conflicts.stream()
                .reduce(this::resolve)
                .orElseThrow();
    }

    private SyncRecord resolveConcurrent(SyncRecord a, SyncRecord b) {
        log.info("Concurrent write detected: aggregateId={} clouds={}/{} – applying LWW",
                 a.getAggregateId(), a.getSourceCloud(), b.getSourceCloud());

        // Tiebreaker 1: wall-clock timestamp (Last-Write-Wins)
        if (a.getWallClockTs() != b.getWallClockTs()) {
            SyncRecord winner = a.getWallClockTs() > b.getWallClockTs() ? a : b;
            return winner.withSyncStatus(io.auroraforge.sync.domain.model.SyncStatus.CONFLICT_RESOLVED)
                         .withVectorClock(a.getVectorClock().merge(b.getVectorClock()));
        }

        // Tiebreaker 2: lexicographic comparison of source cloud ID (deterministic)
        SyncRecord winner = a.getSourceCloud().compareTo(b.getSourceCloud()) >= 0 ? a : b;
        log.warn("Wall-clock tie resolved by cloud ID: winner={} aggregateId={}",
                 winner.getSourceCloud(), winner.getAggregateId());

        return winner.withSyncStatus(io.auroraforge.sync.domain.model.SyncStatus.CONFLICT_RESOLVED)
                     .withVectorClock(a.getVectorClock().merge(b.getVectorClock()));
    }
}
