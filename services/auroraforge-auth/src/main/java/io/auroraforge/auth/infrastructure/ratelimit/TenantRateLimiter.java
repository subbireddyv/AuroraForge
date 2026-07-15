package io.auroraforge.auth.infrastructure.ratelimit;

import com.bucket4j.Bandwidth;
import com.bucket4j.Bucket;
import io.auroraforge.auth.infrastructure.config.SecurityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tenant in-process rate limiter backed by Bucket4j.
 *
 * Each tenant gets its own independent token bucket so high-traffic tenants cannot
 * crowd out others.  The bucket is created lazily on first access and kept in a
 * {@link ConcurrentHashMap} — no external state store is needed (Bucket4j core).
 *
 * Rate parameters are driven by {@link SecurityProperties.RateLimitConfig}:
 * <ul>
 *   <li>{@code tokensPerMinute} — sustained refill rate (tokens added per 60 s)</li>
 *   <li>{@code burstCapacity}   — maximum bucket capacity (allows short bursts)</li>
 * </ul>
 *
 * Usage in filter / controller:
 * <pre>{@code
 *   if (!rateLimiter.tryConsume(tenantId)) {
 *       return ResponseEntity.status(429).build();
 *   }
 * }</pre>
 */
@Slf4j
@Component
public class TenantRateLimiter {

    private final SecurityProperties              secProps;
    private final MeterRegistry                   meterRegistry;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TenantRateLimiter(SecurityProperties secProps, MeterRegistry meterRegistry) {
        this.secProps      = secProps;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Attempts to consume one token from the tenant's bucket.
     *
     * @param tenantId the tenant to rate-limit
     * @return {@code true} if the request is allowed; {@code false} if the bucket is exhausted
     */
    public boolean tryConsume(String tenantId) {
        boolean allowed = getOrCreateBucket(tenantId).tryConsume(1);
        if (!allowed) {
            log.warn("Rate limit exceeded for tenantId={}", tenantId);
            meterRegistry.counter("auroraforge.security.rate_limit.exceeded",
                    Tags.of("tenant", tenantId)).increment();
        }
        return allowed;
    }

    /**
     * Returns the number of available tokens without consuming any.
     * Useful for returning a {@code X-RateLimit-Remaining} header.
     */
    public long availableTokens(String tenantId) {
        return getOrCreateBucket(tenantId).getAvailableTokens();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Bucket getOrCreateBucket(String tenantId) {
        return buckets.computeIfAbsent(tenantId, this::createBucket);
    }

    private Bucket createBucket(String tenantId) {
        SecurityProperties.RateLimitConfig cfg = secProps.rateLimit();

        Bandwidth limit = Bandwidth.builder()
                .capacity(cfg.burstCapacity())
                .refillGreedy(cfg.tokensPerMinute(), Duration.ofMinutes(1))
                .build();

        log.debug("Created rate-limit bucket for tenantId={} rate={}/min burst={}",
                  tenantId, cfg.tokensPerMinute(), cfg.burstCapacity());

        return Bucket.builder().addLimit(limit).build();
    }
}
