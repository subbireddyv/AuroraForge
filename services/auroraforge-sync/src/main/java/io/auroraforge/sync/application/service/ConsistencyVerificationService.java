package io.auroraforge.sync.application.service;

import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.ConsistencyReport;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.infrastructure.aws.AwsDataSyncAdapter;
import io.auroraforge.sync.infrastructure.azure.AzureDataSyncAdapter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Verifies cross-cloud data consistency by sampling records and comparing
 * SHA-256 checksums of their payloads across AWS and Azure.
 *
 * Called by:
 *  - {@link DisasterRecoveryCoordinator} on a scheduled consistency-check run
 *  - {@link io.auroraforge.sync.presentation.DisasterRecoveryController} on-demand via the REST API
 */
@Slf4j
@Service
public class ConsistencyVerificationService {

    private final AwsDataSyncAdapter   awsAdapter;
    private final AzureDataSyncAdapter azureAdapter;
    private final DisasterRecoveryProperties drProps;
    private final Counter divergenceCounter;
    private final Counter gapCounter;

    public ConsistencyVerificationService(AwsDataSyncAdapter awsAdapter,
                                           AzureDataSyncAdapter azureAdapter,
                                           DisasterRecoveryProperties drProps,
                                           MeterRegistry registry) {
        this.awsAdapter        = awsAdapter;
        this.azureAdapter      = azureAdapter;
        this.drProps           = drProps;
        this.divergenceCounter = registry.counter("auroraforge.consistency.diverged", "reason", "payload_mismatch");
        this.gapCounter        = registry.counter("auroraforge.consistency.diverged", "reason", "missing_in_cloud");
    }

    /**
     * Verifies consistency for a tenant by comparing a sample of the provided aggregate IDs
     * across AWS and Azure.
     *
     * @param tenantId     tenant to verify
     * @param aggregateIds candidate aggregate IDs to sample (may be large; will be capped)
     * @return a {@link ConsistencyReport} with per-record verdict and aggregate consistency %
     */
    public ConsistencyReport verify(String tenantId, List<String> aggregateIds) {
        int sampleSize  = Math.min(aggregateIds.size(), drProps.consistencyCheckSampleSize());
        List<String> sample = aggregateIds.subList(0, sampleSize);

        int          consistent = 0;
        List<String> diverged   = new ArrayList<>();

        for (String aggregateId : sample) {
            try {
                Optional<SyncRecord> aws   = awsAdapter.findExisting(tenantId, aggregateId);
                Optional<SyncRecord> azure = azureAdapter.findExisting(tenantId, aggregateId);

                if (aws.isEmpty() && azure.isEmpty()) {
                    // Record absent in both clouds — treated as consistent (never replicated)
                    consistent++;
                    continue;
                }

                if (aws.isEmpty() || azure.isEmpty()) {
                    log.warn("Replication gap: tenantId={} aggregateId={} inAws={} inAzure={}",
                            tenantId, aggregateId, aws.isPresent(), azure.isPresent());
                    diverged.add(aggregateId);
                    gapCounter.increment();
                    continue;
                }

                byte[] awsHash   = sha256(aws.get().getPayload());
                byte[] azureHash = sha256(azure.get().getPayload());

                if (Arrays.equals(awsHash, azureHash)) {
                    consistent++;
                } else {
                    log.warn("Payload divergence: tenantId={} aggregateId={}", tenantId, aggregateId);
                    diverged.add(aggregateId);
                    divergenceCounter.increment();
                }

            } catch (Exception e) {
                log.error("Consistency check error: tenantId={} aggregateId={} error={}",
                        tenantId, aggregateId, e.getMessage());
                diverged.add(aggregateId);
                divergenceCounter.increment();
            }
        }

        double pct = sampleSize > 0 ? (consistent * 100.0 / sampleSize) : 100.0;

        ConsistencyReport report = new ConsistencyReport(
                tenantId, sampleSize, consistent, diverged.size(),
                List.copyOf(diverged), pct, java.time.Instant.now());

        if (report.isFullyConsistent()) {
            log.info("Consistency check PASSED: tenantId={} sampled={}", tenantId, sampleSize);
        } else {
            log.warn("Consistency check FAILED: tenantId={} consistent={}% diverged={}",
                    tenantId, String.format("%.1f", pct), diverged.size());
        }

        return report;
    }

    private byte[] sha256(byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
