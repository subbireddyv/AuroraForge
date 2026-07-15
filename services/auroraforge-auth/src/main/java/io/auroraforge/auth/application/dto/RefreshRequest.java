package io.auroraforge.auth.application.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /auth/refresh}. */
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required")
        String refreshToken
) {}
