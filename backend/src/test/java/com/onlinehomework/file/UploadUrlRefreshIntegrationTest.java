package com.onlinehomework.file;

import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.api.UploadDtos.UploadUrlResponse;
import com.onlinehomework.file.application.FileContentInspector;
import com.onlinehomework.file.application.UploadService;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.S3PrivateObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Exercises production S3PrivateObjectStorage with the real AWS SDK S3Presigner,
 * test-only static credentials, and the reserved s3.invalid endpoint. It proves that
 * X-Amz-Expires reflects the configured TTL, the signed expiry passes after real
 * wall-clock time, and refresh creates a later-expiring signature. No S3 request is
 * sent; this does not prove provider-side rejection of an expired signature.
 */
@SpringBootTest(properties = "app.storage.upload-url-ttl=PT3S")
@ActiveProfiles("test")
@Import(UploadUrlRefreshIntegrationTest.TestStorageConfiguration.class)
class UploadUrlRefreshIntegrationTest {
    private static final long URL_TTL_SECONDS = 3;
    private static final DateTimeFormatter S3_SIGNING_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);

    @Autowired JdbcTemplate jdbc;
    @Autowired UploadIntentRepository intents;
    @Autowired UploadService uploads;
    @Autowired S3PrivateObjectStorage storage;
    @Autowired S3Client s3Client;

    @Test
    void productionS3PresignerExpiresAndRefreshesAfterRealElapsedTimeWithoutS3Calls() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String key = "real-s3-url-expiry-" + suffix;
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);

        try {
            UploadIntentResponse created = uploads.createSubmissionIntent(studentId, assignmentId, key,
                    new CreateUploadRequest("answer.pdf", "application/pdf", 1024, "timed upload"));
            assertEquals(UploadStatus.PENDING, created.status());
            UploadIntent before = intents.findById(created.uploadId()).orElseThrow();
            Long versionBefore = jdbc.queryForObject("select version from upload_intents where id = ?", Long.class,
                    created.uploadId());

            UploadUrlResponse firstUrl = uploads.issueUploadUrl(studentId, "STUDENT", created.uploadId());
            SignedUrlExpiry firstExpiry = signedExpiry(firstUrl.uploadUrl());
            assertEquals(URL_TTL_SECONDS, firstExpiry.ttlSeconds());
            assertEquals(firstExpiry.signedAt().plusSeconds(URL_TTL_SECONDS), firstExpiry.expiresAt());
            assertTrue(firstExpiry.expiresAt().isAfter(Instant.now()));
            assertExpiryMatchesResponse(firstExpiry, firstUrl);
            assertEquals("https", URI.create(firstUrl.uploadUrl()).getScheme());
            assertEquals("s3.invalid", URI.create(firstUrl.uploadUrl()).getHost());

            long waitMillis = Math.max(1,
                    Duration.between(Instant.now(), firstExpiry.expiresAt()).toMillis() + 150);
            Thread.sleep(waitMillis);
            assertFalse(firstExpiry.expiresAt().isAfter(Instant.now()),
                    "X-Amz-Date + X-Amz-Expires must be past after waiting in real time");

            UploadUrlResponse refreshedUrl = uploads.issueUploadUrl(studentId, "STUDENT", created.uploadId());
            SignedUrlExpiry refreshedExpiry = signedExpiry(refreshedUrl.uploadUrl());
            assertNotEquals(firstUrl.uploadUrl(), refreshedUrl.uploadUrl());
            assertEquals(URL_TTL_SECONDS, refreshedExpiry.ttlSeconds());
            assertTrue(refreshedExpiry.expiresAt().isAfter(Instant.now()));
            assertTrue(refreshedExpiry.expiresAt().isAfter(firstExpiry.expiresAt()));
            assertExpiryMatchesResponse(refreshedExpiry, refreshedUrl);

            UploadIntent after = intents.findById(created.uploadId()).orElseThrow();
            Long versionAfter = jdbc.queryForObject("select version from upload_intents where id = ?", Long.class,
                    created.uploadId());
            assertEquals(before.getId(), after.getId());
            assertEquals(before.getRequestFingerprint(), after.getRequestFingerprint());
            assertEquals(before.getStatus(), after.getStatus());
            assertEquals(UploadStatus.PENDING, after.getStatus());
            assertEquals(before.getCreatedAt(), after.getCreatedAt());
            assertEquals(before.getExpiresAt(), after.getExpiresAt());
            assertEquals(versionBefore, versionAfter, "URL refresh must not write or version-bump the intent");
            assertEquals(1L, jdbc.queryForObject("select count(*) from upload_intents where id = ?", Long.class,
                    created.uploadId()));
            verifyNoInteractions(s3Client);
        } finally {
            deleteFixtures(studentId, teacherId, classroomId, assignmentId);
        }
    }

    private static void assertExpiryMatchesResponse(SignedUrlExpiry signed, UploadUrlResponse response) {
        Duration difference = Duration.between(signed.expiresAt(), response.expiresAt()).abs();
        assertTrue(difference.compareTo(Duration.ofSeconds(1)) < 0,
                "response expiresAt must agree with the AWS SDK signed URL expiry within one second");
    }

    private static SignedUrlExpiry signedExpiry(String presignedUrl) {
        String rawQuery = URI.create(presignedUrl).getRawQuery();
        Map<String, String> parameters = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        Instant signedAt = LocalDateTime.parse(parameters.get("X-Amz-Date"), S3_SIGNING_TIME)
                .toInstant(ZoneOffset.UTC);
        long ttlSeconds = Long.parseLong(parameters.get("X-Amz-Expires"));
        return new SignedUrlExpiry(signedAt, signedAt.plusSeconds(ttlSeconds), ttlSeconds);
    }

    private void insertFixtures(UUID teacherId, UUID studentId, UUID classroomId,
                                UUID assignmentId, String suffix) {
        jdbc.update("insert into user_accounts (id, email, normalized_email, password_hash, role, "
                        + "email_verified, enabled, display_name, created_at) "
                        + "values (?, ?, ?, 'test-hash', 'TEACHER', true, true, 'Expiry test teacher', now())",
                teacherId, "expiry-teacher-" + suffix + "@example.test", "expiry-teacher-" + suffix + "@example.test");
        jdbc.update("insert into user_accounts (id, email, normalized_email, password_hash, role, "
                        + "email_verified, enabled, display_name, created_at) "
                        + "values (?, ?, ?, 'test-hash', 'STUDENT', true, true, 'Expiry test student', now())",
                studentId, "expiry-student-" + suffix + "@example.test", "expiry-student-" + suffix + "@example.test");
        jdbc.update("insert into classrooms (id, teacher_id, name, description, time_zone, created_at, version) "
                        + "values (?, ?, 'Upload URL expiry test', null, 'Asia/Tokyo', now(), 0)",
                classroomId, teacherId);
        jdbc.update("insert into class_memberships (id, classroom_id, student_id, added_at) values (?, ?, ?, now())",
                UUID.randomUUID(), classroomId, studentId);
        jdbc.update("insert into assignments (id, classroom_id, teacher_id, title, instructions, status, due_at, "
                        + "close_after_deadline, attachment_upload_id, created_at, published_at, version) "
                        + "values (?, ?, ?, 'Upload URL expiry test', null, 'PUBLISHED', null, false, null, now(), now(), 0)",
                assignmentId, classroomId, teacherId);
    }

    private void deleteFixtures(UUID studentId, UUID teacherId, UUID classroomId, UUID assignmentId) {
        jdbc.update("delete from upload_intents where assignment_id = ?", assignmentId);
        jdbc.update("delete from assignments where id = ?", assignmentId);
        jdbc.update("delete from class_memberships where classroom_id = ?", classroomId);
        jdbc.update("delete from classrooms where id = ?", classroomId);
        jdbc.update("delete from user_accounts where id in (?, ?)", studentId, teacherId);
    }

    private record SignedUrlExpiry(Instant signedAt, Instant expiresAt, long ttlSeconds) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {
        @Bean(destroyMethod = "close")
        S3Presigner testS3Presigner() {
            return S3Presigner.builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                    .endpointOverride(URI.create("https://s3.invalid"))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build();
        }

        @Bean
        S3Client testS3Client() { return mock(S3Client.class); }

        @Bean
        @Primary
        S3PrivateObjectStorage testS3PrivateObjectStorage(S3Client testS3Client,
                                                           S3Presigner testS3Presigner,
                                                           FileContentInspector inspector) {
            return new S3PrivateObjectStorage(testS3Client, testS3Presigner, inspector,
                    "test-homework-bucket", "us-east-1", "https://s3.invalid", "",
                    "test-access-key", "test-secret-key", true);
        }
    }
}
