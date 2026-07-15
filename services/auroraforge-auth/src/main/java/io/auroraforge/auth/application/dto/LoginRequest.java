package io.auroraforge.auth.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/token}.
 *
 * Username/password flow (human users and service accounts alike).
 * Service accounts use their {@code clientId} as the username and
 * their hashed {@code clientSecret} as the password.
 */
public record LoginRequest(

        @NotBlank(message = "username is required")
        @Size(max = 128)
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 256)
        String password,

        /**
         * The tenant this principal belongs to.
         * Must match the {@code tenant_id} column on the auth_users row.
         * Use {@code "platform"} for ADMIN / PLATFORM_OPS accounts.
         */
        @NotBlank(message = "tenantId is required")
        @Size(max = 64)
        String tenantId
) {}
