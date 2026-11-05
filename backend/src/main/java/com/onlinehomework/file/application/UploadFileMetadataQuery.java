package com.onlinehomework.file.application;

import com.onlinehomework.common.api.FileMetadata;
import com.onlinehomework.common.application.FileMetadataQuery;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
public class UploadFileMetadataQuery implements FileMetadataQuery {
    private final UploadIntentRepository uploads;

    public UploadFileMetadataQuery(UploadIntentRepository uploads) {
        this.uploads = uploads;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileMetadata> findCompletedFile(UUID uploadIntentId) {
        return uploads.findById(uploadIntentId)
                .filter(upload -> upload.getStatus() == UploadStatus.COMPLETED)
                .map(this::metadata);
    }

    private FileMetadata metadata(UploadIntent upload) {
        return new FileMetadata(upload.getId(), upload.getFileName(), upload.getDeclaredContentType(),
                upload.getDeclaredSizeBytes());
    }
}
