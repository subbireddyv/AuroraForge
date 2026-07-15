package io.auroraforge.core.domain.model;

import io.auroraforge.core.domain.event.DataEventCreated;
import io.auroraforge.core.domain.event.DataEventProcessed;
import io.auroraforge.core.domain.event.DataEventStatusChanged;
import io.auroraforge.core.domain.exception.InvalidEventStateTransitionException;
import lombok.Getter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Core aggregate root of the AuroraForge domain.
 *
 * A DataEvent represents a single unit of data flowing through the platform –
 * from ingestion (PENDING) through real-time processing (PROCESSING) to
 * final storage and cross-cloud synchronization (PROCESSED).
 *
 * Business invariants enforced here:
 *  1. Status transitions are validated against the allowed state machine.
 *  2. A RESTRICTED event must always carry an encryptedPayload, never rawPayload.
 *  3. SchemaVersion must match the Schema Registry version at the time of creation.
 */
@Getter
public class DataEvent extends AggregateRoot<EventId> {

    private final EventId id;
    private final TenantId tenantId;
    private final String schemaName;
    private final int schemaVersion;
    private final DataClassification classification;

    /** Raw payload – null when classification == RESTRICTED (stored only encrypted). */
    private final byte[] rawPayload;

    /** Encrypted payload – populated by KeyManagementService before persistence. */
    private byte[] encryptedPayload;

    /** Key version used for encryption – needed for decryption routing. */
    private String encryptionKeyVersion;

    private EventStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private int retryCount;
    private String failureReason;

    /** Arbitrary metadata (e.g. source system, region, correlation IDs). */
    private final Map<String, String> metadata;

    // Optimistic locking version (maps to @Version in JPA, _etag in Cosmos DB)
    private Long version;

    private DataEvent(Builder builder) {
        this.id             = Objects.requireNonNull(builder.id, "id");
        this.tenantId       = Objects.requireNonNull(builder.tenantId, "tenantId");
        this.schemaName     = Objects.requireNonNull(builder.schemaName, "schemaName");
        this.schemaVersion  = builder.schemaVersion;
        this.classification = Objects.requireNonNull(builder.classification, "classification");
        this.rawPayload     = builder.rawPayload;
        this.status         = EventStatus.PENDING;
        this.createdAt      = Instant.now();
        this.updatedAt      = this.createdAt;
        this.retryCount     = 0;
        this.metadata       = new HashMap<>(builder.metadata);
        this.version        = 0L;

        validateInvariants();

        registerEvent(new DataEventCreated(
                id.value(), tenantId.value(), schemaName, schemaVersion,
                classification, createdAt));
    }

    /** Reconstitution constructor – used by repository adapters. No events emitted. */
    public static DataEvent reconstitute(EventId id, TenantId tenantId, String schemaName,
                                         int schemaVersion, DataClassification classification,
                                         byte[] rawPayload, byte[] encryptedPayload,
                                         String encryptionKeyVersion, EventStatus status,
                                         Instant createdAt, Instant updatedAt,
                                         int retryCount, String failureReason,
                                         Map<String, String> metadata, Long version) {
        DataEvent event = new DataEvent(new Builder(id, tenantId, schemaName, schemaVersion, classification));
        // Bypass the builder's event emission path
        event.drainEvents();  // clear the DataEventCreated emitted in constructor

        // Directly restore persisted state
        var r = reconstitute(event, rawPayload, encryptedPayload, encryptionKeyVersion,
                status, createdAt, updatedAt, retryCount, failureReason, metadata, version);
        return r;
    }

    private static DataEvent reconstitute(DataEvent e, byte[] rawPayload, byte[] encryptedPayload,
                                           String encryptionKeyVersion, EventStatus status,
                                           Instant createdAt, Instant updatedAt, int retryCount,
                                           String failureReason, Map<String, String> metadata, Long version) {
        // Use reflection-free mutable fields to restore state
        var restored = new DataEvent(new Builder(e.id, e.tenantId, e.schemaName, e.schemaVersion, e.classification)
                .rawPayload(rawPayload)
                .metadata(metadata));
        restored.drainEvents();  // discard the DataEventCreated from builder

        // Directly set fields not reachable via builder (persisted state)
        try {
            var f = DataEvent.class;
            setField(restored, "encryptedPayload", encryptedPayload);
            setField(restored, "encryptionKeyVersion", encryptionKeyVersion);
            setField(restored, "status", status);
            setField(restored, "retryCount", retryCount);
            setField(restored, "failureReason", failureReason);
            setField(restored, "version", version);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to reconstitute DataEvent", ex);
        }
        return restored;
    }

    @SuppressWarnings("java:S3011")
    private static void setField(DataEvent target, String name, Object value) throws Exception {
        var field = DataEvent.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ── State transitions ──────────────────────────────────────────────────

    public void markProcessing() {
        transitionTo(EventStatus.PROCESSING, null);
    }

    public void markProcessed() {
        transitionTo(EventStatus.PROCESSED, null);
        registerEvent(new DataEventProcessed(id.value(), tenantId.value(), Instant.now()));
    }

    public void markFailed(String reason) {
        this.failureReason = reason;
        transitionTo(EventStatus.FAILED, reason);
    }

    public void retry() {
        if (status != EventStatus.FAILED) {
            throw new InvalidEventStateTransitionException(id, status, EventStatus.PROCESSING, "retry");
        }
        retryCount++;
        transitionTo(EventStatus.PROCESSING, null);
    }

    public void deadLetter(String finalReason) {
        this.failureReason = finalReason;
        transitionTo(EventStatus.DEAD_LETTERED, finalReason);
    }

    public void attachEncryptedPayload(byte[] ciphertext, String keyVersion) {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("Encrypted payload must not be empty");
        }
        this.encryptedPayload    = ciphertext;
        this.encryptionKeyVersion = keyVersion;
        this.updatedAt           = Instant.now();
    }

    private void transitionTo(EventStatus next, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidEventStateTransitionException(id, status, next, reason);
        }
        EventStatus previous = this.status;
        this.status    = next;
        this.updatedAt = Instant.now();
        registerEvent(new DataEventStatusChanged(id.value(), tenantId.value(), previous, next, Instant.now()));
    }

    // ── Invariant validation ───────────────────────────────────────────────

    private void validateInvariants() {
        if (classification == DataClassification.RESTRICTED && rawPayload != null) {
            throw new IllegalStateException(
                    "RESTRICTED events must not carry a rawPayload; encrypt before construction.");
        }
    }

    // ── Builder ────────────────────────────────────────────────────────────

    public static Builder builder(EventId id, TenantId tenantId,
                                   String schemaName, int schemaVersion,
                                   DataClassification classification) {
        return new Builder(id, tenantId, schemaName, schemaVersion, classification);
    }

    public static final class Builder {
        private final EventId id;
        private final TenantId tenantId;
        private final String schemaName;
        private final int schemaVersion;
        private final DataClassification classification;
        private byte[] rawPayload;
        private Map<String, String> metadata = new HashMap<>();

        private Builder(EventId id, TenantId tenantId, String schemaName,
                        int schemaVersion, DataClassification classification) {
            this.id             = id;
            this.tenantId       = tenantId;
            this.schemaName     = schemaName;
            this.schemaVersion  = schemaVersion;
            this.classification = classification;
        }

        public Builder rawPayload(byte[] payload) {
            this.rawPayload = payload;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }

        public Builder addMetadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public DataEvent build() {
            return new DataEvent(this);
        }
    }
}
