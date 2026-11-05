package com.onlinehomework.file.storage;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.application.FileContentInspector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.storage.enabled", havingValue = "true", matchIfMissing = true)
public class S3PrivateObjectStorage implements PrivateObjectStorage {
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final S3Client client;
    private final S3Presigner presigner;
    private final FileContentInspector inspector;
    private final String bucket;
    private final boolean requireVersioning;

    public S3PrivateObjectStorage(S3Client client, S3Presigner presigner, FileContentInspector inspector,
                                  @Value("${app.storage.bucket}") String bucket,
                                  @Value("${app.storage.require-versioning:false}") boolean requireVersioning) {
        this.client = client;
        this.presigner = presigner;
        this.inspector = inspector;
        this.bucket = bucket;
        this.requireVersioning = requireVersioning;
    }

    @Override
    public SignedUpload createUploadUrl(String objectKey, String contentType, long sizeBytes,
                                        String uploadIntentId, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(sizeBytes)
                .ifNoneMatch("*")
                .metadata(Map.of("upload-intent-id", uploadIntentId))
                .build();
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());
        return new SignedUpload(signed.url().toExternalForm(), Map.of(
                "Content-Type", contentType,
                "If-None-Match", "*",
                "x-amz-meta-upload-intent-id", uploadIntentId));
    }

    @Override
    public StoredObject inspect(String objectKey, String expectedUploadId) {
        HeadObjectResponse head;
        try {
            head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (S3Exception exception) {
            String code = exception.awsErrorDetails() == null ? "" : exception.awsErrorDetails().errorCode();
            if (exception.statusCode() == 404 || "NoSuchKey".equals(code)) {
                throw new DomainException("UPLOAD_NOT_READY", HttpStatus.CONFLICT,
                        "Uploaded object is not available yet. Retry completion after upload finishes.", true);
            }
            throw new DomainException("STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "Private file storage is temporarily unavailable.", true);
        }
        if (head.contentLength() <= 0) {
            throw DomainException.invalid("FILE_EMPTY", "Empty files are not accepted.");
        }
        if (head.contentLength() > MAX_BYTES) {
            throw DomainException.invalid("FILE_TOO_LARGE", "A file must be no larger than 20 MiB (20,971,520 bytes).");
        }
        String metadataUploadId = head.metadata().get("upload-intent-id");
        if (!expectedUploadId.equals(metadataUploadId)) {
            throw DomainException.invalid("FILE_CONTENT_MISMATCH", "Uploaded object metadata does not match this upload intent.");
        }
        String versionId = head.versionId();
        if (requireVersioning && (versionId == null || versionId.isBlank() || "null".equals(versionId))) {
            throw new DomainException("STORAGE_VERSIONING_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE,
                    "Private storage must have object versioning enabled.");
        }

        Path temp = null;
        try {
            temp = Files.createTempFile("homework-verify-", ".bin");
            GetObjectRequest.Builder getBuilder = GetObjectRequest.builder().bucket(bucket).key(objectKey);
            if (versionId != null && !versionId.isBlank() && !"null".equals(versionId)) {
                getBuilder.versionId(versionId);
            }
            try (ResponseInputStream<?> input = client.getObject(getBuilder.build());
                 var output = Files.newOutputStream(temp)) {
                long copied = 0;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    copied += n;
                    if (copied > MAX_BYTES) {
                        throw DomainException.invalid("FILE_TOO_LARGE", "A file must be no larger than 20 MiB (20,971,520 bytes).");
                    }
                    output.write(buffer, 0, n);
                }
                if (copied != head.contentLength()) {
                    throw DomainException.invalid("FILE_CONTENT_MISMATCH", "Uploaded content length is inconsistent.");
                }
            }
            String actualType = inspector.detect(temp);
            if (actualType == null) {
                throw DomainException.invalid("UNSUPPORTED_FILE_TYPE", "The uploaded file content is not a supported format.");
            }
            return new StoredObject(versionId, head.contentLength(), head.contentType(), actualType, sha256(temp));
        } catch (DomainException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new DomainException("STORAGE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "The uploaded object could not be inspected.", true);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The file is created in the OS temp directory and contains no durable application state.
                }
            }
        }
    }

    @Override
    public String createDownloadUrl(String objectKey, String objectVersionId, Duration ttl) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(objectKey);
        if (objectVersionId != null && !objectVersionId.isBlank()) get.versionId(objectVersionId);
        var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get.build())
                .build());
        return signed.url().toExternalForm();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
