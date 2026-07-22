package io.auroraforge.auth.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auroraforge.auth.application.dto.LoginRequest;
import io.auroraforge.auth.application.dto.RefreshRequest;
import io.auroraforge.auth.application.dto.TokenResponse;
import io.auroraforge.auth.application.service.AuthenticationService;
import io.auroraforge.auth.infrastructure.ratelimit.TenantRateLimiter;
import io.auroraforge.auth.infrastructure.security.AuroraForgeSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link AuthController} using {@link WebMvcTest}.
 *
 * Mocks {@link AuthenticationService} and {@link TenantRateLimiter} to isolate
 * controller logic, validation, and HTTP response shaping.
 * The actual JWT signing chain is not involved.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({AuroraForgeSecurityConfig.class})
// Binds SecurityProperties from (empty) test properties - record defaults kick in.
@EnableConfigurationProperties(io.auroraforge.auth.infrastructure.config.SecurityProperties.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc     mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthenticationService authService;
    @MockBean TenantRateLimiter     rateLimiter;

    // Infrastructure mocks required by security auto-config
    @MockBean io.auroraforge.auth.infrastructure.jwt.JwtAuthenticationConverter jwtConverter;
    @MockBean io.auroraforge.auth.infrastructure.jwt.RsaKeyProvider             rsaKeyProvider;
    @MockBean io.auroraforge.auth.infrastructure.redis.TokenBlacklistService    blacklistService;
    @MockBean io.auroraforge.auth.infrastructure.security.TenantAuthorizationManager tenantAuthzManager;
    @MockBean io.auroraforge.auth.infrastructure.security.TenantAuthenticationEntryPoint authEntryPoint;
    @MockBean io.auroraforge.auth.infrastructure.security.TenantAccessDeniedHandler accessDeniedHandler;

    private static final TokenResponse SAMPLE_TOKEN = new TokenResponse(
            "Bearer",
            "access.token.here",
            "refresh.token.here",
            900L,
            "acme-corp",
            "alice",
            Instant.now()
    );

    @BeforeEach
    void setUp() {
        // Allow all requests by default (rate limiter not under test here)
        when(rateLimiter.tryConsume(any())).thenReturn(true);
        when(rateLimiter.availableTokens(any())).thenReturn(59L);
    }

    // ── POST /auth/token ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/token")
    class TokenEndpointTests {

        @Test
        @DisplayName("returns 200 and TokenResponse on valid credentials")
        void validLoginReturns200() throws Exception {
            when(authService.login(any())).thenReturn(SAMPLE_TOKEN);

            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice", "secret", "acme-corp")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.accessToken").value("access.token.here"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.tenantId").value("acme-corp"));
        }

        @Test
        @DisplayName("returns 401 on bad credentials")
        void badCredentialsReturns401() throws Exception {
            when(authService.login(any())).thenThrow(new BadCredentialsException("Invalid credentials"));

            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice", "wrong-pass", "acme-corp")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));
        }

        @Test
        @DisplayName("returns 403 on locked account")
        void lockedAccountReturns403() throws Exception {
            when(authService.login(any())).thenThrow(new LockedException("Account locked until ..."));

            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice", "secret", "acme-corp")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Account Locked"));
        }

        @Test
        @DisplayName("returns 429 when rate limit is exceeded")
        void rateLimitReturns429() throws Exception {
            when(rateLimiter.tryConsume(any())).thenReturn(false);

            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice", "secret", "acme-corp")))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("returns 400 when request body is missing required fields")
        void missingFieldsReturns400() throws Exception {
            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("returns 400 when tenantId is blank")
        void blankTenantIdReturns400() throws Exception {
            mockMvc.perform(post("/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson("alice", "secret", "")))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── POST /auth/refresh ───────────────────────────────────────────────

    @Nested
    @DisplayName("POST /auth/refresh")
    class RefreshEndpointTests {

        @Test
        @DisplayName("returns 200 and new TokenResponse on valid refresh token")
        void validRefreshReturns200() throws Exception {
            when(authService.refresh(any())).thenReturn(SAMPLE_TOKEN);

            mockMvc.perform(post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"valid.refresh.token\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access.token.here"));
        }

        @Test
        @DisplayName("returns 401 on expired refresh token")
        void expiredRefreshReturns401() throws Exception {
            when(authService.refresh(any())).thenThrow(new BadCredentialsException("Refresh token expired"));

            mockMvc.perform(post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"expired.token\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("returns 400 when refreshToken is missing")
        void missingRefreshTokenReturns400() throws Exception {
            mockMvc.perform(post("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── POST /auth/logout ────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/logout returns 204 for authenticated user")
    @WithMockUser(username = "alice", roles = "DATA_INGEST")
    void logoutReturns204() throws Exception {
        mockMvc.perform(post("/auth/logout")
                .header("Authorization", "Bearer some.access.token")
                .with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String loginJson(String username, String password, String tenantId) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(username, password, tenantId));
    }
}
