package com.onlinehomework.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() { }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 120) String displayName) { }

    public record VerifyEmailRequest(@NotBlank @Size(max = 128) String token) { }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) { }

    public record RegisterResponse(UUID userId, String email, String role,
                                   boolean emailVerified, boolean verificationRequired) { }

    public record LoginResponse(String accessToken, Instant expiresAt, MeResponse user) { }

    public record MeResponse(UUID id, String email, String displayName, String role, boolean emailVerified) { }
}
