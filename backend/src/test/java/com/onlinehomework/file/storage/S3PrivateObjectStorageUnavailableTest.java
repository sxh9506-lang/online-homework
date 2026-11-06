package com.onlinehomework.file.storage;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.application.FileContentInspector;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class S3PrivateObjectStorageUnavailableTest {
    @Test
    void missingS3EndpointOrCredentialsNeverProducesPresignedOrFalseFileUrls() {
        PrivateObjectStorage storage = new S3PrivateObjectStorage(
                mock(S3Client.class), mock(S3Presigner.class), mock(FileContentInspector.class),
                "homework-private", "auto", "", "", "", "", false);

        assertUnavailable(() -> storage.createUploadUrl("private/key", "application/pdf", 1024,
                "upload-id", Duration.ofMinutes(2)));
        assertUnavailable(() -> storage.inspect("private/key", "upload-id"));
        assertUnavailable(() -> storage.createDownloadUrl("private/key", "version-id", Duration.ofMinutes(2)));
    }

    @Test
    void httpStorageOrPresignEndpointsNeverProducePresignedUrls() {
        PrivateObjectStorage httpStorage = new S3PrivateObjectStorage(
                mock(S3Client.class), mock(S3Presigner.class), mock(FileContentInspector.class),
                "homework-private", "auto", "http://objects.example.invalid", "https://signed.example.invalid",
                "access-key", "secret-key", false);
        PrivateObjectStorage httpPresign = new S3PrivateObjectStorage(
                mock(S3Client.class), mock(S3Presigner.class), mock(FileContentInspector.class),
                "homework-private", "auto", "https://objects.example.invalid", "http://signed.example.invalid",
                "access-key", "secret-key", false);

        assertUnavailable(() -> httpStorage.createUploadUrl("private/key", "application/pdf", 1024,
                "upload-id", Duration.ofMinutes(2)));
        assertUnavailable(() -> httpPresign.createDownloadUrl("private/key", "version-id", Duration.ofMinutes(2)));
    }

    private static void assertUnavailable(Runnable action) {
        DomainException exception = assertThrows(DomainException.class, action::run);
        assertEquals("ATTACHMENTS_UNAVAILABLE", exception.code());
        assertEquals(503, exception.status().value());
    }
}
