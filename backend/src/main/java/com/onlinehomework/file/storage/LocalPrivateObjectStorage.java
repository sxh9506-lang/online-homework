package com.onlinehomework.file.storage;

import com.onlinehomework.common.api.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Disabled storage adapter. It deliberately never returns a local or fake file URL. */
@Component
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "false")
public class LocalPrivateObjectStorage implements PrivateObjectStorage {
    @Override
    public void assertAvailable() {
        throw unavailable();
    }

    @Override
    public SignedUpload createUploadUrl(String objectKey, String contentType, long sizeBytes,
                                        String uploadIntentId, Duration ttl) {
        throw unavailable();
    }

    @Override
    public StoredObject inspect(String objectKey, String expectedUploadId) {
        throw unavailable();
    }

    @Override
    public String createDownloadUrl(String objectKey, String objectVersionId, Duration ttl) {
        throw unavailable();
    }

    private static DomainException unavailable() {
        return new DomainException("ATTACHMENTS_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "Private attachments are disabled or not configured.", true);
    }
}
