package com.onlinehomework.file.application;

import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.application.SubmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql://.+")
class UploadServicePostgresIdempotencyIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadIntentRepository uploads;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void useCiPostgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("TEST_POSTGRES_USERNAME"));
        properties.add("spring.datasource.password", () -> System.getenv("TEST_POSTGRES_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void uniqueConstraintConflictRecoversCommittedIntentInANewTransaction() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID committedUploadId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String key = "postgres-unique-race-" + suffix;
        String requestText = "same payload";
        CreateUploadRequest request = new CreateUploadRequest("answer.pdf", "application/pdf", 1024, requestText);
        Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String keyHash = sha256(key.trim());
        String fingerprint = sha256("answer.pdf\napplication/pdf\n1024\n" + requestText);
        String objectKey = "homework/" + assignmentId + "/" + committedUploadId;
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);

        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        when(storage.createUploadUrl(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PrivateObjectStorage.SignedUpload(
                        "https://storage.invalid/private-upload", Map.of("If-None-Match", "*")));
        AssignmentService assignments = mock(AssignmentService.class);
        AtomicInteger contenderBackendPid = new AtomicInteger(-1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        when(assignments.lockForStudentSubmission(eq(studentId), eq(assignmentId))).thenAnswer(invocation -> {
            contenderBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
            contenderStarted.countDown();
            return new AssignmentService.AssignmentPolicy(assignmentId, classroomId, null,
                    false, AssignmentStatus.PUBLISHED);
        });
        UploadService service = new UploadService(uploads, assignments, mock(SubmissionService.class), storage,
                new FileRules(), Clock.fixed(createdAt, ZoneOffset.UTC), Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofMinutes(2), transactionManager);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Connection winningTransaction = null;
        boolean winningTransactionCommitted = false;
        try {
            winningTransaction = dataSource.getConnection();
            winningTransaction.setAutoCommit(false);
            insertUncommittedWinningIntent(winningTransaction, committedUploadId, studentId, assignmentId,
                    keyHash, fingerprint, objectKey, requestText, createdAt);

            Future<UploadIntentResponse> contender = executor.submit(
                    () -> service.createSubmissionIntent(studentId, assignmentId, key, request));
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS), "The competing service transaction must start.");
            int backendPid = contenderBackendPid.get();
            assertTrue(backendPid > 0, "The competing request must use a PostgreSQL backend.");
            assertTrue(awaitPostgresLockWait(backendPid, Duration.ofSeconds(10)),
                    "The competing INSERT must wait on the uncommitted unique-key winner.");

            winningTransaction.commit();
            winningTransactionCommitted = true;
            UploadIntentResponse recovered = contender.get(10, TimeUnit.SECONDS);

            assertEquals(committedUploadId, recovered.uploadId());
            assertEquals("https://storage.invalid/private-upload", recovered.uploadUrl());
            assertEquals(Map.of("If-None-Match", "*"), recovered.requiredHeaders());
            assertEquals(1L, jdbc.queryForObject("select count(*) from upload_intents "
                    + "where actor_id = ? and assignment_id = ? and purpose = 'SUBMISSION' "
                    + "and idempotency_key_hash = ?", Long.class, studentId, assignmentId, keyHash));
        } finally {
            if (winningTransaction != null) {
                if (!winningTransactionCommitted) winningTransaction.rollback();
                winningTransaction.close();
            }
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            deleteFixtures(studentId, teacherId, classroomId, assignmentId);
        }
    }

    private boolean awaitPostgresLockWait(int backendPid, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                    + "where pid = ? and wait_event_type = 'Lock'", Integer.class, backendPid);
            if (waiting != null && waiting > 0) return true;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private void insertFixtures(UUID teacherId, UUID studentId, UUID classroomId,
                                UUID assignmentId, String suffix) {
        jdbc.update("insert into user_accounts (id, email, normalized_email, password_hash, role, "
                        + "email_verified, enabled, display_name, created_at) "
                        + "values (?, ?, ?, 'test-hash', 'TEACHER', true, true, 'Postgres test teacher', now())",
                teacherId, "teacher-" + suffix + "@example.test", "teacher-" + suffix + "@example.test");
        jdbc.update("insert into user_accounts (id, email, normalized_email, password_hash, role, "
                        + "email_verified, enabled, display_name, created_at) "
                        + "values (?, ?, ?, 'test-hash', 'STUDENT', true, true, 'Postgres test student', now())",
                studentId, "student-" + suffix + "@example.test", "student-" + suffix + "@example.test");
        jdbc.update("insert into classrooms (id, teacher_id, name, description, time_zone, created_at, version) "
                        + "values (?, ?, 'Postgres idempotency test', null, 'Asia/Tokyo', now(), 0)",
                classroomId, teacherId);
        jdbc.update("insert into assignments (id, classroom_id, teacher_id, title, instructions, status, due_at, "
                        + "close_after_deadline, attachment_upload_id, created_at, published_at, version) "
                        + "values (?, ?, ?, 'Postgres idempotency test', null, 'PUBLISHED', null, false, null, now(), now(), 0)",
                assignmentId, classroomId, teacherId);
    }

    private static void insertUncommittedWinningIntent(Connection connection, UUID uploadId, UUID actorId,
                                                        UUID assignmentId, String keyHash, String fingerprint,
                                                        String objectKey, String requestText, Instant createdAt)
            throws Exception {
        String sql = "insert into upload_intents (id, actor_id, assignment_id, purpose, idempotency_key_hash, "
                + "request_fingerprint, object_key, file_name, declared_content_type, declared_size_bytes, "
                + "submission_text, status, expires_at, created_at, version) "
                + "values (?, ?, ?, 'SUBMISSION', ?, ?, ?, 'answer.pdf', 'application/pdf', 1024, ?, 'PENDING', ?, ?, 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, uploadId);
            statement.setObject(2, actorId);
            statement.setObject(3, assignmentId);
            statement.setString(4, keyHash);
            statement.setString(5, fingerprint);
            statement.setString(6, objectKey);
            statement.setString(7, requestText);
            statement.setTimestamp(8, Timestamp.from(createdAt.plus(Duration.ofMinutes(15))));
            statement.setTimestamp(9, Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private void deleteFixtures(UUID studentId, UUID teacherId, UUID classroomId, UUID assignmentId) {
        jdbc.update("delete from upload_intents where assignment_id = ?", assignmentId);
        jdbc.update("delete from assignments where id = ?", assignmentId);
        jdbc.update("delete from classrooms where id = ?", classroomId);
        jdbc.update("delete from user_accounts where id in (?, ?)", studentId, teacherId);
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
