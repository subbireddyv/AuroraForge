package io.auroraforge.observability.audit;

/**
 * Canonical set of audit event types emitted across AuroraForge services.
 *
 * Each value maps to a {@code eventType} field in the AUDIT log,
 * enabling SIEM/Elasticsearch queries and compliance-report generation.
 *
 * Categories:
 *   AUTH_*       — identity and session events (SOC 2 CC6.1, PCI DSS 10.2)
 *   KEY_*        — cryptographic key lifecycle (FIPS 140-2, PCI DSS 3.5)
 *   DATA_*       — access/classification events (GDPR Art. 30, HIPAA §164.312)
 *   DR_*         — disaster recovery operations (SOC 2 CC7.5)
 *   ADMIN_*      — privileged actions (CIS Control 5)
 */
public enum AuditEventType {

    // ── Authentication & Session ─────────────────────────────────────────────
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    TOKEN_REFRESH,
    ACCOUNT_LOCKED,          // after N failed attempts
    ACCOUNT_UNLOCKED,

    // ── Key Management ───────────────────────────────────────────────────────
    KEY_ROTATION_INITIATED,
    KEY_ROTATION_COMPLETED,
    KEY_ROTATION_FAILED,
    KEY_ACCESSED,            // reads of key material (rare, high-risk)

    // ── Data Access ──────────────────────────────────────────────────────────
    RESTRICTED_DATA_ACCESS,  // queries on PII / PHI / PCI fields
    BULK_EXPORT,             // large data export triggered
    DATA_PURGE,              // GDPR right-to-erasure execution

    // ── Disaster Recovery ────────────────────────────────────────────────────
    DR_FAILOVER_INITIATED,
    DR_FAILOVER_COMPLETED,
    DR_RECOVERY_COMPLETED,
    DR_CONSISTENCY_CHECK,

    // ── Administrative ───────────────────────────────────────────────────────
    ADMIN_ACTION,            // catch-all for privileged mutations
    TENANT_CREATED,
    TENANT_SUSPENDED,
    ROLE_ASSIGNED,
    ROLE_REVOKED,
    CONFIG_CHANGED,

    // ── Security ─────────────────────────────────────────────────────────────
    AUTHORIZATION_FAILURE,   // 403 on a protected resource
    RATE_LIMIT_EXCEEDED,
    SUSPICIOUS_ACTIVITY      // anomaly detected by application logic
}
