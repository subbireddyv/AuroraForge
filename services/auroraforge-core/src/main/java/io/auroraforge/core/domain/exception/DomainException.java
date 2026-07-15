package io.auroraforge.core.domain.exception;

/**
 * Base class for all domain exceptions in AuroraForge.
 * Domain exceptions represent violated business invariants, not technical failures.
 * They are translated to appropriate HTTP status codes at the presentation layer.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
