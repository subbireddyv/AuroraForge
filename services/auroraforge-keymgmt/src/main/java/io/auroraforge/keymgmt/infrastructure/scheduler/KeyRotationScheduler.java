package io.auroraforge.keymgmt.infrastructure.scheduler;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.service.KeyRotationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules automated CMK rotation per classification tier.
 *
 * Rotation frequency aligns with DataClassification.keyRotationDays():
 *  PUBLIC / INTERNAL  → annually (weekly check, cloud-managed yearly rotation)
 *  CONFIDENTIAL       → 90-day  (monthly trigger to stay within the 90-day window)
 *  RESTRICTED         → 30-day  (monthly trigger to stay within the 30-day window)
 *
 * In production the scheduler runs on one replica only (leader election via DB lock or
 * ShedLock). The cron expressions can be overridden per-environment in application-*.yml.
 *
 * Activation is controlled by {@code auroraforge.keymgmt.rotation.enabled=true} (default).
 * Set to {@code false} in environments where rotation is managed externally (e.g., via
 * a Temporal workflow or an operator-triggered REST call).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name  = "auroraforge.keymgmt.rotation.enabled",
        havingValue = "true",
        matchIfMissing = true   // enabled by default
)
public class KeyRotationScheduler {

    private final KeyRotationService keyRotationService;

    /**
     * PUBLIC / INTERNAL rotation — weekly Sunday 02:00 UTC.
     * AWS KMS annual rotation is idempotent; calling this every week just ensures the
     * feature flag stays enabled on the CMK.
     */
    @Scheduled(cron = "${auroraforge.keymgmt.rotation.public-keys-cron:0 0 2 ? * SUN}")
    public void rotatePublicKeys() {
        log.info("Scheduled rotation: classification=PUBLIC");
        keyRotationService.rotateAllTenantsForClassification(DataClassification.PUBLIC);
    }

    @Scheduled(cron = "${auroraforge.keymgmt.rotation.internal-keys-cron:0 0 2 ? * SUN}")
    public void rotateInternalKeys() {
        log.info("Scheduled rotation: classification=INTERNAL");
        keyRotationService.rotateAllTenantsForClassification(DataClassification.INTERNAL);
    }

    /**
     * CONFIDENTIAL rotation — 1st of each month at 03:00 UTC.
     * Keeps within the 90-day rotation requirement.
     */
    @Scheduled(cron = "${auroraforge.keymgmt.rotation.confidential-keys-cron:0 0 3 1 * ?}")
    public void rotateConfidentialKeys() {
        log.info("Scheduled rotation: classification=CONFIDENTIAL");
        keyRotationService.rotateAllTenantsForClassification(DataClassification.CONFIDENTIAL);
    }

    /**
     * RESTRICTED rotation — 1st of each month at 04:00 UTC.
     * Keeps within the 30-day rotation requirement for PII / regulated data.
     */
    @Scheduled(cron = "${auroraforge.keymgmt.rotation.restricted-keys-cron:0 0 4 1 * ?}")
    public void rotateRestrictedKeys() {
        log.info("Scheduled rotation: classification=RESTRICTED");
        keyRotationService.rotateAllTenantsForClassification(DataClassification.RESTRICTED);
    }
}
