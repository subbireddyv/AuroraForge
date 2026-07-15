package io.auroraforge.core.config.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Data retention policy configuration by classification tier.
 *
 * Bound from: auroraforge.retention.*
 *
 * Retention periods drive:
 *  - S3/Blob lifecycle rules applied by Terraform (in days)
 *  - DB soft-delete and physical purge scheduler (in days)
 *  - Kafka topic retention override per classification
 *
 * Classification keys must match {@code DataClassification} enum values:
 *  PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.retention")
public record DataRetentionProperties(

        Map<String, ClassificationRetention> policies,

        boolean enableAutoPurge,

        int purgeScheduleCronSeconds,

        int purgeMaxBatchSize

) {
    public DataRetentionProperties {
        if (policies == null || policies.isEmpty()) {
            policies = Map.of(
                "PUBLIC",       new ClassificationRetention(365,  -1,    false),
                "INTERNAL",     new ClassificationRetention(730,  365,   false),
                "CONFIDENTIAL", new ClassificationRetention(1825, 730,   true),
                "RESTRICTED",   new ClassificationRetention(2555, 1825,  true)
            );
        }
        if (purgeScheduleCronSeconds == 0) purgeScheduleCronSeconds = 3600;
        if (purgeMaxBatchSize == 0) purgeMaxBatchSize = 500;
    }

    /**
     * @param objectStoreDays    Days before cold object storage transition
     * @param dbRetentionDays    Days before soft-deleted rows are physically purged
     * @param requiresAuditLog   Whether deletion must be logged to the immutable audit trail
     */
    public record ClassificationRetention(
            int objectStoreDays,
            int dbRetentionDays,
            boolean requiresAuditLog
    ) {}
}
