package io.auroraforge.sync.domain.model;

import java.time.Instant;

/**
 * Point-in-time health snapshot for a single cloud provider.
 * Produced by implementations of {@link io.auroraforge.sync.domain.port.CloudHealthPort}.
 */
public record CloudHealth(
        String  cloudProvider,
        boolean reachable,
        long    latencyMs,
        double  errorRatePercent,
        long    pendingSyncCount,
        Instant checkedAt,
        String  statusDetail
) {

    /** Nominal health — reachable with the observed latency. */
    public static CloudHealth healthy(String cloud, long latencyMs) {
        return new CloudHealth(cloud, true, latencyMs, 0.0, 0L, Instant.now(), "OK");
    }

    /** Cloud is unreachable; detail carries the exception message. */
    public static CloudHealth unreachable(String cloud, String detail) {
        return new CloudHealth(cloud, false, -1L, 100.0, -1L, Instant.now(), detail);
    }

    /**
     * Degraded when unreachable, error rate exceeds 5%, or P99 latency exceeds 5 seconds.
     * These thresholds drive the HEALTHY → DEGRADED state machine transition in
     * {@link io.auroraforge.sync.application.service.DisasterRecoveryCoordinator}.
     */
    public boolean isDegraded() {
        return !reachable || errorRatePercent > 5.0 || latencyMs > 5_000;
    }
}
