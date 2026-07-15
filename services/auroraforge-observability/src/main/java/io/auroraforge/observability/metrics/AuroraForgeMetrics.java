package io.auroraforge.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Centralized Micrometer meter definitions for the AuroraForge platform.
 * All custom metrics are defined here to ensure consistent naming and tagging.
 *
 * Metrics exported via Prometheus scrape endpoint and forwarded to Grafana.
 *
 * Naming convention: auroraforge.<domain>.<metric>
 */
@Component
@RequiredArgsConstructor
public class AuroraForgeMetrics {

    private final MeterRegistry meterRegistry;

    // ── Ingestion metrics ──────────────────────────────────────────────────

    private Counter eventsIngested;
    private Counter eventsRejected;
    private Timer   ingestionLatency;
    private DistributionSummary payloadSizeBytes;

    // ── Encryption metrics ─────────────────────────────────────────────────

    private Timer   encryptionLatency;
    private Timer   decryptionLatency;
    private Counter encryptionErrors;

    // ── Sync metrics ───────────────────────────────────────────────────────

    private Counter syncSuccessAws;
    private Counter syncSuccessAzure;
    private Counter syncConflicts;
    private Counter syncErrors;

    @PostConstruct
    void init() {
        eventsIngested = Counter.builder("auroraforge.ingestion.events.ingested")
                .description("Total number of events successfully ingested")
                .tag("version", "v1")
                .register(meterRegistry);

        eventsRejected = Counter.builder("auroraforge.ingestion.events.rejected")
                .description("Total number of events rejected at ingestion")
                .register(meterRegistry);

        ingestionLatency = Timer.builder("auroraforge.ingestion.latency")
                .description("End-to-end ingestion latency (REST → DB + Kafka)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);

        payloadSizeBytes = DistributionSummary.builder("auroraforge.ingestion.payload.bytes")
                .description("Distribution of ingested payload sizes in bytes")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        encryptionLatency = Timer.builder("auroraforge.keymgmt.encrypt.latency")
                .description("Encryption operation latency (KMS / Key Vault)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        decryptionLatency = Timer.builder("auroraforge.keymgmt.decrypt.latency")
                .description("Decryption operation latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        encryptionErrors = Counter.builder("auroraforge.keymgmt.errors")
                .description("Encryption/decryption error count")
                .register(meterRegistry);

        syncSuccessAws = Counter.builder("auroraforge.sync.success")
                .description("Successful cross-cloud sync operations")
                .tag("cloud", "aws")
                .register(meterRegistry);

        syncSuccessAzure = Counter.builder("auroraforge.sync.success")
                .description("Successful cross-cloud sync operations")
                .tag("cloud", "azure")
                .register(meterRegistry);

        syncConflicts = Counter.builder("auroraforge.sync.conflicts")
                .description("Number of write conflicts detected and resolved")
                .register(meterRegistry);

        syncErrors = Counter.builder("auroraforge.sync.errors")
                .description("Unrecoverable sync errors written to DLQ")
                .register(meterRegistry);
    }

    // ── Accessor methods called by services ────────────────────────────────

    public void recordEventIngested(String tenantId, String classification, int payloadBytes) {
        eventsIngested.increment();
        payloadSizeBytes.record(payloadBytes);
    }

    public void recordEventRejected(String reason) {
        eventsRejected.increment();
    }

    public Timer.Sample startIngestionTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopIngestionTimer(Timer.Sample sample) {
        sample.stop(ingestionLatency);
    }

    public void recordEncryptionLatency(long nanos) {
        encryptionLatency.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void recordSyncSuccess(String cloud) {
        if ("aws".equalsIgnoreCase(cloud))   syncSuccessAws.increment();
        if ("azure".equalsIgnoreCase(cloud)) syncSuccessAzure.increment();
    }

    public void recordSyncConflict() { syncConflicts.increment(); }

    public void recordSyncError() { syncErrors.increment(); }
}
