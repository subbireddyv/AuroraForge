package io.auroraforge.core.domain.model;

/**
 * Lifecycle state of a {@link DataEvent} aggregate.
 * Transitions: PENDING → PROCESSING → PROCESSED | FAILED
 *                                    └──────────→ DEAD_LETTERED (after exhausting retries)
 */
public enum EventStatus {

    PENDING,
    PROCESSING,
    PROCESSED,
    FAILED,
    DEAD_LETTERED;

    public boolean isTerminal() {
        return this == PROCESSED || this == DEAD_LETTERED;
    }

    public boolean canTransitionTo(EventStatus next) {
        return switch (this) {
            case PENDING        -> next == PROCESSING;
            case PROCESSING     -> next == PROCESSED || next == FAILED;
            case FAILED         -> next == PROCESSING || next == DEAD_LETTERED;
            case PROCESSED,
                 DEAD_LETTERED  -> false;  // Terminal states
        };
    }
}
