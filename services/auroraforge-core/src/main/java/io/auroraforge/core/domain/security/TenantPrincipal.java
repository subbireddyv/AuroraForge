package io.auroraforge.core.domain.security;

import io.auroraforge.core.domain.model.DataClassification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Authenticated principal for AuroraForge — extends {@link JwtAuthenticationToken} so it
 * integrates natively with Spring Security's OAuth2 resource server filter chain.
 *
 * Injected into controllers via:
 * <pre>{@code
 *   public ResponseEntity<?> ingest(@AuthenticationPrincipal TenantPrincipal principal) { ... }
 * }</pre>
 *
 * JWT claim mapping:
 * <ul>
 *   <li>{@code sub}  → username / service-account-id</li>
 *   <li>{@code tid}  → tenantId</li>
 *   <li>{@code roles} → {@link AuroraForgeRole} list → {@link GrantedAuthority} list</li>
 *   <li>{@code cls}  → allowed {@link DataClassification} set (may be further restricted from role default)</li>
 *   <li>{@code jti}  → unique token ID for Redis-backed blacklisting</li>
 * </ul>
 */
public final class TenantPrincipal extends JwtAuthenticationToken {

    private final String                     tenantId;
    private final Set<AuroraForgeRole>       roles;
    private final Set<DataClassification>    allowedClassifications;

    public TenantPrincipal(
            Jwt jwt,
            Collection<? extends GrantedAuthority> authorities,
            String tenantId,
            Set<AuroraForgeRole> roles,
            Set<DataClassification> allowedClassifications) {

        super(jwt, authorities, jwt.getSubject());
        this.tenantId               = tenantId;
        this.roles                  = Collections.unmodifiableSet(Set.copyOf(roles));
        this.allowedClassifications = Collections.unmodifiableSet(Set.copyOf(allowedClassifications));
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String tenantId() { return tenantId; }

    public Set<AuroraForgeRole> roles() { return roles; }

    public Set<DataClassification> allowedClassifications() { return allowedClassifications; }

    /** Unique JWT ID from the {@code jti} claim — used for token blacklisting. */
    public String jti() {
        return getToken().getId();
    }

    // ── Domain guards ────────────────────────────────────────────────────────

    /**
     * Throws {@link AccessDeniedException} if the path-variable tenantId does not match
     * the tenant embedded in this JWT.  Call from controllers to enforce tenant isolation.
     *
     * <pre>{@code principal.assertTenantAccess(tenantId);}</pre>
     */
    public void assertTenantAccess(String requestedTenantId) {
        if (!tenantId.equals(requestedTenantId)) {
            throw new AccessDeniedException(
                    "Token issued for tenant '%s' cannot access tenant '%s'"
                    .formatted(tenantId, requestedTenantId));
        }
    }

    /**
     * Returns {@code true} when this principal's token grants access to the given
     * {@link DataClassification}.  Controllers should call this before processing
     * events with sensitive classifications.
     */
    public boolean canAccess(DataClassification classification) {
        return allowedClassifications.contains(classification);
    }

    public boolean hasRole(AuroraForgeRole role) {
        return roles.contains(role);
    }
}
