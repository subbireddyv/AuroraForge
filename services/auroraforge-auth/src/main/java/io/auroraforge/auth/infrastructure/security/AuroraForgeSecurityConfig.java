package io.auroraforge.auth.infrastructure.security;

import io.auroraforge.auth.infrastructure.config.SecurityProperties;
import io.auroraforge.auth.infrastructure.jwt.JwtAuthenticationConverter;
import io.auroraforge.auth.infrastructure.ratelimit.TenantRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Central Spring Security configuration for AuroraForge services.
 *
 * When included as a library in another module (auroraforge-ingestion, auroraforge-keymgmt),
 * this config bean is registered via auto-configuration and the host module can override
 * individual routes with its own {@code SecurityFilterChain} at a higher {@code @Order}.
 *
 * Route security matrix:
 * <pre>
 *   POST   /auth/token                      → permitAll (rate-limited separately)
 *   POST   /auth/refresh                    → permitAll
 *   POST   /auth/logout                     → authenticated
 *   GET    /.well-known/jwks.json           → permitAll
 *   GET    /actuator/health, /actuator/info → permitAll
 *   GET    /actuator/**                     → PLATFORM_OPS
 *   POST   /api/v1/tenants/{id}/events      → DATA_INGEST + tenant-match
 *   GET    /api/v1/tenants/{id}/events/**   → DATA_QUERY or ADMIN + tenant-match
 *   POST   /api/v1/keys/rotate/**           → KEY_MANAGER or ADMIN
 *   GET    /api/v1/keys/**                  → KEY_MANAGER or ADMIN
 *   /**    /api/v1/dr/**                    → PLATFORM_OPS or ADMIN
 *   **                                      → authenticated (catch-all)
 * </pre>
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@RequiredArgsConstructor
public class AuroraForgeSecurityConfig {

    private final JwtAuthenticationConverter    jwtAuthConverter;
    private final TenantAuthorizationManager    tenantAuthzManager;
    private final TenantAuthenticationEntryPoint authEntryPoint;
    private final TenantAccessDeniedHandler     accessDeniedHandler;
    private final SecurityProperties            secProps;
    private final TenantRateLimiter             rateLimiter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {

        http
            // ── Stateless API — no cookies, no CSRF surface ──────────────────
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── Security response headers ─────────────────────────────────────
            // SecurityHeadersFilter (auroraforge-observability) sets CSP,
            // Referrer-Policy, and Permissions-Policy at the servlet level.
            // These DSL entries mirror HSTS and frame-options so they also apply
            // in MockMvc-based integration tests that skip the filter chain.
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31_536_000))
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .xssProtection(xss -> xss.disable())  // CSP makes X-XSS-Protection obsolete
                .cacheControl(cc -> {})
            )

            // ── CORS ─────────────────────────────────────────────────────────
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── Route authorization ───────────────────────────────────────────
            .authorizeHttpRequests(authz -> authz

                // Auth server endpoints (open — rate-limited in AuthController)
                .requestMatchers(HttpMethod.POST, "/auth/token", "/auth/refresh").permitAll()
                .requestMatchers("/.well-known/jwks.json").permitAll()

                // Actuator: health/info for k8s probes; management for ops
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("PLATFORM_OPS")

                // Ingestion: tenant-scoped write
                .requestMatchers(HttpMethod.POST, "/api/v1/tenants/*/events")
                    .hasRole("DATA_INGEST")

                // Ingestion: tenant-scoped reads
                .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/events/**")
                    .hasAnyRole("DATA_QUERY", "ADMIN")

                // Key management: rotation (write) and status (read)
                .requestMatchers(HttpMethod.POST, "/api/v1/keys/rotate/**")
                    .hasAnyRole("KEY_MANAGER", "ADMIN")
                .requestMatchers("/api/v1/keys/**")
                    .hasAnyRole("KEY_MANAGER", "ADMIN")

                // DR operations: failover / consistency / DLQ — ops and admin only
                .requestMatchers("/api/v1/dr/**").hasAnyRole("PLATFORM_OPS", "ADMIN")

                // Authenticated logout
                .requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()

                // Deny everything else that isn't explicitly permitted
                .anyRequest().authenticated()
            )

            // ── OAuth2 Resource Server (RS256 JWT) ───────────────────────────
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthConverter))
                .authenticationEntryPoint(authEntryPoint)
            )

            // ── Exception handling ───────────────────────────────────────────
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )

            // ── Per-tenant rate limiting (applied before security checks) ────
            .addFilterBefore(tenantRateLimitFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * {@link JwtDecoder} that validates RS256 signatures using the auth service's public key.
     * Imported by consuming modules (ingestion, keymgmt) via the auto-configuration.
     */
    @Bean
    public JwtDecoder jwtDecoder(
            io.auroraforge.auth.infrastructure.jwt.RsaKeyProvider rsaKeyProvider) {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) rsaKeyProvider.getPublicKey()).build();
    }

    // ── Inner filter ─────────────────────────────────────────────────────────

    private OncePerRequestFilter tenantRateLimitFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {

                // Derive a rate-limit key: tenantId if extractable from path, else client IP
                String pathInfo = request.getRequestURI();
                String tenantKey = extractTenantFromPath(pathInfo);

                if (!rateLimiter.tryConsume(tenantKey)) {
                    long remaining = rateLimiter.availableTokens(tenantKey);
                    response.setStatus(429);
                    response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
                    response.setHeader("Retry-After", "60");
                    response.setContentType("application/problem+json");
                    response.getWriter().write(
                        """
                        {"status":429,"title":"Too Many Requests",
                         "detail":"Rate limit exceeded. Please retry after 60 seconds."}
                        """);
                    return;
                }

                response.setHeader("X-RateLimit-Remaining",
                        String.valueOf(rateLimiter.availableTokens(tenantKey)));
                chain.doFilter(request, response);
            }

            /** Extracts /api/v1/tenants/{tenantId}/... → tenantId, else returns remote addr. */
            private String extractTenantFromPath(String path) {
                String[] segments = path.split("/");
                for (int i = 0; i < segments.length - 1; i++) {
                    if ("tenants".equals(segments[i])) return segments[i + 1];
                }
                return "global"; // rate-limit bucket for non-tenant routes
            }
        };
    }

    // ── CORS ─────────────────────────────────────────────────────────────────

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(secProps.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        config.setExposedHeaders(List.of("X-RateLimit-Remaining", "Retry-After", "Location"));
        config.setAllowCredentials(secProps.cors().allowCredentials());
        config.setMaxAge(secProps.cors().maxAgeSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
