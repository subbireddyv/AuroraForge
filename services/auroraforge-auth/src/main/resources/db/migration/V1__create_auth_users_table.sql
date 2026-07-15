-- ─────────────────────────────────────────────────────────────────────────────
-- AuroraForge Auth Service – Flyway V1
-- Creates the auth_users schema (users, roles, and data-classification grants).
--
-- Notes:
--   • flyway.table = flyway_schema_history_auth  (see application.yml)
--     → avoids collision with the ingestion module's flyway_schema_history table
--     when both services point to the same PostgreSQL instance during local dev.
--   • Passwords stored as bcrypt ($2a$) hashes — minimum cost 12.
--   • tenant_id + username uniqueness enforced at DB level (unique index).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS auth_users (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    username            VARCHAR(128)  NOT NULL,
    password_hash       VARCHAR(72)   NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    service_account     BOOLEAN       NOT NULL DEFAULT FALSE,
    failed_login_count  INT           NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_auth_users PRIMARY KEY (id),
    CONSTRAINT uq_auth_users_username     UNIQUE (username),
    CONSTRAINT uq_auth_users_tenant_user  UNIQUE (tenant_id, username)
);

CREATE INDEX IF NOT EXISTS idx_au_tenant   ON auth_users (tenant_id);
CREATE INDEX IF NOT EXISTS idx_au_enabled  ON auth_users (enabled) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_au_locked   ON auth_users (locked_until) WHERE locked_until IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- Per-user role assignments (ElementCollection → join table)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS auth_user_roles (
    user_id  UUID         NOT NULL
        REFERENCES auth_users(id) ON DELETE CASCADE,
    role     VARCHAR(32)  NOT NULL
        CHECK (role IN ('ADMIN','DATA_INGEST','DATA_QUERY','KEY_MANAGER','PLATFORM_OPS','SERVICE_ACCOUNT')),

    CONSTRAINT pk_auth_user_roles PRIMARY KEY (user_id, role)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Per-user data-classification grants (may be a strict subset of the role default)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS auth_user_classifications (
    user_id        UUID         NOT NULL
        REFERENCES auth_users(id) ON DELETE CASCADE,
    classification VARCHAR(32)  NOT NULL
        CHECK (classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),

    CONSTRAINT pk_auth_user_classifications PRIMARY KEY (user_id, classification)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: platform-level admin user (password = changeme — MUST be rotated)
-- BCrypt cost-12 hash of "changeme": $2a$12$...
-- Generated with: BCrypt.hashpw("changeme", BCrypt.gensalt(12))
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO auth_users (username, password_hash, tenant_id, enabled, service_account)
    VALUES ('platform-admin',
            '$2a$12$Kl9zBxFk4XVQ7kO5bKkO/uqxM4A5z3RZT4V.Y7Kk9nFvQpz2J9hIO',
            'platform',
            TRUE,
            FALSE)
    ON CONFLICT (username) DO NOTHING;

-- Grant ADMIN role to the seed user
INSERT INTO auth_user_roles (user_id, role)
    SELECT id, 'ADMIN'
      FROM auth_users
     WHERE username = 'platform-admin'
    ON CONFLICT DO NOTHING;

-- Grant all data classifications to the seed admin
INSERT INTO auth_user_classifications (user_id, classification)
    SELECT u.id, c.classification
      FROM auth_users u
     CROSS JOIN (VALUES ('PUBLIC'), ('INTERNAL'), ('CONFIDENTIAL'), ('RESTRICTED')) AS c(classification)
     WHERE u.username = 'platform-admin'
    ON CONFLICT DO NOTHING;
