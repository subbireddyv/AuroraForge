package io.auroraforge.sync.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Result of a cross-cloud data consistency verification run produced by
 * {@link io.auroraforge.sync.application.service.ConsistencyVerificationService}.
 *
 * Consistency is verified by comparing SHA-256 checksums of payloads stored in
 * AWS and Azure for the same (tenantId, aggregateId) pair.
 */
public record ConsistencyReport(
        String       tenantId,
        int          sampledRecords,
        int          consistentRecords,
        int          divergedRecords,
        List<String> divergedAggregateIds,
        double       consistencyPercent,
        Instant      generatedAt
) {

    /** Convenience factory for a fully consistent result. */
    public static ConsistencyReport perfect(String tenantId, int sampled) {
        return new ConsistencyReport(
                tenantId, sampled, sampled, 0, List.of(), 100.0, Instant.now());
    }

    public boolean isFullyConsistent() {
        return divergedRecords == 0;
    }
}
