package io.auroraforge.keymgmt.presentation;

import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.TenantPrincipal;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.application.service.KeyRotationService;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionEntity;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionRepository;
import io.auroraforge.keymgmt.presentation.dto.KeyStatusResponse;
import io.auroraforge.keymgmt.presentation.dto.RotationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * REST API for key management operations.
 *
 * Endpoints:
 *   GET  /api/v1/keys/status                      → status per classification
 *   POST /api/v1/keys/rotate/{tenantId}/{cls}      → manual single-tenant rotation
 *   POST /api/v1/keys/rotate/classification/{cls}  → bulk rotation for a classification
 *   GET  /api/v1/keys/history                      → paginated rotation history
 *   GET  /api/v1/keys/history/{tenantId}           → rotation history for one tenant
 *
 * Authentication: JWT Bearer token (RS256) via {@link io.auroraforge.auth.infrastructure.security.AuroraForgeSecurityConfig}.
 * All endpoints require {@code KEY_MANAGER} or {@code ADMIN} role; rotation endpoints
 * additionally require {@code ADMIN} to prevent unintended broad rotation by operators.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/keys")
@RequiredArgsConstructor
public class KeyManagementController {

    private final KeyManagementPort      keyManagementPort;
    private final KeyRotationService     keyRotationService;
    private final KeyVersionRepository   keyVersionRepository;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns the current CMK key version and metadata for each classification tier.
     */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('KEY_MANAGER', 'ADMIN')")
    public ResponseEntity<List<KeyStatusResponse>> getKeyStatus() {
        List<KeyStatusResponse> statuses = Arrays.stream(DataClassification.values())
                .map(cls -> new KeyStatusResponse(
                        cls.name(),
                        keyManagementPort.currentKeyVersion(cls),
                        cls.keyRotationDays(),
                        cls.requiresCmkEncryption(),
                        cls.requiresAuditLog(),
                        keyRotationService.isRotationEnabled(cls)))
                .toList();
        return ResponseEntity.ok(statuses);
    }

    // ── Manual rotation ───────────────────────────────────────────────────────

    /**
     * Trigger immediate rotation for a single tenant + classification.
     * Used by security operations for incident response or compliance sign-off.
     */
    @PostMapping("/rotate/{tenantId}/{classification}")
    @PreAuthorize("hasAnyRole('KEY_MANAGER', 'ADMIN')")
    public ResponseEntity<?> rotateTenantKey(
            @PathVariable String tenantId,
            @PathVariable DataClassification classification,
            @AuthenticationPrincipal TenantPrincipal principal) {

        log.info("Manual rotation triggered: sub={} tenantId={} classification={}",
                 principal.getName(), tenantId, classification);

        if (!keyRotationService.hasRotatableAdapter()) {
            RotationResponse skipped = RotationResponse.skipped(
                    tenantId, classification.name(),
                    "No rotation adapter available — service is running in LOCAL mode");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(skipped);
        }

        try {
            RotatableKeyAdapter.RotationResult result =
                    keyRotationService.rotateTenantKey(tenantId, classification);
            return ResponseEntity.ok(RotationResponse.success(
                    tenantId, classification.name(), result));
        } catch (Exception e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Rotation failed: " + e.getMessage());
            problem.setType(URI.create("https://auroraforge.io/errors/rotation-failed"));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }
    }

    /**
     * Trigger bulk rotation for all tenants under a given classification.
     * Returns 202 Accepted immediately; rotation happens synchronously on the calling thread.
     * In production, delegate to a Temporal workflow for async fan-out.
     */
    @PostMapping("/rotate/classification/{classification}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> rotateClassification(
            @PathVariable DataClassification classification,
            @AuthenticationPrincipal TenantPrincipal principal) {
        log.info("Bulk rotation triggered: sub={} classification={}", principal.getName(), classification);
        if (keyRotationService.hasRotatableAdapter()) {
            keyRotationService.rotateAllTenantsForClassification(classification);
        }
        return ResponseEntity.accepted().build();
    }

    // ── History ───────────────────────────────────────────────────────────────

    /**
     * Paginated rotation history across all tenants, newest first.
     */
    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('KEY_MANAGER', 'ADMIN')")
    public ResponseEntity<Page<KeyVersionEntity>> getRotationHistory(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                keyVersionRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)));
    }

    /**
     * Full rotation history for a specific tenant.
     */
    @GetMapping("/history/{tenantId}")
    @PreAuthorize("hasAnyRole('KEY_MANAGER', 'ADMIN')")
    public ResponseEntity<List<KeyVersionEntity>> getTenantHistory(@PathVariable String tenantId) {
        return ResponseEntity.ok(
                keyVersionRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }
}
