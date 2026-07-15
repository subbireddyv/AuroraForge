package io.auroraforge.auth.infrastructure.jwt;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.auth.infrastructure.redis.TokenBlacklistService;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.AuroraForgeRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Issues and validates RS256-signed JWTs.
 *
 * Token structure:
 * <pre>
 *   Header:  { "alg": "RS256", "kid": "<keyId>", "typ": "JWT" }
 *   Payload: {
 *     "iss":   "https://auth.auroraforge.io",
 *     "sub":   "<username>",
 *     "jti":   "<uuid>",
 *     "iat":   <epoch seconds>,
 *     "exp":   <epoch seconds>,
 *     "tid":   "<tenantId>",
 *     "roles": ["DATA_INGEST", "DATA_QUERY"],
 *     "cls":   ["PUBLIC", "INTERNAL", "CONFIDENTIAL"],
 *     "typ":   "access" | "refresh"
 *   }
 * </pre>
 *
 * Claim names are short to keep token size compact; they match the converter in
 * {@link JwtAuthenticationConverter}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    // Custom claim keys — kept short to minimise token bytes
    public static final String CLAIM_TENANT_ID       = "tid";
    public static final String CLAIM_ROLES           = "roles";
    public static final String CLAIM_CLASSIFICATIONS = "cls";
    public static final String CLAIM_TOKEN_TYPE      = "typ";
    public static final String TOKEN_TYPE_ACCESS     = "access";
    public static final String TOKEN_TYPE_REFRESH    = "refresh";

    private final JwtProperties         jwtProps;
    private final RsaKeyProvider        keyProvider;
    private final TokenBlacklistService blacklist;

    // ── Token issuance ───────────────────────────────────────────────────────

    /**
     * Issues a short-lived access token bound to the given tenant principal.
     *
     * @param subject                unique username or service-account-id
     * @param tenantId               tenant the principal belongs to
     * @param roles                  granted roles (encoded as bare enum names, no "ROLE_" prefix)
     * @param allowedClassifications data classification levels this token may touch
     */
    public String issueAccessToken(
            String subject,
            String tenantId,
            Set<AuroraForgeRole> roles,
            Set<DataClassification> allowedClassifications) {

        Instant now = Instant.now();
        return buildAndSign(subject, tenantId, roles, allowedClassifications,
                now, now.plusSeconds(jwtProps.accessTokenExpirySeconds()), TOKEN_TYPE_ACCESS);
    }

    /**
     * Issues a long-lived refresh token.  Refresh tokens carry minimal claims —
     * they are only used to obtain a new access token, never for resource access.
     */
    public String issueRefreshToken(String subject, String tenantId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(jwtProps.issuer())
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(jwtProps.refreshTokenExpirySeconds())))
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
                .build();

        return sign(claims);
    }

    // ── Token validation ─────────────────────────────────────────────────────

    /**
     * Validates an access token and returns the parsed claims.
     *
     * @throws BadCredentialsException on any validation failure (expired, tampered, blacklisted)
     */
    public JWTClaimsSet validateAccessToken(String tokenValue) {
        return validate(tokenValue, TOKEN_TYPE_ACCESS);
    }

    /** Validates a refresh token and returns its claims. */
    public JWTClaimsSet validateRefreshToken(String tokenValue) {
        return validate(tokenValue, TOKEN_TYPE_REFRESH);
    }

    /**
     * Returns the {@code jti} claim from a token without full validation.
     * Used during logout where we want to blacklist even a token that is about to expire.
     */
    public Optional<String> extractJti(String tokenValue) {
        try {
            SignedJWT jwt = SignedJWT.parse(tokenValue);
            return Optional.ofNullable(jwt.getJWTClaimsSet().getJWTID());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildAndSign(
            String subject, String tenantId,
            Set<AuroraForgeRole> roles, Set<DataClassification> classifications,
            Instant iat, Instant exp, String tokenType) {

        List<String> roleNames = roles.stream()
                .map(AuroraForgeRole::name)
                .collect(Collectors.toList());

        List<String> clsNames = classifications.stream()
                .map(DataClassification::name)
                .collect(Collectors.toList());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(jwtProps.issuer())
                .subject(subject)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLES, roleNames)
                .claim(CLAIM_CLASSIFICATIONS, clsNames)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .build();

        return sign(claims);
    }

    private String sign(JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(jwtProps.rsaKey().keyId())
                    .type(JOSEObjectType.JWT)
                    .build();

            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(keyProvider.getPrivateKey()));
            return jwt.serialize();

        } catch (JOSEException e) {
            throw new RuntimeException("JWT signing failed", e);
        }
    }

    private JWTClaimsSet validate(String tokenValue, String expectedTokenType) {
        try {
            SignedJWT jwt = SignedJWT.parse(tokenValue);

            // Signature check
            if (!jwt.verify(new RSASSAVerifier(keyProvider.getPublicKey()))) {
                throw new BadCredentialsException("JWT signature verification failed");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            // Expiry
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                throw new BadCredentialsException("JWT has expired");
            }

            // Issuer
            if (!jwtProps.issuer().equals(claims.getIssuer())) {
                throw new BadCredentialsException("JWT issuer mismatch");
            }

            // Token type (prevents refresh tokens from being used as access tokens)
            Object typ = claims.getClaim(CLAIM_TOKEN_TYPE);
            if (!expectedTokenType.equals(typ)) {
                throw new BadCredentialsException("Invalid token type: expected " + expectedTokenType);
            }

            // Redis blacklist check
            String jti = claims.getJWTID();
            if (jti != null && blacklist.isBlacklisted(jti)) {
                throw new BadCredentialsException("JWT has been revoked");
            }

            return claims;

        } catch (BadCredentialsException e) {
            throw e;
        } catch (Exception e) {
            throw new BadCredentialsException("JWT validation failed: " + e.getMessage(), e);
        }
    }
}
