package io.auroraforge.auth.application.service;

import io.auroraforge.auth.application.dto.LoginRequest;
import io.auroraforge.auth.application.dto.RefreshRequest;
import io.auroraforge.auth.application.dto.TokenResponse;
import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.auth.infrastructure.jwt.JwtTokenProvider;
import io.auroraforge.auth.infrastructure.persistence.UserEntity;
import io.auroraforge.auth.infrastructure.persistence.UserRepository;
import io.auroraforge.auth.infrastructure.redis.TokenBlacklistService;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Orchestrates token issuance, refresh, and revocation.
 *
 * This service is the only entry point for creating JWT tokens — adapters and controllers
 * never call {@link JwtTokenProvider} directly.  This keeps the issuance logic
 * (password check → lock-out recording → token creation → metrics) in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository            userRepository;
    private final PasswordEncoder           passwordEncoder;
    private final JwtTokenProvider          jwtTokenProvider;
    private final TokenBlacklistService     blacklist;
    private final AuroraForgeUserDetailsService userDetailsService;
    private final JwtProperties             jwtProps;
    private final MeterRegistry             meterRegistry;

    // ── Token issuance ───────────────────────────────────────────────────────

    /**
     * Authenticates username/password and returns a fresh access + refresh token pair.
     *
     * @throws BadCredentialsException on wrong password
     * @throws LockedException         on locked account
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String username = request.username();

        UserEntity user = userRepository.findByUsernameAndTenantId(username, request.tenantId())
                .orElseThrow(() -> {
                    log.warn("Login failed — user not found: username={} tenant={}", username, request.tenantId());
                    return new BadCredentialsException("Invalid credentials");
                });

        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account disabled");
        }

        if (user.isLocked()) {
            log.warn("Login blocked — account locked: username={} lockedUntil={}", username, user.getLockedUntil());
            throw new LockedException("Account is temporarily locked. Try again after " + user.getLockedUntil());
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            userDetailsService.recordFailedLogin(username);
            meterRegistry.counter("auroraforge.auth.login.failed").increment();
            throw new BadCredentialsException("Invalid credentials");
        }

        // Success path
        userDetailsService.recordSuccessfulLogin(username);

        String accessToken  = jwtTokenProvider.issueAccessToken(
                user.getUsername(), user.getTenantId(),
                user.getRoles(), user.getAllowedClassifications());

        String refreshToken = jwtTokenProvider.issueRefreshToken(
                user.getUsername(), user.getTenantId());

        meterRegistry.counter("auroraforge.auth.login.success").increment();
        log.info("Login successful: username={} tenant={}", username, user.getTenantId());

        return TokenResponse.issued(
                accessToken, refreshToken,
                jwtProps.accessTokenExpirySeconds(),
                user.getTenantId(), user.getUsername());
    }

    /**
     * Validates a refresh token and returns a new access token.
     * The refresh token itself is NOT rotated here — callers must decide policy.
     *
     * @throws BadCredentialsException on invalid or expired refresh token
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request) {
        JWTClaimsSet claims = jwtTokenProvider.validateRefreshToken(request.refreshToken());

        String username = claims.getSubject();
        String tenantId = (String) claims.getClaim(JwtTokenProvider.CLAIM_TENANT_ID);

        UserEntity user = userRepository.findByUsernameAndTenantId(username, tenantId)
                .orElseThrow(() -> new BadCredentialsException("User not found during refresh"));

        if (!user.isEnabled() || user.isLocked()) {
            throw new BadCredentialsException("Account is disabled or locked");
        }

        String newAccessToken = jwtTokenProvider.issueAccessToken(
                user.getUsername(), user.getTenantId(),
                user.getRoles(), user.getAllowedClassifications());

        return TokenResponse.issued(
                newAccessToken, request.refreshToken(),
                jwtProps.accessTokenExpirySeconds(),
                user.getTenantId(), user.getUsername());
    }

    /**
     * Revokes the given access token by blacklisting its JTI in Redis.
     * The token's remaining TTL is used as the Redis key TTL.
     */
    public void logout(String accessToken) {
        jwtTokenProvider.extractJti(accessToken).ifPresent(jti -> {
            // Parse expiry to set an appropriate Redis TTL
            try {
                JWTClaimsSet claims  = jwtTokenProvider.validateAccessToken(accessToken);
                Date         exp     = claims.getExpirationTime();
                Duration     ttl     = exp != null
                        ? Duration.between(Instant.now(), exp.toInstant())
                        : Duration.ofSeconds(jwtProps.accessTokenExpirySeconds());

                if (!ttl.isNegative()) {
                    blacklist.blacklist(jti, ttl);
                }
            } catch (BadCredentialsException e) {
                // Token was already expired or invalid — blacklisting is a no-op
                log.debug("Logout for already-invalid token jti={}: {}", jti, e.getMessage());
            }
        });
    }
}
