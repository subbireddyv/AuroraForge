package io.auroraforge.sync.application.service;

import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.ConflictStrategy;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.SyncStatus;
import io.auroraforge.sync.domain.model.VectorClock;
import io.auroraforge.sync.infrastructure.aws.AwsDataSyncAdapter;
import io.auroraforge.sync.infrastructure.azure.AzureDataSyncAdapter;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordEntity;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordRepository;
import io.auroraforge.sync.infrastructure.resolver.MultiStrategyConflictResolver;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Application service: orchestrates cross-cloud synchronization.
 *
 * Flow (per record):
 *  1. Consume DataEventCreated from Kafka
 *  2. Attempt to sync to both clouds (active-active)
 *  3. On concurrent write conflict → dispatch to MultiStrategyConflictResolver
 *  4. MANUAL_REVIEW result → persist both versions to DLQ for human sign-off
 *  5. Circuit-open / retry-exhausted → persist record to DLQ for scheduled retry
 *  6. Success → notify ReplicationLagMonitor
 *
 * Resilience stack per cloud (order: Bulkhead → Retry → CircuitBreaker):
 *  - Bulkhead: isolates AWS/Azure thread pools, prevents resource starvation
 *  - Retry: exponential back-off on transient failures
 *  - CircuitBreaker: opens after sustained failures; routes to fallback → DLQ
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossCloudSyncService {

    private static final String AWS_CB   = "aws-sync";
    private static final String AZURE_CB = "azure-sync";

    private final AwsDataSyncAdapter           awsAdapter;
    private final AzureDataSyncAdapter         azureAdapter;
    private final MultiStrategyConflictResolver conflictResolver;
    private final DlqRecordRepository          dlqRepository;
    private final ReplicationLagMonitor        lagMonitor;
    private final DisasterRecoveryProperties   drProps;

    // ── Kafka consumer ─────────────────────────────────────────────────────────

    @KafkaListener(
            topics           = "${auroraforge.kafka.topic-prefix:auroraforge.events}.created",
            groupId          = "auroraforge-sync-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEventCreated(
            byte[] payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String tenantId) {

        log.debug("Sync triggered: tenantId={}", tenantId);

        SyncRecord record = SyncRecord.builder()
                .tenantId(tenantId)
                .payload(payload)
                .vectorClock(VectorClock.empty().increment("kafka"))
                .wallClockTs(Instant.now().toEpochMilli())
                .syncStatus(SyncStatus.PENDING)
                .createdAt(Instant.now())
                .build();

        syncToAws(record);
        syncToAzure(record);
    }

    // ── Sync operations ────────────────────────────────────────────────────────

    @CircuitBreaker(name = AWS_CB, fallbackMethod = "awsSyncFallback")
    @Retry(name = AWS_CB)
    @Bulkhead(name = AWS_CB)
    public void syncToAws(SyncRecord record) {
        Optional<SyncRecord> existing = awsAdapter.findExisting(
                record.getTenantId(), record.getAggregateId());

        if (existing.isPresent() && record.isConcurrentWith(existing.get())) {
            SyncRecord resolved = conflictResolver.resolve(existing.get(), record);

            if (resolved.getSyncStatus() == SyncStatus.CONFLICT_DETECTED) {
                // MANUAL_REVIEW: park both versions in the DLQ; do not write to cloud
                persistConflictToDlq(record, existing.get(), "aws");
            } else {
                awsAdapter.upsert(resolved);
                lagMonitor.recordSync("aws", resolved.getWallClockTs());
                log.info("Conflict resolved (AWS): aggregateId={}", record.getAggregateId());
            }
        } else {
            SyncRecord synced = record.withSyncStatus(SyncStatus.SYNCED);
            awsAdapter.upsert(synced);
            lagMonitor.recordSync("aws", record.getWallClockTs());
        }
    }

    @CircuitBreaker(name = AZURE_CB, fallbackMethod = "azureSyncFallback")
    @Retry(name = AZURE_CB)
    @Bulkhead(name = AZURE_CB)
    public void syncToAzure(SyncRecord record) {
        Optional<SyncRecord> existing = azureAdapter.findExisting(
                record.getTenantId(), record.getAggregateId());

        if (existing.isPresent() && record.isConcurrentWith(existing.get())) {
            SyncRecord resolved = conflictResolver.resolve(existing.get(), record);

            if (resolved.getSyncStatus() == SyncStatus.CONFLICT_DETECTED) {
                persistConflictToDlq(record, existing.get(), "azure");
            } else {
                azureAdapter.upsert(resolved);
                lagMonitor.recordSync("azure", resolved.getWallClockTs());
                log.info("Conflict resolved (Azure): aggregateId={}", record.getAggregateId());
            }
        } else {
            SyncRecord synced = record.withSyncStatus(SyncStatus.SYNCED);
            azureAdapter.upsert(synced);
            lagMonitor.recordSync("azure", record.getWallClockTs());
        }
    }

    // ── Fallbacks (circuit open / all retries exhausted) ──────────────────────

    void awsSyncFallback(SyncRecord record, Throwable t) {
        log.error("AWS circuit open — DLQ write: aggregateId={} error={}",
                record.getAggregateId(), t.getMessage());
        persistFailureToDlq(record, "aws", t);
    }

    void azureSyncFallback(SyncRecord record, Throwable t) {
        log.error("Azure circuit open — DLQ write: aggregateId={} error={}",
                record.getAggregateId(), t.getMessage());
        persistFailureToDlq(record, "azure", t);
    }

    // ── DLQ persistence ───────────────────────────────────────────────────────

    private void persistFailureToDlq(SyncRecord record, String targetCloud, Throwable cause) {
        Instant firstRetry = Instant.now().plusSeconds(drProps.dlq().retryBackoffSeconds());
        try {
            dlqRepository.save(
                    DlqRecordEntity.fromSyncFailure(record, targetCloud,
                            cause.getMessage(), firstRetry));
        } catch (Exception e) {
            // DLQ write must not propagate — losing the DLQ entry is safer than crashing the consumer
            log.error("CRITICAL: DLQ write failed for aggregateId={} — record may be lost",
                    record.getAggregateId(), e);
        }
    }

    private void persistConflictToDlq(SyncRecord local, SyncRecord remote, String targetCloud) {
        try {
            dlqRepository.save(
                    DlqRecordEntity.fromConflict(local, remote, ConflictStrategy.MANUAL_REVIEW));
            log.warn("Conflict parked for manual review: aggregateId={} tenantId={}",
                    local.getAggregateId(), local.getTenantId());
        } catch (Exception e) {
            log.error("CRITICAL: DLQ conflict write failed for aggregateId={}",
                    local.getAggregateId(), e);
        }
    }
}
