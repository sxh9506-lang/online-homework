package com.onlinehomework.file.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class UploadDtos {
    private UploadDtos() { }

    public record CreateUploadRequest(
            @NotBlank @Size(max = 512) String fileName,
            @NotBlank @Size(max = 128) String contentType,
            @Positive long sizeBytes,
            @Size(max = 10000) String text) { }

    public record UploadIntentResponse(
            UUID uploadId,
            String status,
            String uploadUrl,
            Map<String, String> requiredHeaders,
            Instant expiresAt,
            UUID submissionId,
            Integer version,
            Boolean isLate,
            UUID assignmentId) { }

    public record CompleteUploadResponse(
            UUID uploadId,
            String status,
            UUID submissionId,
            Integer version,
            Boolean isLate,
            UUID assignmentId,
            Instant completedAt) { }

    public record DownloadLinkResponse(String url, Instant expiresAt) { }
}
