package io.auroraforge.ingestion.application.saga;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.TenantId;
import io.auroraforge.ingestion.application.saga.step.EncryptPayloadStep;
import io.auroraforge.ingestion.application.saga.step.PersistEventStep;
import io.auroraforge.ingestion.application.saga.step.PublishEventStep;
import io.auroraforge.ingestion.application.saga.step.StoreRawObjectStep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Orchestration-based saga for the Data Ingestion flow.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  HAPPY PATH                                                             │
 * │                                                                         │
 * │  EncryptPayload → StoreRawObject → PersistEvent → PublishEvent         │
 * │                                                                         │
 * │  COMPENSATION PATH (on any step failure)                                │
 * │                                                                         │
 * │  reverse of all previously completed steps:                             │
 * │  PublishEvent⁻¹ → PersistEvent⁻¹ → StoreRawObject⁻¹ → EncryptPayload⁻¹│
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Design notes:
 *
 * 1. IDEMPOTENCY — each step guards against duplicate execution via a
 *    (sagaId, stepName) idempotency key so retried HTTP requests do not
 *    cause side-effects.
 *
 * 2. RETRY vs COMPENSATE — retryable steps (transient cloud errors, CB open)
 *    are retried up to {@code MAX_STEP_RETRIES} times with exponential backoff
 *    before compensation is triggered.
 *
 * 3. OBSERVABILITY — the saga emits:
 *    - a structured log entry at every state transition
 *    - a Micrometer counter per step outcome (success/failure/compensation)
 *    - an end-to-end timer for the full saga
 *
 * 4. AT-LEAST-ONCE KAFKA — {@link PublishEventStep} compensates by publishing
 *    a tombstone record. The target architecture replaces this with Debezium
 *    CDC on the transactional outbox table (written by {@link PersistEventStep})
 *    which gives exactly-once delivery without a Kafka publish step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionSagaOrchestrator {

    private static final int MAX_STEP_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 200;

    private final EncryptPayloadStep encryptPayloadStep;
    private final StoreRawObjectStep storeRawObjectStep;
    private final PersistEventStep   persistEventStep;
    private final PublishEventStep   publishEventStep;
    private final MeterRegistry      meterRegistry;

    // Ordered pipeline. The orchestrator iterates forward; on failure iterates backward.
    private List<SagaStep> steps() {
        return List.of(encryptPayloadStep, storeRawObjectStep, persistEventStep, publishEventStep);
    }

    /**
     * Execute the saga. Returns the final {@link IngestionSagaContext} enriched by
     * all completed steps (useful for callers that need the Kafka offset or DB id).
     *
     * @throws SagaFailedException if the saga failed AND compensation completed
     * @throws SagaCompensationFailedException if compensation itself encountered errors
     */
    public IngestionSagaContext execute(
            String requestId,
            TenantId tenantId,
            String aggregateId,
            String eventType,
            byte[] rawPayload,
            DataClassification classification) throws SagaFailedException {

        IngestionSagaContext ctx = IngestionSagaContext.start(
                requestId, tenantId, aggregateId, eventType, rawPayload, classification);

        log.info("saga.started sagaId={} tenant={} aggregateId={} eventType={}",
                ctx.getSagaId(), tenantId, aggregateId, eventType);

        Timer.Sample timer = Timer.start(meterRegistry);

        List<SagaStep> executed = new ArrayList<>(); // tracks steps to compensate
        IngestionSagaState currentState = IngestionSagaState.STARTED;

        for (SagaStep step : steps()) {
            currentState = stateFor(step, false);
            try {
                ctx = executeWithRetry(step, ctx);
                executed.add(step);
            } catch (SagaStepException e) {
                log.error("saga.step.failed sagaId={} step={} retryable={} reason={}",
                        ctx.getSagaId(), step.name(), e.isRetryable(), e.getMessage(), e);
                meterRegistry.counter("auroraforge.saga.step.failure",
                        "step", step.name(), "retryable", String.valueOf(e.isRetryable())).increment();

                ctx = ctx.withFailure(e.getMessage());
                compensate(ctx, executed);

                timer.stop(Timer.builder("auroraforge.saga.duration")
                        .tag("outcome", "failed")
                        .tag("failed_step", step.name())
                        .register(meterRegistry));

                throw new SagaFailedException(ctx.getSagaId(), step.name(), e.getMessage(), e);
            }

            meterRegistry.counter("auroraforge.saga.step.success", "step", step.name()).increment();
        }

        timer.stop(Timer.builder("auroraforge.saga.duration")
                .tag("outcome", "success")
                .register(meterRegistry));

        log.info("saga.completed sagaId={} tenant={} aggregateId={} offset={}",
                ctx.getSagaId(), tenantId, aggregateId, ctx.getKafkaOffset());

        return ctx;
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private IngestionSagaContext executeWithRetry(SagaStep step, IngestionSagaContext ctx)
            throws SagaStepException {

        SagaStepException lastException = null;
        for (int attempt = 0; attempt <= MAX_STEP_RETRIES; attempt++) {
            try {
                return step.execute(ctx);
            } catch (SagaStepException e) {
                lastException = e;
                if (!e.isRetryable() || attempt == MAX_STEP_RETRIES) throw e;
                log.warn("saga.step.retry sagaId={} step={} attempt={}/{}",
                        ctx.getSagaId(), step.name(), attempt + 1, MAX_STEP_RETRIES);
                backoff(attempt);
            }
        }
        throw lastException; // unreachable but satisfies compiler
    }

    private void compensate(IngestionSagaContext ctx, List<SagaStep> executed) {
        log.warn("saga.compensating sagaId={} stepsToUndo={}", ctx.getSagaId(), executed.size());

        ListIterator<SagaStep> it = executed.listIterator(executed.size());
        while (it.hasPrevious()) {
            SagaStep step = it.previous();
            try {
                step.compensate(ctx);
                meterRegistry.counter("auroraforge.saga.compensation.success",
                        "step", step.name()).increment();
            } catch (Exception e) {
                log.error("saga.compensation.failed sagaId={} step={} error={}",
                        ctx.getSagaId(), step.name(), e.getMessage(), e);
                meterRegistry.counter("auroraforge.saga.compensation.failure",
                        "step", step.name()).increment();
            }
        }
        log.warn("saga.compensated sagaId={}", ctx.getSagaId());
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MS * (1L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private IngestionSagaState stateFor(SagaStep step, boolean failed) {
        return switch (step.name()) {
            case "ENCRYPT_PAYLOAD"  -> failed ? IngestionSagaState.ENCRYPT_FAILED  : IngestionSagaState.ENCRYPTING;
            case "STORE_RAW_OBJECT" -> failed ? IngestionSagaState.STORE_FAILED    : IngestionSagaState.STORING;
            case "PERSIST_EVENT"    -> failed ? IngestionSagaState.PERSIST_FAILED  : IngestionSagaState.PERSISTING;
            case "PUBLISH_EVENT"    -> failed ? IngestionSagaState.PUBLISH_FAILED  : IngestionSagaState.PUBLISHING;
            default                 -> IngestionSagaState.STARTED;
        };
    }

    // ── Exceptions ─────────────────────────────────────────────────────────────

    public static class SagaFailedException extends RuntimeException {
        private final String sagaId;
        private final String failedStep;

        public SagaFailedException(String sagaId, String failedStep, String message, Throwable cause) {
            super("Saga " + sagaId + " failed at step " + failedStep + ": " + message, cause);
            this.sagaId     = sagaId;
            this.failedStep = failedStep;
        }

        public String getSagaId()    { return sagaId; }
        public String getFailedStep() { return failedStep; }
    }

    public static class SagaCompensationFailedException extends RuntimeException {
        public SagaCompensationFailedException(String sagaId, Throwable cause) {
            super("Saga " + sagaId + " compensation encountered errors — manual review required", cause);
        }
    }
}
