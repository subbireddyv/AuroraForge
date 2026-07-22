package io.auroraforge.auth.infrastructure.jwt;

import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.auth.infrastructure.config.SecurityProperties;
import io.auroraforge.auth.infrastructure.redis.TokenBlacklistService;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.AuroraForgeRole;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.BadCredentialsException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link JwtTokenProvider}.
 *
 * Uses an in-memory RSA key pair — no cloud dependencies.
 * Redis blacklist is mocked to control revocation behaviour.
 */
@ExtendWith(MockitoExtension.class)
// Shared setUp() stubs are not exercised by every test method (e.g. malformed-token
// tests never touch the key provider) - lenient avoids UnnecessaryStubbingException.
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    @Mock private TokenBlacklistService blacklist;

    private JwtTokenProvider   provider;
    private JwtProperties      jwtProps;
    private RsaKeyProvider     keyProvider;

    private static final String ISSUER    = "https://test.auroraforge.io";
    private static final String TENANT_ID = "acme-corp";
    private static final String SUBJECT   = "alice";

    private static RSAPublicKey  publicKey;
    private static RSAPrivateKey privateKey;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();
        publicKey  = (RSAPublicKey)  pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    @BeforeEach
    void setUp() {
        // Stub RsaKeyProvider without hitting Spring context or filesystem
        keyProvider = mock(RsaKeyProvider.class);
        when(keyProvider.getPrivateKey()).thenReturn(privateKey);
        when(keyProvider.getPublicKey()).thenReturn(publicKey);

        jwtProps = new JwtProperties(
                ISSUER,
                900L,
                86400L,
                new JwtProperties.RsaKeyConfig(null, null, null, null, "test-kid-1", false)
        );

        provider = new JwtTokenProvider(jwtProps, keyProvider, blacklist);

        // Default: nothing is blacklisted
        when(blacklist.isBlacklisted(anyString())).thenReturn(false);
    }

    // ── Access token issuance ─────────────────────────────────────────────

    @Nested
    @DisplayName("issueAccessToken()")
    class IssueAccessTokenTests {

        @Test
        @DisplayName("issued token contains expected claims")
        void tokenContainsExpectedClaims() throws Exception {
            String token = provider.issueAccessToken(
                    SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_INGEST),
                    Set.of(DataClassification.CONFIDENTIAL));

            JWTClaimsSet claims = provider.validateAccessToken(token);

            assertThat(claims.getSubject()).isEqualTo(SUBJECT);
            assertThat(claims.getIssuer()).isEqualTo(ISSUER);
            assertThat(claims.getClaim(JwtTokenProvider.CLAIM_TENANT_ID)).isEqualTo(TENANT_ID);
            assertThat(claims.getStringListClaim(JwtTokenProvider.CLAIM_ROLES))
                    .containsExactly("DATA_INGEST");
            assertThat(claims.getStringListClaim(JwtTokenProvider.CLAIM_CLASSIFICATIONS))
                    .containsExactly("CONFIDENTIAL");
            assertThat(claims.getClaim(JwtTokenProvider.CLAIM_TOKEN_TYPE))
                    .isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS);
            assertThat(claims.getJWTID()).isNotBlank();
        }

        @Test
        @DisplayName("each token has a unique jti")
        void uniqueJtiPerToken() {
            String t1 = provider.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_QUERY), Set.of(DataClassification.PUBLIC));
            String t2 = provider.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_QUERY), Set.of(DataClassification.PUBLIC));

            String jti1 = provider.extractJti(t1).orElseThrow();
            String jti2 = provider.extractJti(t2).orElseThrow();
            assertThat(jti1).isNotEqualTo(jti2);
        }

        @Test
        @DisplayName("token serialises all roles when multiple are granted")
        void multipleRolesEncoded() throws Exception {
            Set<AuroraForgeRole> roles = Set.of(AuroraForgeRole.DATA_INGEST, AuroraForgeRole.DATA_QUERY);
            String token = provider.issueAccessToken(SUBJECT, TENANT_ID, roles,
                    Set.of(DataClassification.PUBLIC));

            JWTClaimsSet claims = provider.validateAccessToken(token);
            assertThat(claims.getStringListClaim(JwtTokenProvider.CLAIM_ROLES))
                    .containsExactlyInAnyOrder("DATA_INGEST", "DATA_QUERY");
        }
    }

    // ── Refresh token ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("issueRefreshToken()")
    class IssueRefreshTokenTests {

        @Test
        @DisplayName("refresh token has token type 'refresh'")
        void refreshTokenType() throws Exception {
            String token = provider.issueRefreshToken(SUBJECT, TENANT_ID);
            JWTClaimsSet claims = provider.validateRefreshToken(token);
            assertThat(claims.getClaim(JwtTokenProvider.CLAIM_TOKEN_TYPE))
                    .isEqualTo(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        }

        @Test
        @DisplayName("refresh token rejected when used as access token")
        void refreshTokenNotAcceptedAsAccess() {
            String refreshToken = provider.issueRefreshToken(SUBJECT, TENANT_ID);
            assertThatThrownBy(() -> provider.validateAccessToken(refreshToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid token type");
        }

        @Test
        @DisplayName("access token rejected when used as refresh token")
        void accessTokenNotAcceptedAsRefresh() {
            String accessToken = provider.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_QUERY), Set.of(DataClassification.PUBLIC));
            assertThatThrownBy(() -> provider.validateRefreshToken(accessToken))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("Invalid token type");
        }
    }

    // ── Validation failures ───────────────────────────────────────────────

    @Nested
    @DisplayName("validateAccessToken() error cases")
    class ValidationErrorTests {

        @Test
        @DisplayName("tampered token (signature invalid) is rejected")
        void tamperedTokenRejected() {
            String token = provider.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_INGEST), Set.of(DataClassification.PUBLIC));

            // Corrupt the last byte of the signature
            String tampered = token.substring(0, token.length() - 4) + "XXXX";

            assertThatThrownBy(() -> provider.validateAccessToken(tampered))
                    .isInstanceOf(BadCredentialsException.class);
        }

        @Test
        @DisplayName("wrong issuer is rejected")
        void wrongIssuerRejected() {
            // Issue a token with a different issuer (different JwtTokenProvider instance)
            JwtProperties otherProps = new JwtProperties(
                    "https://evil.example.com", 900L, 86400L,
                    new JwtProperties.RsaKeyConfig(null, null, null, null, "test-kid-1", false));
            JwtTokenProvider other = new JwtTokenProvider(otherProps, keyProvider, blacklist);

            String token = other.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_QUERY), Set.of(DataClassification.PUBLIC));

            // Our provider should reject the wrong issuer
            assertThatThrownBy(() -> provider.validateAccessToken(token))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("issuer mismatch");
        }

        @Test
        @DisplayName("blacklisted token (revoked JTI) is rejected")
        void blacklistedTokenRejected() {
            String token = provider.issueAccessToken(SUBJECT, TENANT_ID,
                    Set.of(AuroraForgeRole.DATA_QUERY), Set.of(DataClassification.PUBLIC));
            String jti = provider.extractJti(token).orElseThrow();

            when(blacklist.isBlacklisted(jti)).thenReturn(true);

            assertThatThrownBy(() -> provider.validateAccessToken(token))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("garbage string is rejected gracefully")
        void garbageTokenRejected() {
            assertThatThrownBy(() -> provider.validateAccessToken("not.a.jwt"))
                    .isInstanceOf(BadCredentialsException.class);
        }
    }

    // ── extractJti ────────────────────────────────────────────────────────

    @Test
    @DisplayName("extractJti() returns empty for malformed token")
    void extractJtiEmptyForMalformed() {
        assertThat(provider.extractJti("garbage")).isEmpty();
    }

    @Test
    @DisplayName("extractJti() returns JTI without validating signature")
    void extractJtiWithoutValidation() {
        String token = provider.issueAccessToken(SUBJECT, TENANT_ID,
                Set.of(AuroraForgeRole.ADMIN), Set.of(DataClassification.RESTRICTED));
        assertThat(provider.extractJti(token)).isPresent();
    }
}
