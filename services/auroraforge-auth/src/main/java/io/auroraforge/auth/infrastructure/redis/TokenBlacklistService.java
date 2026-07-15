package io.auroraforge.auth.infrastructure.redis;

import io.auroraforge.auth.infrastructure.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed JWT revocation list.
 *
 * Each revoked token's JTI is stored as a Redis key with a TTL matching the token's
 * remaining lifetime.  When the token would have expired anyway, the key disappears
 * from Redis automatically — no background cleanup job needed.
 *
 * Key format: {@code <keyPrefix><jti>}  (e.g. {@code af:jwt:bl:550e8400-e29b-41d4-a716-446655440000})
 * Value:      {@code "1"}  (presence of the key is all that matters)
 *
 * When Redis is unavailable ({@link SecurityProperties.TokenBlacklistConfig#enabled()} is false),
 * all operations are no-ops and {@link #isBlacklisted} always returns false.  This allows the
 * auth module to run in LOCAL mode without a Redis instance, accepting the trade-off that
 * logout does not take immediate effect.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private final SecurityProperties    secProps;
    private final StringRedisTemplate   redisTemplate;

    public TokenBlacklistService(
            SecurityProperties secProps,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            StringRedisTemplate redisTemplate) {
        this.secProps      = secProps;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Adds {@code jti} to the blacklist with the given TTL.
     * The caller should pass the token's remaining lifetime so the key expires naturally.
     *
     * @param jti unique JWT ID from the {@code jti} claim
     * @param ttl remaining lifetime of the token (key expires after this duration)
     */
    public void blacklist(String jti, Duration ttl) {
        if (!isEnabled()) {
            log.debug("Token blacklist disabled — logout for jti={} is best-effort only", jti);
            return;
        }
        String key = buildKey(jti);
        redisTemplate.opsForValue().set(key, "1", ttl);
        log.debug("Blacklisted token jti={} ttl={}", jti, ttl);
    }

    /**
     * Returns {@code true} if the given JTI has been revoked.
     *
     * Called on every authenticated request by {@link io.auroraforge.auth.infrastructure.jwt.JwtTokenProvider}.
     */
    public boolean isBlacklisted(String jti) {
        if (!isEnabled()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(jti)));
        } catch (Exception e) {
            // Redis failure open-circuits to "not blacklisted" — prefer availability over strict revocation.
            // In a PCI/HIPAA context, swap this to fail-closed.
            log.warn("Redis unavailable during blacklist check for jti={} — allowing token", jti, e);
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isEnabled() {
        return secProps.tokenBlacklist().enabled() && redisTemplate != null;
    }

    private String buildKey(String jti) {
        return secProps.tokenBlacklist().keyPrefix() + jti;
    }
}
