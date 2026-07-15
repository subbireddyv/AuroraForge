package io.auroraforge.core.domain.model;

/**
 * Data classification label attached to every {@link DataEvent}.
 * Controls which KMS key is used for encryption and which retention policy applies.
 */
public enum DataClassification {

    /** Freely shareable data – no encryption requirement. */
    PUBLIC,

    /** Internal-only data – encrypted but not subject to strict compliance. */
    INTERNAL,

    /** Sensitive business data – encrypted with CMK, 90-day key rotation. */
    CONFIDENTIAL,

    /** PII / regulated data – encrypted with CMK, 30-day key rotation, audit log mandatory. */
    RESTRICTED;

    public boolean requiresCmkEncryption() {
        return this == CONFIDENTIAL || this == RESTRICTED;
    }

    public boolean requiresAuditLog() {
        return this == RESTRICTED;
    }

    public int keyRotationDays() {
        return switch (this) {
            case PUBLIC, INTERNAL -> 365;
            case CONFIDENTIAL     -> 90;
            case RESTRICTED       -> 30;
        };
    }
}
