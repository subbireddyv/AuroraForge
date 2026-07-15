package io.auroraforge.ingestion.application.saga;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable-by-convention value object carrying all data a saga step needs,
 * plus the compensation trail (list of already-completed steps in order).
 *
 * Each step receives the context, performs its work, and returns an enriched
 * copy via the {@code withXxx} builder-style methods.
 */
public final class IngestionSagaContext {

    // ── Input ─────────────────────────────────────────────────────────────────
    private final String sagaId;
    private final String requestId;
    private final TenantId tenantId;
    private final String aggregateId;
    private final String eventType;
    private final byte[] rawPayload;
    private final DataClassification classification;
    private final Instant startedAt;

    // ── Step outputs (populated as saga progresses) ─────────────────────────
    private final byte[] encryptedPayload;
    private final String encryptionKeyId;
    private final String storageObjectKey;   // S3/Blob key
    private final Long persistedEventId;     // DB primary key
    private final String kafkaOffset;        // "{partition}@{offset}" for audit

    // ── Compensation trail ────────────────────────────────────────────────────
    private final List<IngestionSagaState> completedSteps; // ordered: first = oldest

    // ── Failure metadata ─────────────────────────────────────────────────────
    private final String failureReason;

    private IngestionSagaContext(Builder b) {
        this.sagaId           = b.sagaId;
        this.requestId        = b.requestId;
        this.tenantId         = b.tenantId;
        this.aggregateId      = b.aggregateId;
        this.eventType        = b.eventType;
        this.rawPayload       = b.rawPayload;
        this.classification   = b.classification;
        this.startedAt        = b.startedAt;
        this.encryptedPayload = b.encryptedPayload;
        this.encryptionKeyId  = b.encryptionKeyId;
        this.storageObjectKey = b.storageObjectKey;
        this.persistedEventId = b.persistedEventId;
        this.kafkaOffset      = b.kafkaOffset;
        this.completedSteps   = Collections.unmodifiableList(new ArrayList<>(b.completedSteps));
        this.failureReason    = b.failureReason;
    }

    // ── Static factory ────────────────────────────────────────────────────────

    public static IngestionSagaContext start(
            String requestId,
            TenantId tenantId,
            String aggregateId,
            String eventType,
            byte[] rawPayload,
            DataClassification classification) {

        return new Builder()
                .sagaId(UUID.randomUUID().toString())
                .requestId(requestId)
                .tenantId(tenantId)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .rawPayload(rawPayload)
                .classification(classification)
                .startedAt(Instant.now())
                .build();
    }

    // ── Enrichment methods (return new instance) ──────────────────────────────

    public IngestionSagaContext withEncryption(byte[] encryptedPayload, String keyId) {
        return toBuilder()
                .encryptedPayload(encryptedPayload)
                .encryptionKeyId(keyId)
                .addCompletedStep(IngestionSagaState.ENCRYPTING)
                .build();
    }

    public IngestionSagaContext withStorageObjectKey(String key) {
        return toBuilder()
                .storageObjectKey(key)
                .addCompletedStep(IngestionSagaState.STORING)
                .build();
    }

    public IngestionSagaContext withPersistedEventId(Long id) {
        return toBuilder()
                .persistedEventId(id)
                .addCompletedStep(IngestionSagaState.PERSISTING)
                .build();
    }

    public IngestionSagaContext withKafkaOffset(String offset) {
        return toBuilder()
                .kafkaOffset(offset)
                .addCompletedStep(IngestionSagaState.PUBLISHING)
                .build();
    }

    public IngestionSagaContext withFailure(String reason) {
        return toBuilder().failureReason(reason).build();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getSagaId()            { return sagaId; }
    public String getRequestId()         { return requestId; }
    public TenantId getTenantId()        { return tenantId; }
    public String getAggregateId()       { return aggregateId; }
    public String getEventType()         { return eventType; }
    public byte[] getRawPayload()        { return rawPayload; }
    public DataClassification getClassification() { return classification; }
    public Instant getStartedAt()        { return startedAt; }
    public byte[] getEncryptedPayload()  { return encryptedPayload; }
    public String getEncryptionKeyId()   { return encryptionKeyId; }
    public String getStorageObjectKey()  { return storageObjectKey; }
    public Long getPersistedEventId()    { return persistedEventId; }
    public String getKafkaOffset()       { return kafkaOffset; }
    public List<IngestionSagaState> getCompletedSteps() { return completedSteps; }
    public String getFailureReason()     { return failureReason; }

    // ── Builder ───────────────────────────────────────────────────────────────

    private Builder toBuilder() {
        Builder b = new Builder();
        b.sagaId           = this.sagaId;
        b.requestId        = this.requestId;
        b.tenantId         = this.tenantId;
        b.aggregateId      = this.aggregateId;
        b.eventType        = this.eventType;
        b.rawPayload       = this.rawPayload;
        b.classification   = this.classification;
        b.startedAt        = this.startedAt;
        b.encryptedPayload = this.encryptedPayload;
        b.encryptionKeyId  = this.encryptionKeyId;
        b.storageObjectKey = this.storageObjectKey;
        b.persistedEventId = this.persistedEventId;
        b.kafkaOffset      = this.kafkaOffset;
        b.completedSteps   = new ArrayList<>(this.completedSteps);
        b.failureReason    = this.failureReason;
        return b;
    }

    private static class Builder {
        String sagaId, requestId, aggregateId, eventType, encryptionKeyId, storageObjectKey, kafkaOffset, failureReason;
        TenantId tenantId;
        byte[] rawPayload, encryptedPayload;
        DataClassification classification;
        Instant startedAt;
        Long persistedEventId;
        List<IngestionSagaState> completedSteps = new ArrayList<>();

        Builder sagaId(String v)               { this.sagaId = v; return this; }
        Builder requestId(String v)            { this.requestId = v; return this; }
        Builder tenantId(TenantId v)           { this.tenantId = v; return this; }
        Builder aggregateId(String v)          { this.aggregateId = v; return this; }
        Builder eventType(String v)            { this.eventType = v; return this; }
        Builder rawPayload(byte[] v)           { this.rawPayload = v; return this; }
        Builder classification(DataClassification v) { this.classification = v; return this; }
        Builder startedAt(Instant v)           { this.startedAt = v; return this; }
        Builder encryptedPayload(byte[] v)     { this.encryptedPayload = v; return this; }
        Builder encryptionKeyId(String v)      { this.encryptionKeyId = v; return this; }
        Builder storageObjectKey(String v)     { this.storageObjectKey = v; return this; }
        Builder persistedEventId(Long v)       { this.persistedEventId = v; return this; }
        Builder kafkaOffset(String v)          { this.kafkaOffset = v; return this; }
        Builder failureReason(String v)        { this.failureReason = v; return this; }
        Builder addCompletedStep(IngestionSagaState s) { this.completedSteps.add(s); return this; }

        IngestionSagaContext build() { return new IngestionSagaContext(this); }
    }
}
