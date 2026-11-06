package com.onlinehomework.file.application;

import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.application.SubmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = "jdbc:postgresql://.+")
@Import(UploadServicePostgresIdempotencyIntegrationTest.TestStorageConfiguration.class)
class UploadServicePostgresIdempotencyIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired UploadIntentRepository uploads;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UploadService applicationUploads;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;

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
            assertEquals(UploadStatus.PENDING, recovered.status());
            org.mockito.Mockito.verify(storage, never()).createUploadUrl(anyString(), anyString(), anyLong(),
                    anyString(), any());
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

    @Test
    void concurrentSamePayloadCreatesOneStableIntentThroughTheApplicationOnPostgres16() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String key = "postgres-app-concurrent-" + suffix;
        CreateUploadRequest request = new CreateUploadRequest("answer.pdf", "application/pdf", 1024, "same payload");
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var requestCall = (java.util.concurrent.Callable<UploadIntentResponse>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent application requests did not start together.");
                }
                return applicationUploads.createSubmissionIntent(studentId, assignmentId, key, request);
            };
            Future<UploadIntentResponse> first = executor.submit(requestCall);
            Future<UploadIntentResponse> second = executor.submit(requestCall);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            UploadIntentResponse firstResponse = first.get(20, TimeUnit.SECONDS);
            UploadIntentResponse secondResponse = second.get(20, TimeUnit.SECONDS);

            assertEquals(firstResponse, secondResponse);
            assertNotNull(firstResponse.uploadId());
            assertEquals(UploadStatus.PENDING, firstResponse.status());
            assertEquals(1L, countIntent(studentId, assignmentId, key));
            UploadIntent persisted = uploads.findById(firstResponse.uploadId()).orElseThrow();
            assertEquals(UploadStatus.PENDING, persisted.getStatus());
        } finally {
            start.countDown();
            executor.shutdownNow();
            deleteFixtures(studentId, teacherId, classroomId, assignmentId);
        }
    }

    @Test
    void differentPayloadWithSameKeyReturnsConflictWithoutMutatingPostgresIntent() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String key = "postgres-app-conflict-" + suffix;
        CreateUploadRequest firstRequest = new CreateUploadRequest("answer.pdf", "application/pdf", 1024, "original");
        CreateUploadRequest differentRequest = new CreateUploadRequest("answer.pdf", "application/pdf", 1024, "different");
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);

        try {
            UploadIntentResponse created = applicationUploads.createSubmissionIntent(
                    studentId, assignmentId, key, firstRequest);
            UploadIntent before = uploads.findById(created.uploadId()).orElseThrow();
            DomainException conflict = assertThrows(DomainException.class, () ->
                    applicationUploads.createSubmissionIntent(studentId, assignmentId, key, differentRequest));

            assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.code());
            assertEquals(org.springframework.http.HttpStatus.CONFLICT, conflict.status());
            assertEquals(1L, countIntent(studentId, assignmentId, key));
            UploadIntent after = uploads.findById(created.uploadId()).orElseThrow();
            assertEquals(before.getId(), after.getId());
            assertEquals(before.getRequestFingerprint(), after.getRequestFingerprint());
            assertEquals(before.getStatus(), after.getStatus());
            assertEquals(before.getCreatedAt(), after.getCreatedAt());
            assertEquals(before.getExpiresAt(), after.getExpiresAt());
        } finally {
            deleteFixtures(studentId, teacherId, classroomId, assignmentId);
        }
    }

    @Test
    void differentPayloadReturnsHttp409WithoutMutationThroughPostgres16Application() throws Exception {
        UUID teacherId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        UUID classroomId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String studentEmail = "student-" + suffix + "@example.test";
        String key = "postgres-http-conflict-" + suffix;
        insertFixtures(teacherId, studentId, classroomId, assignmentId, suffix);
        String bearer = bearer(studentId, studentEmail, "STUDENT");
        String originalBody = "{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\","
                + "\"sizeBytes\":1024,\"text\":\"original\"}";
        String differentBody = originalBody.replace("original", "different");

        try {
            MvcResult createdResult = mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                            .header("Authorization", bearer)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(originalBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.uploadUrl").doesNotExist())
                    .andExpect(jsonPath("$.expiresAt").doesNotExist())
                    .andReturn();
            UUID uploadId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
                    createdResult.getResponse().getContentAsString(), "$.uploadId"));
            UploadIntent before = uploads.findById(uploadId).orElseThrow();

            mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                            .header("Authorization", bearer)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(differentBody))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

            assertEquals(1L, countIntent(studentId, assignmentId, key));
            UploadIntent after = uploads.findById(uploadId).orElseThrow();
            assertEquals(before.getRequestFingerprint(), after.getRequestFingerprint());
            assertEquals(before.getStatus(), after.getStatus());
            assertEquals(before.getCreatedAt(), after.getCreatedAt());
            assertEquals(before.getExpiresAt(), after.getExpiresAt());
        } finally {
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
        jdbc.update("insert into class_memberships (id, classroom_id, student_id, added_at) values (?, ?, ?, now())",
                UUID.randomUUID(), classroomId, studentId);
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
        jdbc.update("delete from class_memberships where classroom_id = ?", classroomId);
        jdbc.update("delete from classrooms where id = ?", classroomId);
        jdbc.update("delete from user_accounts where id in (?, ?)", studentId, teacherId);
    }

    private long countIntent(UUID studentId, UUID assignmentId, String key) throws Exception {
        return jdbc.queryForObject("select count(*) from upload_intents where actor_id = ? and assignment_id = ? "
                        + "and purpose = 'SUBMISSION' and idempotency_key_hash = ?", Long.class,
                studentId, assignmentId, sha256(key.trim()));
    }

    private String bearer(UUID userId, String email, String role) {
        Instant now = Instant.now();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                        .issuer("online-homework")
                        .subject(userId.toString())
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(600))
                        .claim("email", email)
                        .claim("role", role)
                        .claim("email_verified", true)
                        .build())).getTokenValue();
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {
        @Bean
        @Primary
        PrivateObjectStorage testPrivateObjectStorage() { return mock(PrivateObjectStorage.class); }
    }
}
