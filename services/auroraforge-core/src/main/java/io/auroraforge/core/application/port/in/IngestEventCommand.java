package io.auroraforge.core.application.port.in;

import io.auroraforge.core.domain.model.DataClassification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Command object for the IngestEvent use case.
 * Implements the Command pattern: encapsulates all data needed to create a DataEvent.
 * Validated at the presentation layer before the use case is invoked.
 */
public record IngestEventCommand(

        @NotBlank(message = "tenantId must not be blank")
        String tenantId,

        @NotBlank(message = "schemaName must not be blank")
        @Size(max = 256)
        String schemaName,

        @Positive(message = "schemaVersion must be >= 1")
        int schemaVersion,

        @NotNull(message = "classification must not be null")
        DataClassification classification,

        byte[] rawPayload,

        @Size(max = 64, message = "metadata map must not exceed 64 entries")
        Map<String, String> metadata,

        /** Idempotency key: if provided, duplicate ingestion returns the existing event. */
        String idempotencyKey,

        /** Source system identifier for audit trail. */
        @NotBlank
        String sourceSystem
) {
    public IngestEventCommand {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
