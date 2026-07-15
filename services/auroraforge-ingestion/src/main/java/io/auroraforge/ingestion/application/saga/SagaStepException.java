package io.auroraforge.ingestion.application.saga;

/**
 * Thrown by a {@link SagaStep} when its forward action fails in a way that
 * should trigger saga compensation.
 *
 * {@code retryable} distinguishes transient failures (circuit open, timeout)
 * from permanent ones (validation, authentication) so the orchestrator can
 * decide whether to attempt a retry before compensating.
 */
public class SagaStepException extends Exception {

    private final String stepName;
    private final boolean retryable;

    public SagaStepException(String stepName, String message, Throwable cause, boolean retryable) {
        super("[" + stepName + "] " + message, cause);
        this.stepName  = stepName;
        this.retryable = retryable;
    }

    public SagaStepException(String stepName, String message, boolean retryable) {
        super("[" + stepName + "] " + message);
        this.stepName  = stepName;
        this.retryable = retryable;
    }

    public String getStepName()  { return stepName; }
    public boolean isRetryable() { return retryable; }
}
