package io.auroraforge.ingestion.application.saga;

/**
 * A single unit of work within a saga.
 *
 * Every step must provide a compensating action ({@link #compensate}) that is
 * idempotent — the orchestrator will call it on every step whose forward action
 * succeeded, in reverse order, on any downstream failure.
 *
 * Implementations should:
 *   - be stateless (all mutable state lives in the {@link IngestionSagaContext})
 *   - throw a {@link SagaStepException} on unrecoverable failure
 *   - tolerate being called more than once (idempotency guard via sagaId + step name)
 */
public interface SagaStep {

    /**
     * Human-readable step label used in logs and metrics.
     */
    String name();

    /**
     * Execute the forward action. Returns an enriched context carrying the
     * step's output (e.g. the object storage key, persisted entity ID, etc.).
     */
    IngestionSagaContext execute(IngestionSagaContext ctx) throws SagaStepException;

    /**
     * Execute the compensating action. Must be idempotent.
     * Called only if {@link #execute} previously returned successfully.
     */
    void compensate(IngestionSagaContext ctx);
}
