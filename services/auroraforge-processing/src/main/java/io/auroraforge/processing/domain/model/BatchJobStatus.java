package io.auroraforge.processing.domain.model;

public enum BatchJobStatus {
    SUBMITTED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
