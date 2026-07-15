-- ============================================================================
-- V2: Dedicated idempotency_keys table for cross-service deduplication
--
-- The data_events.idempotency_key column handles same-service dedup.
-- This table handles the case where multiple services (ingestion + sync)
-- both process the same external message, and a shared idempotency store
-- is needed. TTL cleanup is performed by the scheduled purge task.
-- ============================================================================

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key             VARCHAR(256)    NOT NULL,
    service         VARCHAR(64)     NOT NULL,
    result_code     VARCHAR(32),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (key, service)
);

-- Allow efficient cleanup of expired keys by the scheduled purge task
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires
    ON idempotency_keys (expires_at)
    WHERE expires_at < NOW();
