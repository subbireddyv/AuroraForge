-- ============================================================================
-- V3: Transactional Outbox Events Table
--
-- Written in the SAME transaction as data_events (see PersistEventStep).
-- Debezium CDC reads this table via logical replication and publishes records
-- to Kafka topic: auroraforge.public.outbox_events
--
-- This eliminates the dual-write race condition in the pre-saga ingestion path:
--   OLD: persist to DB → publish to Kafka (two separate operations, not atomic)
--   NEW: persist DB row + outbox row in one ACID transaction → Debezium relays
--
-- Once Debezium CDC is enabled, PublishEventStep can be removed from the saga.
-- ============================================================================

CREATE TABLE IF NOT EXISTS outbox_events (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id          VARCHAR(36) NOT NULL,
    aggregate_id     VARCHAR(255) NOT NULL,
    aggregate_type   VARCHAR(100) NOT NULL DEFAULT 'DataEvent',
    event_type       VARCHAR(100) NOT NULL,
    tenant_id        VARCHAR(255) NOT NULL,
    payload          BYTEA       NOT NULL,
    payload_size     INT         NOT NULL DEFAULT 0,
    classification   VARCHAR(20) NOT NULL,
    encryption_key_id VARCHAR(255),
    storage_object_key VARCHAR(512),
    schema_version   VARCHAR(20) NOT NULL DEFAULT '1.0',
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at     TIMESTAMPTZ
);

-- Debezium reads in creation order; this index supports its WAL-position-based filtering
CREATE INDEX idx_outbox_events_created_at  ON outbox_events (created_at);
CREATE INDEX idx_outbox_events_tenant_id   ON outbox_events (tenant_id);
CREATE INDEX idx_outbox_events_saga_id     ON outbox_events (saga_id);
-- Unique on saga_id enforces idempotency: duplicate saga executions hit this constraint
CREATE UNIQUE INDEX idx_outbox_events_saga_id_unique ON outbox_events (saga_id);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox: rows written atomically with data_events, relayed to Kafka by Debezium CDC.';
COMMENT ON COLUMN outbox_events.status IS
    'PENDING = awaiting CDC relay; PUBLISHED = confirmed by Debezium; FAILED = relay error';
