package io.auroraforge.keymgmt.application.service;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.infrastructure.cache.DataKeyCache;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionEntity;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Unified rotation service — cloud-agnostic orchestration of the full rotation lifecycle.
 *
 * Rotation sequence per tenant:
 *  1. Audit: log rotation start.
 *  2. Delegate to {@link RotatableKeyAdapter} (AWS or Azure specific logic).
 *  3. Evict the DEK cache for this tenant+classification (next encrypt generates a new DEK).
 *  4. Persist the new key version to the {@code key_versions} table.
 *  5. Audit: log rotation complete.
 *
 * Failure handling:
 *  - Rotation failures are caught per-tenant during bulk rotation so other tenants proceed.
 *  - The failed tenant is logged with full stack trace (audit.rotation.failure event).
 *  - No partial state is written on failure: the @Transactional wraps step 4 only.
 *
 * If no {@link RotatableKeyAdapter} bean is present (LOCAL mode), rotation is a no-op.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyRotationService {

    private final KeyVersionRepository    keyVersionRepository;
    private final KeyManagementAuditService auditService;
    private final DataKeyCache             dataKeyCache;

    /**
     * Injected when an AWS or Azure adapter is on the classpath.
     * Null in LOCAL mode — guard every use with a null check.
     */
    @Autowired(required = false)
    private RotatableKeyAdapter rotatableKeyAdapter;

    // ── Single-tenant rotation ────────────────────────────────────────────────

    /**
     * Rotates the CMK key version for one tenant's classification tier.
     *
     * @return rotation result, or {@code null} when running in LOCAL mode
     */
    @Transactional
    public RotatableKeyAdapter.RotationResult rotateTenantKey(String tenantId,
                                                               DataClassification classification) {
        if (rotatableKeyAdapter == null) {
            log.warn("Key rotation skipped for tenantId={} — no RotatableKeyAdapter (LOCAL mode)",
                    tenantId);
            return null;
        }

        auditService.auditRotationStart(tenantId, classification);
        log.info("Rotating key: tenantId={} classification={}", tenantId, classification);

        try {
            RotatableKeyAdapter.RotationResult result =
                    rotatableKeyAdapter.triggerRotation(classification);

            // DEK cache eviction: next encrypt for this tenant will obtain a fresh DEK
            // wrapped under the new CMK version
            dataKeyCache.evict(tenantId, classification);

            // Persist the new version to the audit table
            auditService.recordNewKeyVersion(tenantId, classification,
                    result.newVersion(), result.cloudProvider());

            auditService.auditRotationComplete(tenantId, classification,
                    result.previousVersion(), result.newVersion(), result.cloudProvider());

            return result;

        } catch (Exception e) {
            auditService.auditRotationFailure(tenantId, classification, e.getMessage());
            throw e;   // propagate so the caller (scheduler or controller) can react
        }
    }

    // ── Bulk rotation (all tracked tenants for a classification) ─────────────

    /**
     * Rotates all tenants that have an active key version for the given classification.
     *
     * Failures for individual tenants are caught and logged; other tenants proceed.
     * A summary of success/failure counts is logged at the end.
     */
    public void rotateAllTenantsForClassification(DataClassification classification) {
        if (rotatableKeyAdapter == null) {
            log.info("Bulk rotation skipped for classification={} — LOCAL mode", classification);
            return;
        }

        List<KeyVersionEntity> activeVersions =
                keyVersionRepository.findByClassificationAndActiveTrueOrderByCreatedAtAsc(classification);

        if (activeVersions.isEmpty()) {
            log.info("No tracked tenants for classification={} — triggering CMK-level rotation only",
                    classification);
            // Still trigger the CMK rotation at the cloud level; evict all DEKs
            rotatableKeyAdapter.triggerRotation(classification);
            dataKeyCache.evictAll();
            return;
        }

        log.info("Starting bulk rotation for classification={} across {} tenants",
                classification, activeVersions.size());

        int successCount = 0;
        int failureCount = 0;

        for (KeyVersionEntity entity : activeVersions) {
            try {
                rotateTenantKey(entity.getTenantId(), classification);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.error("Rotation failed: tenantId={} classification={}",
                        entity.getTenantId(), classification, e);
            }
        }

        log.info("Bulk rotation complete: classification={} success={} failure={}",
                classification, successCount, failureCount);
    }

    // ── Status queries ────────────────────────────────────────────────────────

    public boolean isRotationEnabled(DataClassification classification) {
        return rotatableKeyAdapter != null && rotatableKeyAdapter.isRotationEnabled(classification);
    }

    public boolean hasRotatableAdapter() {
        return rotatableKeyAdapter != null;
    }
}
