package io.auroraforge.ingestion.application.saga.step;

import io.auroraforge.core.application.port.out.EventStoragePort;
import io.auroraforge.core.domain.model.DataEvent;
import io.auroraforge.core.domain.model.EventStatus;
import io.auroraforge.ingestion.application.saga.IngestionSagaContext;
import io.auroraforge.ingestion.application.saga.SagaStep;
import io.auroraforge.ingestion.application.saga.SagaStepException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Step 3 — Persist the event record to PostgreSQL.
 *
 * The event is written in PROCESSING status with a back-reference to the object
 * storage key. The Transactional Outbox row is written in the same transaction
 * (see OutboxEvent inside PostgresEventStorageAdapter) so the Debezium CDC
 * connector picks it up and publishes it to Kafka — avoiding the dual-write
 * problem that exists in the pre-refactor ingestion path.
 *
 * Compensation: mark the record as SAGA_COMPENSATED (soft delete preserving
 * audit trail). Hard deletes on compensated sagas are performed by a nightly
 * retention job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersistEventStep implements SagaStep {

    private final EventStoragePort eventStoragePort;

    @Override
    public String name() { return "PERSIST_EVENT"; }

    @Override
    public IngestionSagaContext execute(IngestionSagaContext ctx) throws SagaStepException {
        log.debug("saga={} step={} aggregateId={}", ctx.getSagaId(), name(), ctx.getAggregateId());
        try {
            DataEvent event = DataEvent.builder()
                    .sagaId(ctx.getSagaId())
                    .requestId(ctx.getRequestId())
                    .tenantId(ctx.getTenantId())
                    .aggregateId(ctx.getAggregateId())
                    .eventType(ctx.getEventType())
                    .encryptedPayload(ctx.getEncryptedPayload())
                    .encryptionKeyId(ctx.getEncryptionKeyId())
                    .storageObjectKey(ctx.getStorageObjectKey())
                    .classification(ctx.getClassification())
                    .status(EventStatus.PROCESSING)
                    .occurredAt(Instant.now())
                    .build();

            Long id = eventStoragePort.save(event);
            log.info("saga={} step={} status=ok eventId={}", ctx.getSagaId(), name(), id);
            return ctx.withPersistedEventId(id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Idempotency: a prior attempt already persisted this sagaId — treat as success.
            Long existingId = eventStoragePort.findIdBySagaId(ctx.getSagaId());
            log.warn("saga={} step={} status=idempotent-hit existingId={}", ctx.getSagaId(), name(), existingId);
            return ctx.withPersistedEventId(existingId);
        } catch (Exception e) {
            throw new SagaStepException(name(), "DB persist failed", e, isRetryable(e));
        }
    }

    @Override
    public void compensate(IngestionSagaContext ctx) {
        if (ctx.getPersistedEventId() == null) return;
        try {
            eventStoragePort.markCompensated(ctx.getPersistedEventId(), ctx.getSagaId());
            log.info("saga={} step={} compensation=ok eventId={}",
                    ctx.getSagaId(), name(), ctx.getPersistedEventId());
        } catch (Exception e) {
            log.error("saga={} step={} compensation=failed eventId={} error={}",
                    ctx.getSagaId(), name(), ctx.getPersistedEventId(), e.getMessage(), e);
        }
    }

    private boolean isRetryable(Exception e) {
        return !(e instanceof org.springframework.dao.DataIntegrityViolationException);
    }
}
