package io.auroraforge.auth.application.dto;

import java.time.Instant;

/**
 * Response body for {@code POST /auth/token} and {@code POST /auth/refresh}.
 *
 * OAuth2-compatible shape (RFC 6749 §4.1.4):
 * <pre>{@code
 * {
 *   "tokenType":          "Bearer",
 *   "accessToken":        "<jwt>",
 *   "refreshToken":       "<jwt>",
 *   "expiresIn":          900,
 *   "tenantId":           "acme-corp",
 *   "subject":            "alice",
 *   "issuedAt":           "2026-06-30T12:00:00Z"
 * }
 * }</pre>
 */
public record TokenResponse(
        String  tokenType,
        String  accessToken,
        String  refreshToken,
        long    expiresIn,
        String  tenantId,
        String  subject,
        Instant issuedAt
) {
    /** Factory used by {@link io.auroraforge.auth.application.service.AuthenticationService}. */
    public static TokenResponse issued(
            String accessToken,
            String refreshToken,
            long   expiresIn,
            String tenantId,
            String subject) {

        return new TokenResponse(
                "Bearer",
                accessToken,
                refreshToken,
                expiresIn,
                tenantId,
                subject,
                Instant.now()
        );
    }
}
