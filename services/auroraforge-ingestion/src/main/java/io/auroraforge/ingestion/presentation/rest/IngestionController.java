package io.auroraforge.ingestion.presentation.rest;

import io.auroraforge.core.application.dto.EventDto;
import io.auroraforge.core.application.dto.EventPageDto;
import io.auroraforge.core.application.port.in.IngestEventCommand;
import io.auroraforge.core.application.port.in.IngestEventUseCase;
import io.auroraforge.core.application.port.in.QueryEventUseCase;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.model.EventStatus;
import io.auroraforge.core.domain.security.TenantPrincipal;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Presentation layer: REST controller for data event ingestion and querying.
 *
 * Routes:
 *  POST   /api/v1/tenants/{tenantId}/events          → ingest single event
 *  GET    /api/v1/tenants/{tenantId}/events/{eventId} → get event by ID
 *  GET    /api/v1/tenants/{tenantId}/events?status=   → list events by status
 *
 * The controller:
 *  - Validates input (via @Valid)
 *  - Maps request/response (IngestionRequest ↔ IngestEventCommand, EventDto ↔ IngestionResponse)
 *  - Delegates ALL business logic to use case interfaces
 *  - Never directly calls repositories or infrastructure
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/events")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestEventUseCase ingestUseCase;
    private final QueryEventUseCase  queryUseCase;

    /**
     * Ingest a data event.
     *
     * Access rules enforced here:
     * 1. Role check: caller must have DATA_INGEST or ADMIN role (via @PreAuthorize).
     * 2. Tenant isolation: JWT tid claim must match {tenantId} path variable.
     * 3. Classification access: principal must be allowed to write at the requested level.
     */
    @PostMapping
    @Timed(value = "auroraforge.ingestion.ingest", description = "Time taken to ingest a DataEvent")
    @PreAuthorize("hasAnyRole('DATA_INGEST', 'ADMIN')")
    public ResponseEntity<?> ingest(
            @PathVariable String tenantId,
            @Valid @RequestBody IngestionRequest request,
            @AuthenticationPrincipal TenantPrincipal principal) {

        // Tenant isolation — 403 if JWT is for a different tenant
        principal.assertTenantAccess(tenantId);

        // Classification-level access control
        DataClassification cls = request.classification();
        if (!principal.canAccess(cls)) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
            pd.setDetail("Your token does not grant access to classification: " + cls);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
        }

        log.info("Ingest request: sub={} tenantId={} schema={} v{} cls={}",
                 principal.getName(), tenantId, request.schemaName(),
                 request.schemaVersion(), cls);

        byte[] payload = decodePayload(request.payloadBase64());

        IngestEventCommand command = new IngestEventCommand(
                tenantId,
                request.schemaName(),
                request.schemaVersion(),
                cls,
                payload,
                request.metadata() != null ? request.metadata() : Map.of(),
                request.idempotencyKey(),
                principal.getName());   // sourceSystem = authenticated subject

        EventDto dto = ingestUseCase.ingest(command);

        IngestionResponse body      = IngestionResponse.from(dto);
        boolean          isDuplicate = request.idempotencyKey() != null
                                       && !dto.createdAt().equals(dto.updatedAt());

        if (isDuplicate) {
            return ResponseEntity.ok(body);   // 200 – idempotent duplicate
        }

        return ResponseEntity
                .created(URI.create("/api/v1/tenants/%s/events/%s".formatted(tenantId, dto.id())))
                .body(body);  // 201 Created
    }

    @GetMapping("/{eventId}")
    @Timed(value = "auroraforge.ingestion.getById")
    @PreAuthorize("hasAnyRole('DATA_QUERY', 'ADMIN')")
    public ResponseEntity<IngestionResponse> getById(
            @PathVariable String tenantId,
            @PathVariable String eventId,
            @AuthenticationPrincipal TenantPrincipal principal) {

        principal.assertTenantAccess(tenantId);

        EventDto dto = queryUseCase.findById(tenantId, eventId);
        return ResponseEntity.ok(IngestionResponse.from(dto));
    }

    @GetMapping
    @Timed(value = "auroraforge.ingestion.list")
    @PreAuthorize("hasAnyRole('DATA_QUERY', 'ADMIN')")
    public ResponseEntity<EventPageDto> list(
            @PathVariable String tenantId,
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        principal.assertTenantAccess(tenantId);

        EventPageDto result;

        if (from != null && to != null) {
            result = queryUseCase.findByTenantCreatedBetween(tenantId, from, to, page, size);
        } else {
            EventStatus effectiveStatus = status != null ? status : EventStatus.PENDING;
            result = queryUseCase.findByTenantAndStatus(tenantId, effectiveStatus, page, size);
        }

        return result.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(result);
    }

    private byte[] decodePayload(String payloadBase64) {
        if (payloadBase64 == null || payloadBase64.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(payloadBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("payloadBase64 is not valid Base64: " + e.getMessage());
        }
    }
}
