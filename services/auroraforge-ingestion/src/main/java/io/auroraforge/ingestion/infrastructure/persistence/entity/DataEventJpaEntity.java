package io.auroraforge.ingestion.infrastructure.persistence.entity;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.EventStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * JPA entity for the data_events table in PostgreSQL.
 *
 * Design notes:
 *  - Uses @Version for optimistic locking (prevents lost updates under concurrency).
 *  - rawPayload stored as bytea (nullable – RESTRICTED events store only encrypted form).
 *  - metadata stored as JSONB for flexible querying without schema changes.
 *  - Partial index on (status, created_at) for efficient stale-event queries.
 */
@Entity
@Table(
        name = "data_events",
        indexes = {
            @Index(name = "idx_events_tenant_status",  columnList = "tenant_id, status"),
            @Index(name = "idx_events_tenant_created", columnList = "tenant_id, created_at"),
            @Index(name = "idx_events_idempotency",    columnList = "idempotency_key", unique = true),
            @Index(name = "idx_events_status_created", columnList = "status, created_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataEventJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 128)
    private String tenantId;

    @Column(name = "schema_name", nullable = false, updatable = false, length = 256)
    private String schemaName;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, updatable = false, length = 20)
    private DataClassification classification;

    @Column(name = "raw_payload", columnDefinition = "bytea")
    private byte[] rawPayload;

    @Column(name = "encrypted_payload", columnDefinition = "bytea")
    private byte[] encryptedPayload;

    @Column(name = "encryption_key_version", length = 64)
    private String encryptionKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "failure_reason", length = 1024)
    private String failureReason;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    /** Stored as JSONB – allows GIN-indexed queries on metadata keys. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, String> metadata;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
