package io.auroraforge.sync.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Disaster recovery and cross-cloud consistency configuration.
 *
 * Example:
 * <pre>
 * auroraforge:
 *   dr:
 *     primary-cloud: aws
 *     health-check-interval-seconds: 30
 *     degraded-threshold-seconds: 60
 *     rpo-threshold-seconds: 300
 *     consistency-check-sample-size: 100
 *     dlq:
 *       max-retry-attempts: 5
 *       retry-backoff-seconds: 30
 *     strategy-by-aggregate-type:
 *       TENANT_CONFIG: CLOUD_PRIORITY
 *       AUDIT_RECORD:  LAST_WRITE_WINS
 *       RESTRICTED_DATA: MANUAL_REVIEW
 * </pre>
 */
@ConfigurationProperties(prefix = "auroraforge.dr")
@Validated
public record DisasterRecoveryProperties(
        @NotBlank  String primaryCloud,
        @Positive  int    healthCheckIntervalSeconds,
        @Positive  int    degradedThresholdSeconds,
        @Positive  int    rpoThresholdSeconds,
        @Positive  int    consistencyCheckSampleSize,
        @Valid     DlqConfig dlq,
        Map<String, String> strategyByAggregateType
) {

    public record DlqConfig(
            @Positive int maxRetryAttempts,
            @Positive int retryBackoffSeconds
    ) {}

    /**
     * Resolves the conflict strategy for a given aggregate type.
     * Falls back to {@code HIGHEST_VECTOR_CLOCK} if not explicitly configured.
     */
    public io.auroraforge.sync.domain.model.ConflictStrategy strategyFor(String aggregateType) {
        if (strategyByAggregateType == null || aggregateType == null) {
            return io.auroraforge.sync.domain.model.ConflictStrategy.HIGHEST_VECTOR_CLOCK;
        }
        String raw = strategyByAggregateType.getOrDefault(
                aggregateType.toUpperCase(),
                "HIGHEST_VECTOR_CLOCK");
        try {
            return io.auroraforge.sync.domain.model.ConflictStrategy.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return io.auroraforge.sync.domain.model.ConflictStrategy.HIGHEST_VECTOR_CLOCK;
        }
    }
}
