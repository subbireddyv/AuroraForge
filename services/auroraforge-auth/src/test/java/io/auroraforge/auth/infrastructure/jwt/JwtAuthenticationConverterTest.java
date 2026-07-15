package io.auroraforge.auth.infrastructure.jwt;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.AuroraForgeRole;
import io.auroraforge.core.domain.security.TenantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JwtAuthenticationConverter}.
 *
 * Jwt objects are built in-memory using {@link Jwt#withTokenValue} —
 * no actual signing required.
 */
@DisplayName("JwtAuthenticationConverter")
class JwtAuthenticationConverterTest {

    private JwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JwtAuthenticationConverter();
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("convert() — valid claims")
    class ValidClaimsTests {

        @Test
        @DisplayName("extracts tenantId, roles, and classifications from JWT claims")
        void extractsAllClaims() {
            Jwt jwt = buildJwt("alice", "acme-corp",
                    List.of("DATA_INGEST", "DATA_QUERY"),
                    List.of("PUBLIC", "CONFIDENTIAL"));

            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

            assertThat(principal).isNotNull();
            assertThat(principal.getName()).isEqualTo("alice");
            assertThat(principal.tenantId()).isEqualTo("acme-corp");
            assertThat(principal.roles()).containsExactlyInAnyOrder(
                    AuroraForgeRole.DATA_INGEST, AuroraForgeRole.DATA_QUERY);
            assertThat(principal.allowedClassifications()).containsExactlyInAnyOrder(
                    DataClassification.PUBLIC, DataClassification.CONFIDENTIAL);
        }

        @Test
        @DisplayName("grants correct Spring Security authorities (ROLE_ prefix)")
        void setsAuthoritiesWithRolePrefix() {
            Jwt jwt = buildJwt("bob", "tenant-x",
                    List.of("KEY_MANAGER"),
                    List.of("RESTRICTED"));

            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

            assertThat(principal.getAuthorities())
                    .extracting("authority")
                    .containsExactly("ROLE_KEY_MANAGER");
        }

        @Test
        @DisplayName("ADMIN role → hasRole(ADMIN) returns true")
        void adminRoleRecognised() {
            Jwt jwt = buildJwt("platform-admin", "platform",
                    List.of("ADMIN"),
                    List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"));

            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

            assertThat(principal.hasRole(AuroraForgeRole.ADMIN)).isTrue();
            assertThat(principal.canAccess(DataClassification.RESTRICTED)).isTrue();
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("convert() — edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("unknown role name is skipped without throwing")
        void unknownRoleSkipped() {
            Jwt jwt = buildJwt("user", "t", List.of("DATA_INGEST", "NONEXISTENT_ROLE"), List.of("PUBLIC"));
            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

            // Only the valid role is present
            assertThat(principal.roles()).containsExactly(AuroraForgeRole.DATA_INGEST);
        }

        @Test
        @DisplayName("missing 'tid' claim defaults tenantId to 'unknown'")
        void missingTidDefaultsToUnknown() {
            Jwt jwt = Jwt.withTokenValue("test-token")
                    .header("alg", "RS256")
                    .subject("user")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(900))
                    // No 'tid' claim
                    .claim(JwtTokenProvider.CLAIM_ROLES, List.of("DATA_QUERY"))
                    .claim(JwtTokenProvider.CLAIM_CLASSIFICATIONS, List.of("PUBLIC"))
                    .build();

            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

            assertThat(principal.tenantId()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("null roles claim produces empty roles set")
        void nullRolesClaimProducesEmpty() {
            Jwt jwt = buildJwt("user", "tenant", null, List.of("PUBLIC"));
            TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);
            assertThat(principal.roles()).isEmpty();
            assertThat(principal.getAuthorities()).isEmpty();
        }
    }

    // ── Domain guard helpers ──────────────────────────────────────────────

    @Test
    @DisplayName("assertTenantAccess throws for wrong tenant")
    void assertTenantAccessThrows() {
        Jwt jwt = buildJwt("alice", "acme", List.of("DATA_INGEST"), List.of("PUBLIC"));
        TenantPrincipal principal = (TenantPrincipal) converter.convert(jwt);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> principal.assertTenantAccess("other-tenant"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("acme")
                .hasMessageContaining("other-tenant");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Jwt buildJwt(String subject, String tenantId,
                         List<String> roles, List<String> classifications) {
        Jwt.Builder builder = Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .claim(JwtTokenProvider.CLAIM_TENANT_ID, tenantId)
                .claim(JwtTokenProvider.CLAIM_CLASSIFICATIONS, classifications);

        if (roles != null) {
            builder.claim(JwtTokenProvider.CLAIM_ROLES, roles);
        }
        return builder.build();
    }
}
