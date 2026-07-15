package io.auroraforge.keymgmt.application.service;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionEntity;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Immutable audit trail for all key management events.
 *
 * Audit entries are emitted as structured SLF4J log lines prefixed with "AUDIT".
 * These are picked up by the OTel Collector log exporter and forwarded to:
 *  - AWS:   CloudWatch Logs (auroraforge/keymgmt/audit log group, KMS-encrypted)
 *  - Azure: Log Analytics Workspace → Sentinel SIEM
 *
 * Additionally, key rotation events are persisted to the {@code key_versions} table
 * for in-product audit queries via the REST API.
 *
 * Encryption/decryption events are NOT persisted to the DB (too high volume);
 * they live exclusively in the structured log stream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeyManagementAuditService {

    private final KeyVersionRepository keyVersionRepository;

    // ── Encrypt / Decrypt (log-only, no DB write) ─────────────────────────────

    public void auditEncrypt(String tenantId, DataClassification classification,
                             String keyVersion, long plaintextBytes) {
        // AUDIT lines are machine-parseable: all fields are key=value pairs
        log.info("AUDIT event=key-management.encrypt tenantId={} classification={} " +
                        "keyVersion={} plaintextBytes={} ts={}",
                tenantId, classification, keyVersion, plaintextBytes, Instant.now());
    }

    public void auditDecrypt(String tenantId, DataClassification classification,
                             String keyVersion) {
        log.info("AUDIT event=key-management.decrypt tenantId={} classification={} " +
                        "keyVersion={} ts={}",
                tenantId, classification, keyVersion, Instant.now());
    }

    // ── Rotation (log + DB write) ─────────────────────────────────────────────

    public void auditRotationStart(String tenantId, DataClassification classification) {
        log.info("AUDIT event=key-management.rotation.start tenantId={} classification={} ts={}",
                tenantId, classification, Instant.now());
    }

    public void auditRotationComplete(String tenantId, DataClassification classification,
                                      String previousVersion, String newVersion,
                                      String cloudProvider) {
        log.info("AUDIT event=key-management.rotation.complete tenantId={} classification={} " +
                        "previousVersion={} newVersion={} cloudProvider={} ts={}",
                tenantId, classification, previousVersion, newVersion, cloudProvider, Instant.now());
    }

    public void auditRotationFailure(String tenantId, DataClassification classification,
                                     String reason) {
        log.error("AUDIT event=key-management.rotation.failure tenantId={} classification={} " +
                        "reason={} ts={}",
                tenantId, classification, reason, Instant.now());
    }

    // ── Persistence: key version tracking ────────────────────────────────────

    /**
     * Records a new active key version in the DB, deactivating the previous one.
     * Called by {@link KeyRotationService} after a successful rotation.
     *
     * @return the newly persisted {@link KeyVersionEntity}
     */
    @Transactional
    public KeyVersionEntity recordNewKeyVersion(String tenantId,
                                                DataClassification classification,
                                                String newKeyVersion,
                                                String cloudProvider) {
        // Deactivate current active version (bulk JPQL update, no N+1)
        int deactivated = keyVersionRepository.deactivateCurrentVersion(tenantId, classification);
        int rotationCount = keyVersionRepository.countRotations(tenantId, classification);

        KeyVersionEntity entity = KeyVersionEntity.builder()
                .tenantId(tenantId)
                .classification(classification)
                .keyVersion(newKeyVersion)
                .cloudProvider(cloudProvider)
                .active(true)
                .rotationCount(rotationCount + 1)
                .build();

        KeyVersionEntity saved = keyVersionRepository.save(entity);
        log.debug("KeyVersion persisted: tenantId={} classification={} keyVersion={} rotationCount={}",
                tenantId, classification, newKeyVersion, saved.getRotationCount());
        return saved;
    }
}
