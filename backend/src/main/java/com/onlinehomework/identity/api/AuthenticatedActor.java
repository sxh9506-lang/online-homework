package com.onlinehomework.identity.api;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public record AuthenticatedActor(UUID userId, String role, String email) {
    public static AuthenticatedActor from(Jwt jwt) {
        return new AuthenticatedActor(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"),
                jwt.getClaimAsString("email"));
    }
}
