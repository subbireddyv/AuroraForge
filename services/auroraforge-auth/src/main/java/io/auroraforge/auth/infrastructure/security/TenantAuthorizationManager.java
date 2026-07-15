package io.auroraforge.auth.infrastructure.security;

import io.auroraforge.core.domain.security.AuroraForgeRole;
import io.auroraforge.core.domain.security.TenantPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Custom {@link AuthorizationManager} that enforces tenant isolation on routes
 * of the form {@code /api/v1/tenants/{tenantId}/...}.
 *
 * Logic:
 * <ol>
 *   <li>ADMIN role bypasses the tenant check — required for platform-level operations.</li>
 *   <li>For all other roles, the JWT {@code tid} claim must exactly match the path variable
 *       {@code tenantId}.  A mismatch returns a DENY decision, which Spring Security
 *       translates to HTTP 403.</li>
 * </ol>
 *
 * Wired in {@link AuroraForgeSecurityConfig} via:
 * <pre>{@code
 *   .requestMatchers("/api/v1/tenants/{tenantId}/**").access(tenantAuthorizationManager)
 * }</pre>
 */
@Slf4j
@Component
public class TenantAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final AuthorizationDecision ALLOW = new AuthorizationDecision(true);
    private static final AuthorizationDecision DENY  = new AuthorizationDecision(false);

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authSupplier,
            RequestAuthorizationContext context) {

        Authentication auth = authSupplier.get();
        if (auth == null || !auth.isAuthenticated() || !(auth instanceof TenantPrincipal principal)) {
            return DENY;
        }

        // Platform admins can access any tenant's data
        if (principal.hasRole(AuroraForgeRole.ADMIN)) {
            return ALLOW;
        }

        // Extract {tenantId} path variable from the matched URI template
        String pathTenantId = extractTenantId(context.getRequest());
        if (pathTenantId == null) {
            // Route does not contain {tenantId} — not our concern
            return ALLOW;
        }

        boolean allowed = principal.tenantId().equals(pathTenantId);
        if (!allowed) {
            log.warn("Tenant isolation violation: sub={} jwtTenant={} pathTenant={}",
                     principal.getName(), principal.tenantId(), pathTenantId);
        }
        return allowed ? ALLOW : DENY;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractTenantId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attr instanceof Map<?, ?> variables) {
            Object value = ((Map<String, String>) variables).get("tenantId");
            return value != null ? value.toString() : null;
        }
        return null;
    }
}
