package io.auroraforge.auth;

import io.auroraforge.auth.application.service.AuroraForgeUserDetailsService;
import io.auroraforge.auth.application.service.AuthenticationService;
import io.auroraforge.auth.infrastructure.config.AuthServerProperties;
import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.auth.infrastructure.config.SecurityProperties;
import io.auroraforge.auth.infrastructure.jwt.JwtAuthenticationConverter;
import io.auroraforge.auth.infrastructure.jwt.JwtTokenProvider;
import io.auroraforge.auth.infrastructure.jwt.RsaKeyProvider;
import io.auroraforge.auth.infrastructure.ratelimit.TenantRateLimiter;
import io.auroraforge.auth.infrastructure.redis.TokenBlacklistService;
import io.auroraforge.auth.infrastructure.security.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring Boot auto-configuration that activates all AuroraForge security infrastructure.
 *
 * Consuming modules (auroraforge-ingestion, auroraforge-keymgmt) declare
 * {@code auroraforge-auth} as a Maven dependency.  This auto-configuration then
 * fires automatically during their Spring context startup, wiring:
 *
 * <ul>
 *   <li>{@link RsaKeyProvider}         — loads RSA key pair from config/cloud</li>
 *   <li>{@link JwtTokenProvider}       — signs and validates RS256 JWTs</li>
 *   <li>{@link JwtAuthenticationConverter} — converts Jwt → TenantPrincipal</li>
 *   <li>{@link TokenBlacklistService}  — Redis-backed revocation</li>
 *   <li>{@link TenantRateLimiter}      — Bucket4j per-tenant rate limiter</li>
 *   <li>{@link AuroraForgeSecurityConfig} — SecurityFilterChain with route rules</li>
 *   <li>{@link PasswordEncoder}        — BCrypt with configured strength</li>
 * </ul>
 *
 * Auth-server endpoints ({@code /auth/**}, {@code /.well-known/jwks.json}) are only
 * registered when {@code auroraforge.auth.server.enabled=true} (default) via
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnProperty} on
 * the respective controllers.
 *
 * Registration: {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 */
@AutoConfiguration
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class, AuthServerProperties.class})
@ComponentScan(basePackages = "io.auroraforge.auth")
@EnableAsync
public class AuroraForgeSecurityAutoConfiguration {

    /**
     * BCrypt password encoder.  {@link ConditionalOnMissingBean} allows the host
     * service to override the strength if needed (e.g. faster bcrypt in tests).
     */
    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder(SecurityProperties secProps) {
        return new BCryptPasswordEncoder(secProps.bcryptStrength());
    }
}
