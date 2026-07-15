package io.auroraforge.sync.infrastructure.scheduler;

import io.auroraforge.sync.application.service.CrossCloudSyncService;
import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordEntity;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled component: retries PENDING_RETRY records from the Dead Letter Queue.
 *
 * Retry strategy:
 *  - Runs every {@code auroraforge.dr.dlq.retry-interval-seconds} seconds (default 60s)
 *  - Exponential back-off: nextRetry = lastAttempt + retryBackoffSeconds * 2^retryCount (cap 1h)
 *  - After {@code maxRetryAttempts} failures the record is moved to EXHAUSTED
 *    and a Micrometer counter is incremented for alerting
 *
 * Retry calls go through the Spring AOP proxy for CrossCloudSyncService so the
 * Resilience4j circuit breaker, retry, and bulkhead annotations remain active.
 */
@Slf4j
@Component
public class DlqRetryScheduler {

    private static final long MAX_BACKOFF_SECONDS = 3_600L; // 1 hour cap

    private final DlqRecordRepository      dlqRepository;
    private final CrossCloudSyncService    syncService;
    private final DisasterRecoveryProperties drProps;
    private final Counter                  successCounter;
    private final Counter                  exhaustedCounter;

    public DlqRetryScheduler(DlqRecordRepository dlqRepository,
                              CrossCloudSyncService syncService,
                              DisasterRecoveryProperties drProps,
                              MeterRegistry registry) {
        this.dlqRepository    = dlqRepository;
        this.syncService      = syncService;
        this.drProps          = drProps;
        this.successCounter   = registry.counter("auroraforge.dlq.retry", "result", "success");
        this.exhaustedCounter = registry.counter("auroraforge.dlq.retry", "result", "exhausted");

        // Gauge: total EXHAUSTED records across all tenants (SLO breach signal)
        Gauge.builder("auroraforge.dlq.exhausted_total",
                      dlqRepository,
                      DlqRecordRepository::countExhausted)
             .description("Total DLQ records that exhausted all retry attempts")
             .register(registry);
    }

    @Scheduled(fixedDelayString = "${auroraforge.dr.dlq.retry-interval-seconds:60}000")
    @Transactional
    public void retryPending() {
        List<DlqRecordEntity> due = dlqRepository
                .findByStatusAndNextRetryAtBefore(DlqRecordEntity.DlqStatus.PENDING_RETRY, Instant.now());

        if (due.isEmpty()) return;
        log.info("DLQ retry run: {} records eligible", due.size());

        for (DlqRecordEntity entity : due) {
            attemptRetry(entity);
        }
    }

    private void attemptRetry(DlqRecordEntity entity) {
        entity.setStatus(DlqRecordEntity.DlqStatus.IN_RETRY);
        entity.setLastAttemptedAt(Instant.now());

        try {
            SyncRecord record = entity.toSyncRecord();

            if ("aws".equalsIgnoreCase(entity.getTargetCloud())) {
                syncService.syncToAws(record);
            } else {
                syncService.syncToAzure(record);
            }

            entity.setStatus(DlqRecordEntity.DlqStatus.RESOLVED);
            successCounter.increment();
            log.info("DLQ retry succeeded: id={} aggregateId={} targetCloud={}",
                    entity.getId(), entity.getAggregateId(), entity.getTargetCloud());

        } catch (Exception e) {
            int attempts = entity.getRetryCount() + 1;
            entity.setRetryCount(attempts);
            entity.setErrorMessage(truncate(e.getMessage(), 1024));

            if (attempts >= drProps.dlq().maxRetryAttempts()) {
                entity.setStatus(DlqRecordEntity.DlqStatus.EXHAUSTED);
                exhaustedCounter.increment();
                log.error("DLQ EXHAUSTED after {} attempts: id={} aggregateId={}",
                        attempts, entity.getId(), entity.getAggregateId());
            } else {
                // Exponential back-off capped at MAX_BACKOFF_SECONDS
                long backoffSeconds = Math.min(
                        (long) drProps.dlq().retryBackoffSeconds() * (1L << attempts),
                        MAX_BACKOFF_SECONDS);
                entity.setStatus(DlqRecordEntity.DlqStatus.PENDING_RETRY);
                entity.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                log.warn("DLQ retry failed (attempt {}/{}): id={} nextRetryIn={}s error={}",
                        attempts, drProps.dlq().maxRetryAttempts(),
                        entity.getId(), backoffSeconds, e.getMessage());
            }
        }

        dlqRepository.save(entity);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
