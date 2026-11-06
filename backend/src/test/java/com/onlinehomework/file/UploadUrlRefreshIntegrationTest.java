package com.onlinehomework.file;

import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.api.UploadDtos.UploadUrlResponse;
import com.onlinehomework.file.application.UploadService;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "app.storage.upload-url-ttl=PT0.35S")
@ActiveProfiles("test")
@Import(UploadUrlRefreshIntegrationTest.TestStorageConfiguration.class)
class UploadUrlRefreshIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadIntentRepository intents;
    @Autowired UploadService uploads;
    @Autowired ExpiringTestStorage storage;

    @Test
    void refreshAfterRealUrlExpiryReturnsFreshUrlAndDoesNotMutateOrRecreateIntent() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String key = "real-url-expiry-" + suffix;
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);

        try {
            UploadIntentResponse created = uploads.createSubmissionIntent(studentId, assignmentId, key,
                    new CreateUploadRequest("answer.pdf", "application/pdf", 1024, "timed upload"));
            assertEquals(UploadStatus.PENDING, created.status());
            assertEquals(0, storage.signatureCount(), "creating an intent must not sign a URL");
            UploadIntent before = intents.findById(created.uploadId()).orElseThrow();
            Long versionBefore = jdbc.queryForObject("select version from upload_intents where id = ?", Long.class,
                    created.uploadId());

            UploadUrlResponse firstUrl = uploads.issueUploadUrl(studentId, "STUDENT", created.uploadId());
            assertTrue(firstUrl.expiresAt().isAfter(Instant.now()));
            assertEquals(1, storage.signatureCount());

            long waitMillis = Math.max(1, Duration.between(Instant.now(), firstUrl.expiresAt()).toMillis() + 60);
            Thread.sleep(waitMillis);
            assertTrue(Instant.now().isAfter(firstUrl.expiresAt()),
                    "the first signed URL must have actually expired before refreshing it");

            UploadUrlResponse refreshedUrl = uploads.issueUploadUrl(studentId, "STUDENT", created.uploadId());
            assertNotEquals(firstUrl.uploadUrl(), refreshedUrl.uploadUrl());
            assertTrue(refreshedUrl.expiresAt().isAfter(firstUrl.expiresAt()));
            assertEquals(2, storage.signatureCount());

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
        } finally {
            deleteFixtures(studentId, teacherId, classroomId, assignmentId);
        }
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {
        @Bean
        @Primary
        ExpiringTestStorage expiringTestStorage() { return new ExpiringTestStorage(); }
    }

    static class ExpiringTestStorage implements PrivateObjectStorage {
        private final AtomicInteger signatureCount = new AtomicInteger();

        int signatureCount() { return signatureCount.get(); }

        @Override
        public void assertAvailable() { }

        @Override
        public SignedUpload createUploadUrl(String objectKey, String contentType, long sizeBytes,
                                            String uploadIntentId, Duration ttl) {
            int sequence = signatureCount.incrementAndGet();
            return new SignedUpload("https://storage.invalid/upload/" + uploadIntentId + "/" + sequence,
                    Map.of("If-None-Match", "*"));
        }

        @Override
        public StoredObject inspect(String objectKey, String expectedUploadId) {
            throw new UnsupportedOperationException("The expiry test does not upload an object.");
        }

        @Override
        public String createDownloadUrl(String objectKey, String objectVersionId, Duration ttl) {
            throw new UnsupportedOperationException("The expiry test does not download an object.");
        }
    }
}
