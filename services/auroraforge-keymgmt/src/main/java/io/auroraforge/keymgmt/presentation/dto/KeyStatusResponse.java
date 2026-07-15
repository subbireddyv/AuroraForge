package io.auroraforge.keymgmt.presentation.dto;

/**
 * Current key status for a given classification tier.
 * Returned by GET /api/v1/keys/status.
 */
public record KeyStatusResponse(
        String  classification,
        String  currentVersion,
        int     rotationDays,
        boolean requiresCmk,
        boolean requiresAuditLog,
        boolean rotationEnabled
) {}
