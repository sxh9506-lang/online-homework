package com.onlinehomework.assignment.api;

import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.common.api.FileMetadata;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AssignmentDtos {
    private AssignmentDtos() { }

    public record CreateAssignmentRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String instructions,
            Instant dueAt,
            boolean closeAfterDeadline) { }

    public record UpdateAssignmentRequest(
            @Size(max = 200) String title,
            @Size(max = 10000) String instructions,
            Instant dueAt,
            Boolean closeAfterDeadline) { }

    public record AssignmentResponse(
            UUID id,
            UUID classId,
            String title,
            String instructions,
            AssignmentStatus status,
            Instant dueAt,
            boolean closeAfterDeadline,
            FileMetadata attachment,
            Instant createdAt,
            Instant publishedAt) { }
}
