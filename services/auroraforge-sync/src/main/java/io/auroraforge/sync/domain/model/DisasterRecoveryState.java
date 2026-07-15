package io.auroraforge.sync.domain.model;

/**
 * DR state machine lifecycle:
 *
 *  HEALTHY ──[cloud degraded ≥ threshold]──► DEGRADED ──[trigger]──► FAILOVER_INITIATED
 *                                                                            │
 *          RECOVERING ◄──[DR ops committed]── FAILOVER_ACTIVE ◄─────────────┘
 *              │
 *          HEALTHY ◄──[back-fill complete, health checks pass]
 */
public enum DisasterRecoveryState {

    /**
     * Both clouds healthy; normal active-active replication.
     */
    HEALTHY,

    /**
     * One cloud degraded (high error rate / latency); sync continues to the healthy cloud only.
     * Entered when a cloud's health check has failed for ≥ {@code dr.degraded-threshold-seconds}.
     */
    DEGRADED,

    /**
     * Failover decision made; routing update in progress.
     * Short-lived (seconds); writes are paused to prevent split-brain.
     */
    FAILOVER_INITIATED,

    /**
     * Primary workload on single (DR) cloud; degraded cloud is offline.
     * RPO / RTO clock starts when this state is entered.
     */
    FAILOVER_ACTIVE,

    /**
     * Degraded cloud recovered; re-syncing missed writes before rejoining active-active.
     * Transitions to HEALTHY once back-fill completes.
     */
    RECOVERING;

    public boolean isFailoverActive() {
        return this == FAILOVER_ACTIVE || this == FAILOVER_INITIATED;
    }

    public boolean isNominal() {
        return this == HEALTHY;
    }
}
