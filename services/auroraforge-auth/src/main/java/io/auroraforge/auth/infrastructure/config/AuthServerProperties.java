package io.auroraforge.auth.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Auth-server-specific settings (controls whether the token-issuance endpoints are active).
 *
 * When {@code enabled = false}, the module acts purely as a security library:
 * it configures the JWT decoder/filter for the host service but does NOT expose
 * {@code /auth/**} or {@code /.well-known/jwks.json}.
 *
 * <pre>
 * auroraforge:
 *   auth:
 *     server:
 *       enabled: true
 *       flyway-table: flyway_schema_history_auth
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.auth.server")
public record AuthServerProperties(
        /** Activate /auth/** endpoints and /.well-known/jwks.json. Default: true. */
        boolean enabled,

        /**
         * Flyway schema history table for the auth module — must differ from
         * the ingestion module's default to avoid collision when both run in the same DB.
         */
        String flywayTable
) {
    public AuthServerProperties {
        if (flywayTable == null) flywayTable = "flyway_schema_history_auth";
    }
}
