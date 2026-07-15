package io.auroraforge.sync.presentation.dto;

import io.auroraforge.sync.domain.model.CloudHealth;
import io.auroraforge.sync.domain.model.DisasterRecoveryState;

import java.time.Instant;
import java.util.Map;

/**
 * Response body for GET /api/v1/dr/status.
 */
public record DrStatusResponse(
        DisasterRecoveryState state,
        String                activeCloud,
        Map<String, CloudHealth> cloudHealth,
        Map<String, Double>   replicationLagSeconds,
        Instant               failoverActiveSince,
        Instant               checkedAt
) {}
