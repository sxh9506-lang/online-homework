package com.onlinehomework.file.storage;

import java.time.Duration;
import java.util.Map;

public interface PrivateObjectStorage {
    void assertAvailable();

    SignedUpload createUploadUrl(String objectKey, String contentType, long sizeBytes,
                                 String uploadIntentId, Duration ttl);

    StoredObject inspect(String objectKey, String expectedUploadId);

    String createDownloadUrl(String objectKey, String objectVersionId, Duration ttl);

    record SignedUpload(String url, Map<String, String> requiredHeaders) { }

    record StoredObject(String versionId, long sizeBytes, String storageContentType,
                        String actualContentType, String sha256) { }
}
