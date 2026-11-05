package com.onlinehomework.common.api;

import java.util.UUID;

public record FileMetadata(UUID id, String fileName, String contentType, long sizeBytes) {
}
