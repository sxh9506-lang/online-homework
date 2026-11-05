package com.onlinehomework.file.storage;

import com.onlinehomework.common.api.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only in-process adapter. It does not expose files over HTTP and is not suitable for production. */
@Component
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "false")
public class LocalPrivateObjectStorage implements PrivateObjectStorage {
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    @Override public SignedUpload createUploadUrl(String objectKey, String contentType, long sizeBytes, String uploadIntentId, Duration ttl) {
        return new SignedUpload("http://local-storage.invalid/private/" + uploadIntentId, Map.of("Content-Type", contentType));
    }
    @Override public StoredObject inspect(String objectKey, String expectedUploadId) {
        StoredObject value = objects.get(objectKey);
        if (value == null) throw new DomainException("UPLOAD_NOT_READY", org.springframework.http.HttpStatus.CONFLICT,
                "Uploaded object is not available yet. Retry completion after upload finishes.", true);
        return value;
    }
    @Override public String createDownloadUrl(String objectKey, String objectVersionId, Duration ttl) {
        if (!objects.containsKey(objectKey)) throw DomainException.notFound("FILE_NOT_FOUND", "File was not found.");
        return "http://local-storage.invalid/private-download/" + objectKey;
    }
    /** Test fixture hook; production callers cannot reach this adapter through REST. */
    public void put(String key, StoredObject object) { objects.put(key, object); }
}
