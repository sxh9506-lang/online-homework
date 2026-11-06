package com.onlinehomework.file.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.onlinehomework.file.domain.UploadStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class UploadDtos {
    private UploadDtos() { }

    public record CreateUploadRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 128) String contentType,
            @Positive long sizeBytes,
            @Size(max = 10000) String text) { }

    public record UploadIntentResponse(UUID uploadId, UploadStatus status) { }

    public record UploadUrlResponse(String uploadUrl, Instant expiresAt,
                                    Map<String, String> requiredHeaders) { }

    public record DownloadLinkResponse(String url, Instant expiresAt) { }
}
