package io.auroraforge.auth.presentation;

import io.auroraforge.auth.application.dto.LoginRequest;
import io.auroraforge.auth.application.dto.RefreshRequest;
import io.auroraforge.auth.application.dto.TokenResponse;
import io.auroraforge.auth.application.service.AuthenticationService;
import io.auroraforge.auth.infrastructure.ratelimit.TenantRateLimiter;
import io.auroraforge.core.domain.security.TenantPrincipal;
import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;

/**
 * Auth server REST endpoints.
 *
 * Only activated when {@code auroraforge.auth.server.enabled=true} (default: true).
 * When consuming services (ingestion, keymgmt) include auroraforge-auth as a library,
 * they set this property to {@code false} — the JWT resource server filter still runs
 * but these endpoints are not registered.
 *
 * <pre>
 *   POST /auth/token    → issue access + refresh token
 *   POST /auth/refresh  → exchange refresh token for new access token
 *   POST /auth/logout   → revoke access token via Redis blacklist
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auroraforge.auth.server.enabled", havingValue = "true", matchIfMissing = true)
public class AuthController {

    private final AuthenticationService authService;
    private final TenantRateLimiter     rateLimiter;

    // ── POST /auth/token ─────────────────────────────────────────────────────

    @PostMapping("/token")
    @Timed(value = "auroraforge.auth.token", description = "Time to issue an access token")
    public ResponseEntity<?> token(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        // Secondary rate-limit on the login endpoint itself (prevents brute-force)
        if (!rateLimiter.tryConsume("login:" + request.tenantId())) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(problem(429, "Too Many Requests",
                                  "Login rate limit exceeded. Please retry after 60 seconds.",
                                  httpRequest.getRequestURI()));
        }

        try {
            TokenResponse response = authService.login(request);
            log.info("Token issued: sub={} tenant={}", request.username(), request.tenantId());
            return ResponseEntity.ok(response);

        } catch (LockedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(problem(403, "Account Locked", e.getMessage(), httpRequest.getRequestURI()));

        } catch (BadCredentialsException e) {
            // Intentionally vague — don't leak whether username or password was wrong
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(problem(401, "Unauthorized", "Invalid credentials.", httpRequest.getRequestURI()));
        }
    }

    // ── POST /auth/refresh ───────────────────────────────────────────────────

    @PostMapping("/refresh")
    @Timed(value = "auroraforge.auth.refresh")
    public ResponseEntity<?> refresh(
            @Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest) {

        try {
            TokenResponse response = authService.refresh(request);
            return ResponseEntity.ok(response);

        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(problem(401, "Unauthorized", "Refresh token is invalid or expired.",
                                  httpRequest.getRequestURI()));
        }
    }

    // ── POST /auth/logout ────────────────────────────────────────────────────

    @PostMapping("/logout")
    @Timed(value = "auroraforge.auth.logout")
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal TenantPrincipal principal,
            HttpServletRequest httpRequest) {

        String bearerToken = extractBearerToken(httpRequest);
        if (bearerToken != null) {
            authService.logout(bearerToken);
            log.info("Logout: sub={} jti={}", principal.getName(), principal.jti());
        }
        return ResponseEntity.noContent().build();
    }

    // ── Exception handler ────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("Unexpected error in auth controller: {}", e.getMessage(), e);
        return ResponseEntity.status(500)
                .body(problem(500, "Internal Server Error",
                              "An unexpected error occurred.", req.getRequestURI()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ProblemDetail problem(int status, String title, String detail, String path) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(detail);
        pd.setType(URI.create("https://auroraforge.io/errors/" + title.toLowerCase().replace(' ', '-')));
        pd.setProperty("timestamp", Instant.now());
        pd.setProperty("path", path);
        return pd;
    }

    private static String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
