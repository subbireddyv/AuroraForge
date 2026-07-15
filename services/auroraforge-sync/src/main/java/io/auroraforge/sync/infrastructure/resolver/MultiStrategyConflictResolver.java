package io.auroraforge.sync.infrastructure.resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.ConflictStrategy;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.SyncStatus;
import io.auroraforge.sync.domain.model.VectorClock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * Multi-strategy conflict resolver extending the basic LWW logic in {@link ConflictResolverService}.
 *
 * Strategy dispatch is per aggregate-type, configured via
 * {@link DisasterRecoveryProperties#strategyByAggregateType()}.
 * This resolver is used by {@link io.auroraforge.sync.application.service.CrossCloudSyncService}
 * for all concurrent writes detected during cross-cloud sync.
 */
@Slf4j
@Service
public class MultiStrategyConflictResolver {

    private final DisasterRecoveryProperties drProps;
    private final ObjectMapper               objectMapper;
    private final Counter                    lwwCounter;
    private final Counter                    vcCounter;
    private final Counter                    priorityCounter;
    private final Counter                    mergeCounter;
    private final Counter                    manualCounter;

    public MultiStrategyConflictResolver(DisasterRecoveryProperties drProps,
                                         ObjectMapper objectMapper,
                                         MeterRegistry registry) {
        this.drProps         = drProps;
        this.objectMapper    = objectMapper;
        this.lwwCounter      = counter(registry, "lww");
        this.vcCounter       = counter(registry, "vector_clock");
        this.priorityCounter = counter(registry, "cloud_priority");
        this.mergeCounter    = counter(registry, "field_merge");
        this.manualCounter   = counter(registry, "manual_review");
    }

    private static Counter counter(MeterRegistry r, String strategy) {
        return r.counter("auroraforge.conflict.resolved", "strategy", strategy);
    }

    /**
     * Resolves a conflict between two concurrent SyncRecords using the strategy
     * configured for the record's aggregate type.
     *
     * @param local  version currently stored in the local cloud
     * @param remote incoming version from the remote cloud
     * @return winning record; or local flagged as {@link SyncStatus#CONFLICT_DETECTED}
     *         when strategy is {@link ConflictStrategy#MANUAL_REVIEW}
     */
    public SyncRecord resolve(SyncRecord local, SyncRecord remote) {
        String aggregateType = local.getAggregateType() != null ? local.getAggregateType() : "default";
        ConflictStrategy strategy = drProps.strategyFor(aggregateType);

        log.info("Conflict resolution: aggregateId={} type={} strategy={} clouds={}/{}",
                local.getAggregateId(), aggregateType, strategy,
                local.getSourceCloud(), remote.getSourceCloud());

        return switch (strategy) {
            case LAST_WRITE_WINS      -> resolveByLww(local, remote);
            case HIGHEST_VECTOR_CLOCK -> resolveByVectorClock(local, remote);
            case CLOUD_PRIORITY       -> resolveByCloudPriority(local, remote);
            case FIELD_MERGE          -> resolveByFieldMerge(local, remote);
            case MANUAL_REVIEW        -> flagForManualReview(local);
        };
    }

    // ── Last-Write-Wins ───────────────────────────────────────────────────────

    private SyncRecord resolveByLww(SyncRecord a, SyncRecord b) {
        SyncRecord winner;
        if (a.getWallClockTs() != b.getWallClockTs()) {
            winner = a.getWallClockTs() > b.getWallClockTs() ? a : b;
        } else {
            // Deterministic tiebreaker: lexicographic cloud ID prevents flip-flop on re-sync
            winner = a.getSourceCloud().compareTo(b.getSourceCloud()) >= 0 ? a : b;
            log.warn("LWW wall-clock tie broken by cloud ID: winner={} aggregateId={}",
                    winner.getSourceCloud(), winner.getAggregateId());
        }
        lwwCounter.increment();
        return winner
                .withSyncStatus(SyncStatus.CONFLICT_RESOLVED)
                .withVectorClock(a.getVectorClock().merge(b.getVectorClock()));
    }

    // ── Vector Clock ──────────────────────────────────────────────────────────

    private SyncRecord resolveByVectorClock(SyncRecord local, SyncRecord remote) {
        VectorClock.CausalRelation relation =
                local.getVectorClock().compareTo(remote.getVectorClock());

        SyncRecord winner = switch (relation) {
            case HAPPENED_BEFORE -> remote;
            case HAPPENED_AFTER, EQUAL -> local;
            case CONCURRENT -> {
                log.debug("Vector clocks concurrent — falling back to LWW: aggregateId={}",
                        local.getAggregateId());
                yield resolveByLww(local, remote);
            }
        };

        vcCounter.increment();
        // Merge the clocks so the result reflects both causal histories
        return winner
                .withSyncStatus(SyncStatus.CONFLICT_RESOLVED)
                .withVectorClock(local.getVectorClock().merge(remote.getVectorClock()));
    }

    // ── Cloud Priority ────────────────────────────────────────────────────────

    private SyncRecord resolveByCloudPriority(SyncRecord local, SyncRecord remote) {
        String primary = drProps.primaryCloud();
        SyncRecord winner = primary.equalsIgnoreCase(local.getSourceCloud()) ? local : remote;
        log.debug("Cloud-priority win: primary={} winner={} aggregateId={}",
                primary, winner.getSourceCloud(), winner.getAggregateId());
        priorityCounter.increment();
        return winner
                .withSyncStatus(SyncStatus.CONFLICT_RESOLVED)
                .withVectorClock(local.getVectorClock().merge(remote.getVectorClock()));
    }

    // ── Field Merge ───────────────────────────────────────────────────────────

    private SyncRecord resolveByFieldMerge(SyncRecord local, SyncRecord remote) {
        try {
            JsonNode localJson  = objectMapper.readTree(local.getPayload());
            JsonNode remoteJson = objectMapper.readTree(remote.getPayload());

            if (!localJson.isObject() || !remoteJson.isObject()) {
                log.warn("Field merge: payload not a JSON object — falling back to LWW: aggregateId={}",
                        local.getAggregateId());
                return resolveByLww(local, remote);
            }

            ObjectNode merged = (ObjectNode) localJson.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> it = remoteJson.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String   field = entry.getKey();
                JsonNode remoteVal = entry.getValue();

                if (!merged.has(field)) {
                    // Field only in remote — take it unconditionally
                    merged.set(field, remoteVal);
                } else {
                    // Same field in both — LWW per-field using wall-clock timestamps
                    JsonNode localVal = merged.get(field);
                    if (!localVal.equals(remoteVal)) {
                        merged.set(field, remote.getWallClockTs() >= local.getWallClockTs()
                                ? remoteVal : localVal);
                    }
                }
            }

            byte[] mergedPayload = objectMapper.writeValueAsBytes(merged);
            mergeCounter.increment();

            SyncRecord base = local.getWallClockTs() >= remote.getWallClockTs() ? local : remote;
            return SyncRecord.builder()
                    .id(base.getId())
                    .tenantId(base.getTenantId())
                    .aggregateType(base.getAggregateType())
                    .aggregateId(base.getAggregateId())
                    .sourceCloud(base.getSourceCloud())
                    .vectorClock(local.getVectorClock().merge(remote.getVectorClock()))
                    .wallClockTs(Math.max(local.getWallClockTs(), remote.getWallClockTs()))
                    .schemaVersion(base.getSchemaVersion())
                    .encryptionKeyVersion(base.getEncryptionKeyVersion())
                    .payload(mergedPayload)
                    .syncStatus(SyncStatus.CONFLICT_RESOLVED)
                    .createdAt(base.getCreatedAt())
                    .lastSyncedAt(Instant.now())
                    .metadata(base.getMetadata())
                    .build();

        } catch (Exception e) {
            log.warn("Field merge failed — falling back to LWW: aggregateId={} error={}",
                    local.getAggregateId(), e.getMessage());
            return resolveByLww(local, remote);
        }
    }

    // ── Manual Review ─────────────────────────────────────────────────────────

    /**
     * Flags the local record for manual review without selecting a winner.
     * The caller is responsible for persisting both the local and remote records to the DLQ.
     */
    private SyncRecord flagForManualReview(SyncRecord local) {
        log.warn("Conflict flagged for MANUAL REVIEW: aggregateId={} tenantId={}",
                local.getAggregateId(), local.getTenantId());
        manualCounter.increment();
        return local.withSyncStatus(SyncStatus.CONFLICT_DETECTED);
    }
}
