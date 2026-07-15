-- ============================================================
-- AuroraForge :: Sync Service — DLQ schema
-- Stores failed and manually-reviewed sync records for retry.
-- ============================================================

CREATE TABLE IF NOT EXISTS sync_dlq (
    id                      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               VARCHAR(64)     NOT NULL,
    aggregate_type          VARCHAR(128),
    aggregate_id            VARCHAR(255),
    source_cloud            VARCHAR(32),
    target_cloud            VARCHAR(32),
    payload_b64             TEXT,
    competing_payload_b64   TEXT,
    vector_clock_json       VARCHAR(1024),
    wall_clock_ts           BIGINT          NOT NULL DEFAULT 0,
    schema_version          INTEGER         NOT NULL DEFAULT 1,
    encryption_key_version  VARCHAR(64),
    status                  VARCHAR(32)     NOT NULL DEFAULT 'PENDING_RETRY',
    conflict_strategy       VARCHAR(32),
    error_message           VARCHAR(1024),
    retry_count             INTEGER         NOT NULL DEFAULT 0,
    next_retry_at           TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    last_attempted_at       TIMESTAMPTZ,
    version                 BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_dlq_status CHECK (
        status IN ('PENDING_RETRY', 'IN_RETRY', 'CONFLICT_REVIEW', 'EXHAUSTED', 'RESOLVED')
    ),
    CONSTRAINT chk_dlq_conflict_strategy CHECK (
        conflict_strategy IS NULL OR conflict_strategy IN (
            'LAST_WRITE_WINS', 'HIGHEST_VECTOR_CLOCK', 'CLOUD_PRIORITY',
            'FIELD_MERGE', 'MANUAL_REVIEW'
        )
    )
);

-- Lookup: scheduler queries by status + next_retry_at
CREATE INDEX IF NOT EXISTS idx_dlq_next_retry_at
    ON sync_dlq (status, next_retry_at)
    WHERE status = 'PENDING_RETRY';

-- Lookup: dashboard / REST API queries per tenant
CREATE INDEX IF NOT EXISTS idx_dlq_tenant_status
    ON sync_dlq (tenant_id, status, created_at DESC);

-- Lookup: finding specific aggregate conflicts
CREATE INDEX IF NOT EXISTS idx_dlq_aggregate
    ON sync_dlq (tenant_id, aggregate_id)
    WHERE aggregate_id IS NOT NULL;

-- Lookup: CONFLICT_REVIEW records awaiting human sign-off
CREATE INDEX IF NOT EXISTS idx_dlq_conflict_review
    ON sync_dlq (status, created_at ASC)
    WHERE status = 'CONFLICT_REVIEW';

COMMENT ON TABLE sync_dlq IS
    'Dead Letter Queue for failed cross-cloud sync operations and manual-review conflicts.';
