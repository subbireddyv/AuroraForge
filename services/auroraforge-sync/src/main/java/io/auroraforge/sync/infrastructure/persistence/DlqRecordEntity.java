package io.auroraforge.sync.infrastructure.persistence;

import io.auroraforge.sync.domain.model.ConflictStrategy;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.SyncStatus;
import io.auroraforge.sync.domain.model.VectorClock;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity for sync failures and MANUAL_REVIEW conflicts held in the Dead Letter Queue.
 *
 * Retry lifecycle:
 *   PENDING_RETRY → IN_RETRY → RESOLVED (success)
 *                           → PENDING_RETRY (back-off, retryCount < maxRetries)
 *                           → EXHAUSTED    (retryCount ≥ maxRetries)
 *
 * CONFLICT_REVIEW: set when the conflict strategy is MANUAL_REVIEW;
 *   {@code competingPayloadB64} carries the remote record's payload for side-by-side comparison.
 */
@Entity
@Table(
    name = "sync_dlq",
    indexes = {
        @Index(name = "idx_dlq_tenant_status",   columnList = "tenant_id, status"),
        @Index(name = "idx_dlq_next_retry_at",   columnList = "next_retry_at"),
        @Index(name = "idx_dlq_aggregate",        columnList = "tenant_id, aggregate_id")
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DlqRecordEntity {

    public enum DlqStatus {
        PENDING_RETRY,
        IN_RETRY,
        CONFLICT_REVIEW,
        EXHAUSTED,
        RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id",      nullable = false, length = 64)
    private String tenantId;

    @Column(name = "aggregate_type", length = 128)
    private String aggregateType;

    @Column(name = "aggregate_id",   length = 255)
    private String aggregateId;

    @Column(name = "source_cloud",   length = 32)
    private String sourceCloud;

    @Column(name = "target_cloud",   length = 32)
    private String targetCloud;

    /** Base64-encoded payload bytes. */
    @Lob
    @Column(name = "payload_b64")
    private String payloadB64;

    /** Base64-encoded competing record payload (used for CONFLICT_REVIEW). */
    @Lob
    @Column(name = "competing_payload_b64")
    private String competingPayloadB64;

    @Column(name = "vector_clock_json", length = 1024)
    private String vectorClockJson;

    @Column(name = "wall_clock_ts")
    private long wallClockTs;

    @Column(name = "schema_version")
    private int schemaVersion;

    @Column(name = "encryption_key_version", length = 64)
    private String encryptionKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DlqStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_strategy", length = 32)
    private ConflictStrategy conflictStrategy;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Version
    private long version;

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (status    == null) status    = DlqStatus.PENDING_RETRY;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates a PENDING_RETRY entry for a failed sync write.
     */
    public static DlqRecordEntity fromSyncFailure(SyncRecord record,
                                                   String targetCloud,
                                                   String errorMessage,
                                                   Instant nextRetryAt) {
        return DlqRecordEntity.builder()
                .tenantId(record.getTenantId())
                .aggregateType(record.getAggregateType())
                .aggregateId(record.getAggregateId())
                .sourceCloud(record.getSourceCloud())
                .targetCloud(targetCloud)
                .payloadB64(record.getPayload() != null
                        ? Base64.getEncoder().encodeToString(record.getPayload())
                        : null)
                .vectorClockJson(record.getVectorClock() != null
                        ? record.getVectorClock().toString()
                        : "{}")
                .wallClockTs(record.getWallClockTs())
                .schemaVersion(record.getSchemaVersion())
                .encryptionKeyVersion(record.getEncryptionKeyVersion())
                .status(DlqStatus.PENDING_RETRY)
                .errorMessage(truncate(errorMessage, 1024))
                .retryCount(0)
                .nextRetryAt(nextRetryAt)
                .build();
    }

    /**
     * Creates a CONFLICT_REVIEW entry when MANUAL_REVIEW strategy is selected.
     */
    public static DlqRecordEntity fromConflict(SyncRecord local,
                                                SyncRecord remote,
                                                ConflictStrategy strategy) {
        DlqRecordEntity entity = fromSyncFailure(
                local, remote.getSourceCloud(),
                "Manual review required for conflict", Instant.now());
        entity.setStatus(DlqStatus.CONFLICT_REVIEW);
        entity.setConflictStrategy(strategy);
        if (remote.getPayload() != null) {
            entity.setCompetingPayloadB64(
                    Base64.getEncoder().encodeToString(remote.getPayload()));
        }
        return entity;
    }

    /**
     * Reconstructs a SyncRecord from this DLQ entry for retry purposes.
     */
    public SyncRecord toSyncRecord() {
        byte[] payload = payloadB64 != null
                ? Base64.getDecoder().decode(payloadB64)
                : new byte[0];
        return SyncRecord.builder()
                .tenantId(tenantId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .sourceCloud(sourceCloud)
                .payload(payload)
                .vectorClock(VectorClock.empty())
                .wallClockTs(wallClockTs)
                .schemaVersion(schemaVersion)
                .encryptionKeyVersion(encryptionKeyVersion)
                .syncStatus(SyncStatus.PENDING)
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
