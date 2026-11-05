package com.onlinehomework.common.application;

import com.onlinehomework.common.api.FileMetadata;

import java.util.Optional;
import java.util.UUID;

public interface FileMetadataQuery {
    Optional<FileMetadata> findCompletedFile(UUID uploadIntentId);
}
