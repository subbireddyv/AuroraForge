-- ============================================================================
-- V1: Create core data_events table with all supporting indexes
--
-- Design notes:
--   - payload_encrypted stores the ciphertext blob from KMS/Key Vault envelope
--     encryption. Raw text is NEVER persisted.
--   - metadata is JSONB for flexible key-value pairs without schema changes.
--   - idempotency_key has a UNIQUE index to guarantee exactly-once semantics
--     even under retries; checked in application layer before insert.
--   - version column enables JPA optimistic locking (@Version annotation).
--   - tenant_id is the primary partition dimension; all queries include it.
--   - wal_level=logical (set in docker-compose / RDS parameter group) enables
--     Debezium CDC replication slots on this table.
-- ============================================================================

CREATE TABLE IF NOT EXISTS data_events (
    id                  UUID            NOT NULL,
    tenant_id           VARCHAR(64)     NOT NULL,
    source_system       VARCHAR(128)    NOT NULL,
    event_type          VARCHAR(128)    NOT NULL,
    classification      VARCHAR(32)     NOT NULL
                            CHECK (classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    status              VARCHAR(32)     NOT NULL DEFAULT 'RECEIVED'
                            CHECK (status IN ('RECEIVED','VALIDATING','ENCRYPTING',
                                              'ENCRYPTED','PUBLISHED','FAILED','ARCHIVED')),
    payload_encrypted   BYTEA,
    schema_version      VARCHAR(32)     NOT NULL DEFAULT '1.0',
    idempotency_key     VARCHAR(256)    NOT NULL,
    metadata            JSONB           NOT NULL DEFAULT '{}',
    version             BIGINT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMPTZ,

    CONSTRAINT pk_data_events PRIMARY KEY (id)
);

-- ── Tenant-scoped status queries (most frequent access pattern) ─────────────
CREATE INDEX IF NOT EXISTS idx_data_events_tenant_status
    ON data_events (tenant_id, status);

-- ── Time-series queries per tenant ─────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_data_events_tenant_created
    ON data_events (tenant_id, created_at DESC);

-- ── Idempotency enforcement ─────────────────────────────────────────────────
-- Unique constraint rather than just unique index so JPA can report
-- DataIntegrityViolationException with a predictable constraint name.
ALTER TABLE data_events
    ADD CONSTRAINT uq_data_events_idempotency_key
    UNIQUE (idempotency_key);

-- ── Background reaper queries (status + age) ────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_data_events_status_created
    ON data_events (status, created_at)
    WHERE status IN ('FAILED', 'ARCHIVED');

-- ── Auto-update updated_at on any row change ────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_data_events_updated_at
    BEFORE UPDATE ON data_events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ── Debezium replication slot (created once; idempotent via IF NOT EXISTS) ──
-- This matches the slot name in the Kafka Connect Debezium connector config.
SELECT pg_create_logical_replication_slot('auroraforge_debezium_slot', 'pgoutput')
WHERE NOT EXISTS (
    SELECT 1 FROM pg_replication_slots
    WHERE slot_name = 'auroraforge_debezium_slot'
);

-- ── Publication for Debezium ────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication WHERE pubname = 'auroraforge_publication'
    ) THEN
        EXECUTE 'CREATE PUBLICATION auroraforge_publication FOR TABLE data_events';
    END IF;
END $$;
