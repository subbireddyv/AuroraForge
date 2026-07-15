package io.auroraforge.auth.infrastructure.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Cross-cutting security settings: CORS, rate limiting, token blacklist, and path rules.
 *
 * <pre>
 * auroraforge:
 *   security:
 *     cors:
 *       allowed-origins:
 *         - https://app.auroraforge.io
 *       allow-credentials: true
 *       max-age-seconds: 3600
 *     rate-limit:
 *       tokens-per-minute: 60
 *       burst-capacity: 10
 *     token-blacklist:
 *       enabled: true
 *       key-prefix: "af:jwt:bl:"
 *     bcrypt-strength: 12
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.security")
public record SecurityProperties(

        CorsConfig cors,
        RateLimitConfig rateLimit,
        TokenBlacklistConfig tokenBlacklist,

        /** BCrypt cost factor. Min 10 for production. */
        @Positive int bcryptStrength

) {
    public SecurityProperties {
        if (cors == null)           cors           = new CorsConfig(List.of("*"), false, 1800L);
        if (rateLimit == null)      rateLimit      = new RateLimitConfig(60, 10);
        if (tokenBlacklist == null) tokenBlacklist = new TokenBlacklistConfig(true, "af:jwt:bl:");
        if (bcryptStrength <= 0)    bcryptStrength = 12;
    }

    public record CorsConfig(
            List<String> allowedOrigins,
            boolean allowCredentials,
            long maxAgeSeconds
    ) {}

    public record RateLimitConfig(
            /** Sustained token refill rate (per minute, per tenant). */
            @Positive int tokensPerMinute,
            /** Maximum burst capacity above the sustained rate. */
            @Positive int burstCapacity
    ) {}

    public record TokenBlacklistConfig(
            /** Set to false to disable Redis blacklist check (LOCAL no-Redis mode). */
            boolean enabled,
            /** Redis key prefix for blacklist entries. */
            String keyPrefix
    ) {}
}
