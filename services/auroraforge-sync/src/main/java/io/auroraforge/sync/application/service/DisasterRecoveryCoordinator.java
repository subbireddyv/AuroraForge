package io.auroraforge.sync.application.service;

import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.CloudHealth;
import io.auroraforge.sync.domain.model.DisasterRecoveryState;
import io.auroraforge.sync.domain.port.CloudHealthPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core DR coordinator: orchestrates the cloud-health state machine, failover,
 * and recovery operations.
 *
 * State machine transitions (automated):
 *   HEALTHY         → DEGRADED         when a cloud is unhealthy for ≥ degradedThresholdSeconds
 *   DEGRADED        → FAILOVER_INITIATED (manual) or stays DEGRADED (automatic watch)
 *   FAILOVER_INITIATED → FAILOVER_ACTIVE after routing committed
 *   FAILOVER_ACTIVE → RECOVERING       when the degraded cloud comes back
 *   RECOVERING      → HEALTHY          when back-fill completes (manual via completeRecovery())
 *
 * Micrometer gauge:
 *   auroraforge.dr.state — ordinal of the current DR state (0=HEALTHY … 4=RECOVERING)
 */
@Slf4j
@Service
public class DisasterRecoveryCoordinator {

    private final List<CloudHealthPort>        healthPorts;
    private final DisasterRecoveryProperties   drProps;
    private final ReplicationLagMonitor        lagMonitor;

    private final AtomicReference<DisasterRecoveryState> state;
    private final Map<String, CloudHealth>               latestHealth   = new ConcurrentHashMap<>();
    private final Map<String, Instant>                   degradedSince  = new ConcurrentHashMap<>();

    /** Timestamp when FAILOVER_ACTIVE was entered (for RTO measurement). */
    @Getter private volatile Instant failoverActiveSince;

    /** The cloud currently receiving writes (primary during HEALTHY; DR cloud during failover). */
    @Getter private volatile String  activeCloud;

    public DisasterRecoveryCoordinator(List<CloudHealthPort> healthPorts,
                                        DisasterRecoveryProperties drProps,
                                        ReplicationLagMonitor lagMonitor,
                                        MeterRegistry registry) {
        this.healthPorts = healthPorts;
        this.drProps     = drProps;
        this.lagMonitor  = lagMonitor;
        this.state       = new AtomicReference<>(DisasterRecoveryState.HEALTHY);
        this.activeCloud = drProps.primaryCloud();

        Gauge.builder("auroraforge.dr.state",
                      () -> (double) state.get().ordinal())
             .description("DR state machine ordinal (0=HEALTHY, 1=DEGRADED, 2=FAILOVER_INITIATED, 3=FAILOVER_ACTIVE, 4=RECOVERING)")
             .register(registry);
    }

    // ── Scheduled health checks ────────────────────────────────────────────────

    /**
     * Polls all cloud health probes on a fixed interval and drives state transitions.
     * Interval is configured via {@code auroraforge.dr.health-check-interval-seconds}.
     */
    @Scheduled(fixedDelayString = "${auroraforge.dr.health-check-interval-seconds:30}000")
    public void runHealthChecks() {
        for (CloudHealthPort port : healthPorts) {
            CloudHealth health = port.probe();
            latestHealth.put(port.cloudProvider(), health);

            if (health.isDegraded()) {
                handleDegradedCloud(port.cloudProvider());
            } else {
                handleRecoveredCloud(port.cloudProvider());
            }
        }
    }

    private void handleDegradedCloud(String cloud) {
        degradedSince.putIfAbsent(cloud, Instant.now());
        long degradedForSeconds = Duration.between(degradedSince.get(cloud), Instant.now()).toSeconds();

        log.warn("Cloud degraded: cloud={} degradedForSeconds={} latencyMs={} errorRate={}%",
                cloud, degradedForSeconds,
                latestHealth.get(cloud).latencyMs(),
                String.format("%.1f", latestHealth.get(cloud).errorRatePercent()));

        if (degradedForSeconds >= drProps.degradedThresholdSeconds()
                && state.get() == DisasterRecoveryState.HEALTHY) {
            transitionTo(DisasterRecoveryState.DEGRADED, cloud);
        }
    }

    private void handleRecoveredCloud(String cloud) {
        boolean wasTracked = degradedSince.remove(cloud) != null;
        if (wasTracked && state.get() == DisasterRecoveryState.FAILOVER_ACTIVE) {
            log.info("Degraded cloud is back online: cloud={} — initiating RECOVERING", cloud);
            transitionTo(DisasterRecoveryState.RECOVERING, cloud);
            log.info("DLQ back-fill will run on next DlqRetryScheduler cycle for cloud={}", cloud);
        }
    }

    // ── Manual operations (invoked by the REST API) ───────────────────────────

    /**
     * Triggers a failover to the specified target cloud.
     * Idempotent: subsequent calls while already in failover are ignored.
     *
     * @param targetCloud the cloud to route writes to (e.g. "azure")
     */
    public synchronized void triggerFailover(String targetCloud) {
        DisasterRecoveryState current = state.get();
        if (current.isFailoverActive()) {
            log.warn("Failover already active (state={}) — ignoring redundant trigger", current);
            return;
        }
        log.warn("MANUAL FAILOVER triggered: targetCloud={}", targetCloud);
        transitionTo(DisasterRecoveryState.FAILOVER_INITIATED, targetCloud);
        commitFailover(targetCloud);
    }

    /**
     * Marks recovery as complete after the operator confirms back-fill is done.
     * Transitions RECOVERING → HEALTHY.
     */
    public synchronized void completeRecovery() {
        if (state.get() != DisasterRecoveryState.RECOVERING) {
            throw new IllegalStateException(
                    "Cannot complete recovery: current state is " + state.get());
        }
        transitionTo(DisasterRecoveryState.HEALTHY, activeCloud);
        failoverActiveSince = null;
        log.info("Recovery complete — system HEALTHY. activeCloud={}", activeCloud);
    }

    // ── State machine internals ───────────────────────────────────────────────

    private synchronized void transitionTo(DisasterRecoveryState next, String cloudContext) {
        DisasterRecoveryState prev = state.getAndSet(next);
        log.info("DR state: {} → {} (cloud={})", prev, next, cloudContext);

        if (next == DisasterRecoveryState.FAILOVER_ACTIVE) {
            failoverActiveSince = Instant.now();
            activeCloud         = cloudContext;
        }
    }

    private void commitFailover(String targetCloud) {
        // In production: update a shared routing record (Route53 weighted policy, Azure Traffic
        // Manager, or a Redis key read by load balancers) before entering FAILOVER_ACTIVE.
        // Here we update local state and emit a structured log for the operator to act on.
        activeCloud = targetCloud;
        transitionTo(DisasterRecoveryState.FAILOVER_ACTIVE, targetCloud);
        log.warn("FAILOVER COMMITTED — all writes routing to cloud={}", targetCloud);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public DisasterRecoveryState getState() {
        return state.get();
    }

    /** Returns a snapshot of the most recent health check results per cloud. */
    public Map<String, CloudHealth> getLatestHealth() {
        return Map.copyOf(latestHealth);
    }

    public boolean isCloudHealthy(String cloudProvider) {
        CloudHealth h = latestHealth.get(cloudProvider);
        return h != null && !h.isDegraded();
    }
}
