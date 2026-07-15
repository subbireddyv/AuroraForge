package io.auroraforge.observability.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies defence-in-depth HTTP security response headers to every response.
 *
 * These complement Spring Security's built-in header support.  Spring Security's
 * {@code .headers()} DSL is also configured in AuroraForgeSecurityConfig to set
 * HSTS and default frame-options; this filter adds the headers that require
 * fine-tuned values or are not covered by the DSL (Permissions-Policy, CSP,
 * Referrer-Policy, Cache-Control).
 *
 * Header rationale:
 *
 *   Strict-Transport-Security  — forces HTTPS for 1 year, includes subdomains
 *   Content-Security-Policy    — default-src 'self'; blocks inline scripts (XSS)
 *   X-Frame-Options            — DENY prevents clickjacking
 *   X-Content-Type-Options     — nosniff prevents MIME confusion attacks
 *   Referrer-Policy            — no-referrer avoids leaking path info in headers
 *   Permissions-Policy         — disables browser features not used by APIs
 *   Cache-Control              — no-store on all API responses (no client caching
 *                                of potentially sensitive payloads)
 *   X-Request-ID               — echoed from MdcRequestContextFilter for tracing
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // just after MdcRequestContextFilter
public class SecurityHeadersFilter extends OncePerRequestFilter {

    // Actuator health/info probes don't need the full header set
    private static final String ACTUATOR_HEALTH = "/actuator/health";
    private static final String ACTUATOR_INFO   = "/actuator/info";

    private static final String CSP =
            "default-src 'self'; " +
            "script-src 'self'; " +
            "style-src 'self'; " +
            "img-src 'self' data:; " +
            "font-src 'self'; " +
            "object-src 'none'; " +
            "frame-ancestors 'none'; " +
            "base-uri 'self'; " +
            "form-action 'self'";

    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), " +
            "usb=(), fullscreen=(), display-capture=()";

    @Override
    protected void doFilterInternal(
            HttpServletRequest  request,
            HttpServletResponse response,
            FilterChain         chain) throws ServletException, IOException {

        // Always set transport and cache headers
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");

        // Full security headers for application endpoints
        if (!isActuatorProbe(request)) {
            response.setHeader("Content-Security-Policy",    CSP);
            response.setHeader("X-Frame-Options",            "DENY");
            response.setHeader("X-Content-Type-Options",     "nosniff");
            response.setHeader("Referrer-Policy",            "no-referrer");
            response.setHeader("Permissions-Policy",         PERMISSIONS_POLICY);
            response.setHeader("X-Permitted-Cross-Domain-Policies", "none");
        }

        chain.doFilter(request, response);
    }

    private boolean isActuatorProbe(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return ACTUATOR_HEALTH.equals(uri) || ACTUATOR_INFO.equals(uri);
    }
}
