package com.onlinehomework.classroom.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ClassroomDtos {
    private ClassroomDtos() { }

    public record CreateClassRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 64) String timeZone) { }

    public record AddMemberRequest(@NotBlank @Email @Size(max = 320) String email) { }

    public record ClassResponse(UUID id, String name, String description, String timeZone, Instant createdAt) { }

    public record MemberResponse(UUID userId, String email, String displayName, Instant addedAt) { }
}
