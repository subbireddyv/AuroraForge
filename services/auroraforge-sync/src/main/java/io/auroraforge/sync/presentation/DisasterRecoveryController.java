package io.auroraforge.sync.presentation;

import io.auroraforge.sync.application.service.ConsistencyVerificationService;
import io.auroraforge.sync.application.service.DisasterRecoveryCoordinator;
import io.auroraforge.sync.application.service.ReplicationLagMonitor;
import io.auroraforge.sync.domain.model.ConsistencyReport;
import io.auroraforge.sync.domain.model.DisasterRecoveryState;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordEntity;
import io.auroraforge.sync.infrastructure.persistence.DlqRecordRepository;
import io.auroraforge.sync.presentation.dto.DrStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for disaster recovery operations and observability.
 *
 * Endpoints:
 *   GET  /api/v1/dr/status                           → current DR state and cloud health
 *   POST /api/v1/dr/failover/{targetCloud}           → trigger manual failover
 *   POST /api/v1/dr/recover                          → mark recovery complete
 *   GET  /api/v1/dr/consistency/{tenantId}           → on-demand consistency check
 *   GET  /api/v1/dr/replication-lag                  → current lag per cloud
 *   GET  /api/v1/dr/dlq/{tenantId}                   → paginated DLQ records
 *   POST /api/v1/dr/dlq/{tenantId}/resolve           → mark all DLQ records resolved
 *
 * Authorization: PLATFORM_OPS or ADMIN role (enforced by AuroraForgeSecurityConfig).
 * These endpoints are intentionally not annotated here — the security config applies
 * path-level rules centrally to keep authorization in a single location.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/dr")
@RequiredArgsConstructor
public class DisasterRecoveryController {

    private final DisasterRecoveryCoordinator       coordinator;
    private final ConsistencyVerificationService    consistencyVerifier;
    private final ReplicationLagMonitor             lagMonitor;
    private final DlqRecordRepository               dlqRepository;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns the current DR state, per-cloud health snapshots, and replication lag.
     * Safe to call at any frequency; no write side-effects.
     */
    @GetMapping("/status")
    public ResponseEntity<DrStatusResponse> getStatus() {
        Map<String, Double> lag = coordinator.getLatestHealth().keySet().stream()
                .collect(Collectors.toMap(
                        cloud -> cloud,
                        lagMonitor::getLagSeconds));

        DrStatusResponse response = new DrStatusResponse(
                coordinator.getState(),
                coordinator.getActiveCloud(),
                coordinator.getLatestHealth(),
                lag,
                coordinator.getFailoverActiveSince(),
                Instant.now());

        return ResponseEntity.ok(response);
    }

    // ── Failover ──────────────────────────────────────────────────────────────

    /**
     * Triggers a manual failover to the target cloud.
     * Idempotent: calling while already in failover returns 409.
     *
     * @param targetCloud e.g. "azure" or "aws"
     */
    @PostMapping("/failover/{targetCloud}")
    public ResponseEntity<?> triggerFailover(@PathVariable String targetCloud) {
        DisasterRecoveryState current = coordinator.getState();

        if (current.isFailoverActive()) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            pd.setType(URI.create("https://auroraforge.io/errors/dr/failover-already-active"));
            pd.setTitle("Failover Already Active");
            pd.setDetail("DR state is already " + current + ". No action taken.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
        }

        log.warn("Manual failover requested: targetCloud={}", targetCloud);
        coordinator.triggerFailover(targetCloud);

        return ResponseEntity.accepted().build();  // 202 — routing commit is async
    }

    // ── Recovery ──────────────────────────────────────────────────────────────

    /**
     * Marks recovery complete after the operator confirms back-fill is finished.
     * Must be called from RECOVERING state; returns 409 if called from any other state.
     */
    @PostMapping("/recover")
    public ResponseEntity<?> completeRecovery() {
        try {
            coordinator.completeRecovery();
            log.info("Recovery marked complete via REST API");
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            pd.setType(URI.create("https://auroraforge.io/errors/dr/invalid-state-transition"));
            pd.setTitle("Invalid DR State Transition");
            pd.setDetail(e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
        }
    }

    // ── Consistency verification ───────────────────────────────────────────────

    /**
     * Performs an on-demand consistency check for a tenant across AWS and Azure.
     *
     * @param tenantId     tenant to verify
     * @param aggregateIds comma-separated list of aggregate IDs to sample
     */
    @GetMapping("/consistency/{tenantId}")
    public ResponseEntity<ConsistencyReport> checkConsistency(
            @PathVariable String tenantId,
            @RequestParam List<String> aggregateIds) {

        if (aggregateIds.isEmpty()) {
            return ResponseEntity.ok(ConsistencyReport.perfect(tenantId, 0));
        }

        ConsistencyReport report = consistencyVerifier.verify(tenantId, aggregateIds);
        HttpStatus status = report.isFullyConsistent() ? HttpStatus.OK : HttpStatus.MULTI_STATUS;
        return ResponseEntity.status(status).body(report);
    }

    // ── Replication lag ───────────────────────────────────────────────────────

    /**
     * Returns current replication lag in seconds per cloud provider.
     * A value of -1 indicates no sync has been observed yet since startup.
     */
    @GetMapping("/replication-lag")
    public ResponseEntity<Map<String, Double>> getReplicationLag() {
        Map<String, Double> lag = coordinator.getLatestHealth().keySet().stream()
                .collect(Collectors.toMap(
                        cloud -> cloud,
                        lagMonitor::getLagSeconds));
        return ResponseEntity.ok(lag);
    }

    // ── DLQ management ────────────────────────────────────────────────────────

    /**
     * Returns paginated DLQ records for a tenant, newest first.
     */
    @GetMapping("/dlq/{tenantId}")
    public ResponseEntity<Page<DlqRecordEntity>> getDlq(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DlqRecordEntity> records =
                dlqRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, PageRequest.of(page, size));
        return records.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(records);
    }

    /**
     * Bulk-resolves all DLQ records for a tenant (e.g. post-tenant-offboarding cleanup).
     * Returns 200 with the count of records resolved.
     */
    @PostMapping("/dlq/{tenantId}/resolve")
    public ResponseEntity<Map<String, Integer>> resolveDlq(@PathVariable String tenantId) {
        int resolved = dlqRepository.resolveAllForTenant(tenantId);
        log.info("DLQ bulk-resolve: tenantId={} recordsResolved={}", tenantId, resolved);
        return ResponseEntity.ok(Map.of("resolved", resolved));
    }
}
