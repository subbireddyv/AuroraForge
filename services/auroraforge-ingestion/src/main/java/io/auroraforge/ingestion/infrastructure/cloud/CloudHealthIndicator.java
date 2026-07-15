package io.auroraforge.ingestion.infrastructure.cloud;

import io.auroraforge.core.application.port.out.CloudObjectStoragePort;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.core.domain.model.TenantId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Spring Boot Actuator health indicator for the active cloud storage adapter.
 *
 * Performs a lightweight exists() probe on a well-known sentinel object
 * ({@code health-check/probe.txt}) to verify end-to-end connectivity.
 * The probe tenant uses a reserved ID that is excluded from tenant routing.
 *
 * Exposed at: GET /internal/actuator/health/cloudStorage
 *
 * A DOWN status here triggers the Kubernetes readiness probe failure, removing
 * the pod from load balancer rotation until storage is reachable.
 */
@Slf4j
@Component("cloudStorage")
@RequiredArgsConstructor
public class CloudHealthIndicator implements HealthIndicator {

    private static final TenantId PROBE_TENANT  = TenantId.of("health-probe");
    private static final String   PROBE_KEY      = "health-check/probe.txt";

    private final CloudObjectStoragePort cloudObjectStoragePort;
    private final CloudProperties        cloudProperties;

    @Override
    public Health health() {
        try {
            long startMs = System.currentTimeMillis();
            cloudObjectStoragePort.exists(PROBE_TENANT, PROBE_KEY);
            long elapsedMs = System.currentTimeMillis() - startMs;

            return Health.up()
                    .withDetail("provider",   cloudProperties.provider().name())
                    .withDetail("probeMs",    elapsedMs)
                    .withDetail("checkedAt",  Instant.now().toString())
                    .build();

        } catch (Exception e) {
            log.warn("Cloud storage health check failed: {}", e.getMessage());
            return Health.down(e)
                    .withDetail("provider",  cloudProperties.provider().name())
                    .withDetail("checkedAt", Instant.now().toString())
                    .build();
        }
    }
}
