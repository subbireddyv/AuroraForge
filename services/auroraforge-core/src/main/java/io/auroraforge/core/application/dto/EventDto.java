package io.auroraforge.core.application.dto;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.EventStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable DTO returned to callers of the application use cases.
 * Never exposes domain internals (no aggregate state, no events list).
 * The presentation layer maps this to its own response models.
 */
public record EventDto(
        String id,
        String tenantId,
        String schemaName,
        int schemaVersion,
        DataClassification classification,
        EventStatus status,
        String encryptionKeyVersion,
        Instant createdAt,
        Instant updatedAt,
        int retryCount,
        String failureReason,
        Map<String, String> metadata
) {
    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isEncrypted() {
        return encryptionKeyVersion != null;
    }
}
