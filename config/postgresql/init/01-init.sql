-- ============================================================================
-- PostgreSQL init script — runs once when the container is first created
-- Executed by the postgres image's /docker-entrypoint-initdb.d/ mechanism
-- ============================================================================

-- Create the application database and user if not already present
-- (The POSTGRES_DB / POSTGRES_USER env vars create the default DB/user;
-- this script adds extra roles and extensions needed by the application.)

-- ── Application role (limited privileges) ──────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'auroraforge') THEN
        CREATE ROLE auroraforge LOGIN PASSWORD 'auroraforge_dev';
    END IF;
END $$;

-- Grant connect and schema usage
GRANT CONNECT ON DATABASE auroraforge TO auroraforge;
GRANT USAGE ON SCHEMA public TO auroraforge;
GRANT CREATE ON SCHEMA public TO auroraforge;

-- Grant full table/sequence access for the application role
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO auroraforge;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO auroraforge;

-- ── Extensions ──────────────────────────────────────────────────────────────
-- pgcrypto: used for UUID generation in tests and optional column encryption
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- pg_stat_statements: exposes slow query statistics to Prometheus pg_exporter
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ── Logical replication settings (mirrors RDS parameter group) ──────────────
-- These are already set via docker-compose command args; listed here for docs.
-- wal_level=logical, max_replication_slots=10, max_wal_senders=10

-- ── Read-replica role (for Debezium replication connection) ─────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'debezium') THEN
        CREATE ROLE debezium REPLICATION LOGIN PASSWORD 'debezium_dev';
    END IF;
END $$;

-- Debezium requires SELECT on the monitored tables
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO debezium;
