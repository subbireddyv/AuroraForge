package io.auroraforge.keymgmt.presentation.dto;

import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;

import java.time.Instant;

/**
 * Response body for manual rotation trigger endpoints.
 */
public record RotationResponse(
        String  status,
        String  tenantId,
        String  classification,
        String  previousVersion,
        String  newVersion,
        String  cloudProvider,
        Instant rotatedAt,
        String  message
) {
    public static RotationResponse success(String tenantId, String classification,
                                           RotatableKeyAdapter.RotationResult result) {
        return new RotationResponse(
                "SUCCESS", tenantId, classification,
                result.previousVersion(), result.newVersion(),
                result.cloudProvider(), result.rotatedAt(), null);
    }

    public static RotationResponse skipped(String tenantId, String classification,
                                           String reason) {
        return new RotationResponse(
                "SKIPPED", tenantId, classification,
                null, null, null, null, reason);
    }
}
