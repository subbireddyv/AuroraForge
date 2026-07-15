-- ============================================================
-- AuroraForge Key Management Service — Schema V1
-- Creates the key_versions audit table.
-- ============================================================

CREATE TABLE IF NOT EXISTS key_versions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        VARCHAR(64)  NOT NULL,
    classification   VARCHAR(32)  NOT NULL
                         CHECK (classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    key_version      VARCHAR(512) NOT NULL,
    cloud_provider   VARCHAR(16)  NOT NULL
                         CHECK (cloud_provider IN ('AWS','AZURE','LOCAL')),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    rotated_at       TIMESTAMPTZ,
    rotation_count   INT          NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_key_versions PRIMARY KEY (id)
);

-- Covering index for the most common query: "current active version for tenant+classification"
CREATE UNIQUE INDEX IF NOT EXISTS idx_kv_tenant_class_active
    ON key_versions (tenant_id, classification)
    WHERE active = TRUE;

-- Supporting index for bulk-rotation queries: "all active tenants for a classification"
CREATE INDEX IF NOT EXISTS idx_kv_active_class
    ON key_versions (active, classification)
    WHERE active = TRUE;

-- Supporting index for audit/history queries ordered by recency
CREATE INDEX IF NOT EXISTS idx_kv_rotated_at_desc
    ON key_versions (rotated_at DESC NULLS LAST);

-- Tenant-scoped history queries
CREATE INDEX IF NOT EXISTS idx_kv_tenant_history
    ON key_versions (tenant_id, created_at DESC);

COMMENT ON TABLE  key_versions                  IS 'CMK key version per tenant per classification. One active row per (tenant,classification); all previous rows retained for audit.';
COMMENT ON COLUMN key_versions.key_version      IS 'Cloud-provider key identifier: CMK ARN (AWS) or Key Vault URI with version (Azure).';
COMMENT ON COLUMN key_versions.active           IS 'TRUE = currently encrypting new data. FALSE = superseded; only needed for decrypting historical ciphertext.';
COMMENT ON COLUMN key_versions.rotated_at       IS 'Timestamp when this version was superseded. NULL if still active.';
COMMENT ON COLUMN key_versions.rotation_count   IS 'Cumulative number of rotations for this (tenant, classification) pair at the time this row was created.';
COMMENT ON COLUMN key_versions.version          IS 'Optimistic-locking counter (mapped to @Version in JPA).';
