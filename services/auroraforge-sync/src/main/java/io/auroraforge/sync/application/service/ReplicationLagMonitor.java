package io.auroraforge.sync.application.service;

import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks replication lag (seconds) per cloud provider and publishes Micrometer gauges.
 *
 * Lag is defined as: {@code now() - wallClockTs} of the most recently observed sync record.
 * An RPO alert is logged at ERROR level when lag exceeds {@code auroraforge.dr.rpo-threshold-seconds}.
 *
 * Gauges:
 *   auroraforge.replication.lag_seconds{cloud="aws"}
 *   auroraforge.replication.lag_seconds{cloud="azure"}
 */
@Slf4j
@Service
public class ReplicationLagMonitor {

    private final DisasterRecoveryProperties drProps;

    /** Wall-clock timestamp (ms) of the most recent sync per cloud. */
    private final Map<String, Long> lastSyncTs = new ConcurrentHashMap<>();

    /** Cached lag seconds per cloud; read by Micrometer gauges at scrape time. */
    private final Map<String, Double> lagSeconds = new ConcurrentHashMap<>();

    public ReplicationLagMonitor(DisasterRecoveryProperties drProps, MeterRegistry registry) {
        this.drProps = drProps;
        registerGauge(registry, "aws");
        registerGauge(registry, "azure");
    }

    private void registerGauge(MeterRegistry registry, String cloud) {
        Gauge.builder("auroraforge.replication.lag_seconds",
                      lagSeconds,
                      m -> m.getOrDefault(cloud, 0.0))
             .tag("cloud", cloud)
             .description("Replication lag in seconds for " + cloud.toUpperCase())
             .register(registry);
    }

    /**
     * Records a successful sync event for the given cloud provider.
     *
     * @param cloudProvider e.g. "aws", "azure"
     * @param wallClockTs   wall-clock timestamp (ms) of the record just synced
     */
    public void recordSync(String cloudProvider, long wallClockTs) {
        lastSyncTs.put(cloudProvider, wallClockTs);
        double lag = computeLag(wallClockTs);
        lagSeconds.put(cloudProvider, lag);

        if (lag > drProps.rpoThresholdSeconds()) {
            log.error("RPO BREACH: cloud={} lagSeconds={} rpoThresholdSeconds={}",
                    cloudProvider,
                    String.format("%.1f", lag),
                    drProps.rpoThresholdSeconds());
        } else {
            log.debug("Sync lag recorded: cloud={} lagSeconds={}", cloudProvider,
                    String.format("%.1f", lag));
        }
    }

    /**
     * Returns the current replication lag in seconds for a cloud provider.
     * Returns -1 if no sync has been observed yet.
     */
    public double getLagSeconds(String cloudProvider) {
        Long ts = lastSyncTs.get(cloudProvider);
        return ts != null ? computeLag(ts) : -1.0;
    }

    /** Snapshot of last observed wall-clock timestamps per cloud (for dashboards). */
    public Map<String, Long> getLastSyncTimestamps() {
        return Map.copyOf(lastSyncTs);
    }

    private double computeLag(long wallClockTs) {
        long nowMs = Instant.now().toEpochMilli();
        return Math.max(0.0, (nowMs - wallClockTs) / 1000.0);
    }
}
