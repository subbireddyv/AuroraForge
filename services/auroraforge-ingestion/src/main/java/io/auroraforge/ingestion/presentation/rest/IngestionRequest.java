package io.auroraforge.ingestion.presentation.rest;

import io.auroraforge.core.domain.model.DataClassification;
import jakarta.validation.constraints.*;

import java.util.Map;

/**
 * HTTP request body for POST /api/v1/events.
 * Validation annotations enforce the API contract at the presentation boundary.
 * The controller maps this to an {@link io.auroraforge.core.application.port.in.IngestEventCommand}.
 */
public record IngestionRequest(

        @NotBlank(message = "schemaName is required")
        @Size(max = 256)
        String schemaName,

        @Min(value = 1, message = "schemaVersion must be >= 1")
        int schemaVersion,

        @NotNull(message = "classification is required")
        DataClassification classification,

        /** Base64-encoded payload. Null is valid for schema-only events. */
        String payloadBase64,

        @Size(max = 64)
        Map<String, String> metadata,

        /**
         * Client-supplied idempotency key. If provided and a prior request with the same
         * key succeeded, the existing event is returned (HTTP 200, not 201).
         */
        @Size(max = 255)
        String idempotencyKey
) {}
