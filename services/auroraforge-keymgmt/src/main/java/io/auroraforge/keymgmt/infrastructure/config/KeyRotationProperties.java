package io.auroraforge.keymgmt.infrastructure.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for automated data-key (DEK) rotation and per-classification cache TTLs.
 *
 * CRON schedules (Spring syntax: seconds minutes hours day-of-month month day-of-week):
 *   CONFIDENTIAL/RESTRICTED rotate monthly (1st of each month at 03:00/04:00 UTC)
 *   PUBLIC/INTERNAL rotate weekly (every Sunday at 02:00 UTC)
 *
 * DEK TTLs control how long a cached plaintext data-encryption-key lives in heap memory
 * before the next call forces a new GenerateDataKey / Key Vault wrap operation.
 * Shorter TTL = higher KMS API cost but smaller blast radius if memory is compromised.
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.keymgmt.rotation")
public record KeyRotationProperties(

        // Cron expressions per classification (Spring cron, 6 fields)
        String publicKeysCron,
        String internalKeysCron,
        String confidentialKeysCron,
        String restrictedKeysCron,

        // Per-classification in-memory DEK TTLs (seconds)
        @Positive int publicDataKeyTtlSeconds,
        @Positive int internalDataKeyTtlSeconds,
        @Positive int confidentialDataKeyTtlSeconds,
        @Positive int restrictedDataKeyTtlSeconds,

        // Threshold (days) after which a tenant's tracked key version is considered stale
        @Positive int dataKeyRotationDays

) {
    public KeyRotationProperties {
        if (publicKeysCron        == null) publicKeysCron        = "0 0 2 ? * SUN";
        if (internalKeysCron      == null) internalKeysCron      = "0 0 2 ? * SUN";
        if (confidentialKeysCron  == null) confidentialKeysCron  = "0 0 3 1 * ?";
        if (restrictedKeysCron    == null) restrictedKeysCron    = "0 0 4 1 * ?";
        if (publicDataKeyTtlSeconds       <= 0) publicDataKeyTtlSeconds       = 300;
        if (internalDataKeyTtlSeconds     <= 0) internalDataKeyTtlSeconds     = 300;
        if (confidentialDataKeyTtlSeconds <= 0) confidentialDataKeyTtlSeconds = 60;
        if (restrictedDataKeyTtlSeconds   <= 0) restrictedDataKeyTtlSeconds   = 60;
        if (dataKeyRotationDays           <= 0) dataKeyRotationDays           = 90;
    }
}
