package io.auroraforge.ingestion.presentation.rest;

import io.auroraforge.core.application.dto.EventDto;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.EventStatus;

import java.time.Instant;
import java.util.Map;

/**
 * HTTP response body for ingestion and query endpoints.
 * Never exposes internal domain fields (no payload, no encryption key material).
 */
public record IngestionResponse(
        String id,
        String tenantId,
        String schemaName,
        int schemaVersion,
        DataClassification classification,
        EventStatus status,
        boolean encrypted,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> metadata
) {
    public static IngestionResponse from(EventDto dto) {
        return new IngestionResponse(
                dto.id(), dto.tenantId(), dto.schemaName(), dto.schemaVersion(),
                dto.classification(), dto.status(), dto.isEncrypted(),
                dto.createdAt(), dto.updatedAt(), dto.metadata());
    }
}
