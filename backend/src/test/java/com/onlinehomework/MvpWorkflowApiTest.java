package com.onlinehomework;

import com.jayway.jsonpath.JsonPath;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.identity.application.VerificationEmailSender;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MvpWorkflowApiTest.TestStorageConfiguration.class)
class MvpWorkflowApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired UploadIntentRepository uploadIntents;
    @Autowired PrivateObjectStorage storage;
    @Autowired VerificationEmailSender emailSender;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired MutableTestClock clock;

    @BeforeEach
    void configurePrivateObjectStorage() {
        clock.set(Instant.now());
        reset(storage);
        reset(emailSender);
        when(storage.createUploadUrl(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(new PrivateObjectStorage.SignedUpload(
                        "https://storage.invalid/private-upload", Map.of("If-None-Match", "*")));
        when(storage.inspect(anyString(), anyString())).thenReturn(
                new PrivateObjectStorage.StoredObject("test-object-version", 1024,
                        "application/pdf", "application/pdf", "test-sha256"));
    }

    @Test
    void studentMustVerifyEmailBeforeLoginAndMeUsesAuthenticatedIdentity() throws Exception {
        String email = "new-student-" + UUID.randomUUID() + "@example.test";
        String password = "very-secure-test-password-2026";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\",\"displayName\":\"New Student\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.verificationRequired").value(true));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        ArgumentCaptor<String> verificationToken = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).send(org.mockito.ArgumentMatchers.eq(email), verificationToken.capture());
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + verificationToken.getValue() + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerified").value(true))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andReturn();
        String accessToken = json(login, "$.accessToken");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void teacherStudentWorkflowKeepsUploadsPrivateAndPublishesOnlyCurrentGrade() throws Exception {
        TestAccount teacher = account("teacher@example.test", UserRole.TEACHER);
        TestAccount otherTeacher = account("other-teacher@example.test", UserRole.TEACHER);
        TestAccount student = account("student@example.test", UserRole.STUDENT);
        TestAccount otherStudent = account("other-student@example.test", UserRole.STUDENT);

        MvcResult classResult = mvc.perform(post("/api/v1/classes")
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Class 7A","description":"integration fixture","timeZone":"Asia/Tokyo"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String classId = json(classResult, "$.id");

        mvc.perform(post("/api/v1/classes/{classId}/members", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"student@example.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(student.id().toString()));
        mvc.perform(post("/api/v1/classes/{classId}/members", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"other-student@example.test\"}"))
                .andExpect(status().isCreated());

        MvcResult assignmentResult = mvc.perform(post("/api/v1/classes/{classId}/assignments", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Short essay","instructions":"Write one paragraph.","closeAfterDeadline":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String assignmentId = json(assignmentResult, "$.id");

        mvc.perform(get("/api/v1/assignments/{assignmentId}", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/classes/{classId}", classId)
                        .header("Authorization", otherTeacher.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assignments/{assignmentId}", assignmentId)
                        .header("Authorization", otherTeacher.bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/assignments/{assignmentId}/publish", assignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/api/v1/assignments/{assignmentId}", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/assignments/{assignmentId}/my-submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        String uploadRequest = """
                {"fileName":"answer.pdf","contentType":"application/pdf","sizeBytes":1024,"text":"My first answer"}
                """;
        MvcResult uploadResult = mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", "student-first-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.uploadUrl").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.requiredHeaders").doesNotExist())
                .andReturn();
        String uploadId = json(uploadResult, "$.uploadId");
        long uploadCountAfterCreate = uploadIntents.count();

        var intentBeforeUrlIssue = uploadIntents.findById(UUID.fromString(uploadId)).orElseThrow();
        MvcResult signedUpload = mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", uploadId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadUrl").value("https://storage.invalid/private-upload"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.requiredHeaders.If-None-Match").value("*"))
                .andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(json(signedUpload, "$.uploadUrl").isBlank());
        mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", uploadId)
                        .header("Authorization", otherStudent.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", uploadId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", uploadId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk());
        var intentAfterUrlRetries = uploadIntents.findById(UUID.fromString(uploadId)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(intentBeforeUrlIssue.getId(), intentAfterUrlRetries.getId());
        org.junit.jupiter.api.Assertions.assertEquals(intentBeforeUrlIssue.getStatus(), intentAfterUrlRetries.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(intentBeforeUrlIssue.getCreatedAt(), intentAfterUrlRetries.getCreatedAt());
        org.junit.jupiter.api.Assertions.assertEquals(intentBeforeUrlIssue.getExpiresAt(), intentAfterUrlRetries.getExpiresAt());
        org.junit.jupiter.api.Assertions.assertEquals(uploadCountAfterCreate, uploadIntents.count());

        mvc.perform(get("/api/v1/assignments/{assignmentId}/my-submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        MvcResult completeResult = mvc.perform(post("/api/v1/submissions/{uploadId}/complete", uploadId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.late").value(false))
                .andReturn();
        String submissionId = json(completeResult, "$.id");

        MvcResult retryResult = mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", "student-first-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest))
                .andExpect(status().isCreated())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(uploadId, json(retryResult, "$.uploadId"));
        MvcResult completeRetry = mvc.perform(post("/api/v1/submissions/{uploadId}/complete", uploadId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(submissionId, json(completeRetry, "$.id"));
        mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", "student-first-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest.replace("My first answer", "different text")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        mvc.perform(get("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/submissions/{submissionId}/attachment-download", submissionId)
                        .header("Authorization", otherStudent.bearer()))
                .andExpect(status().isNotFound());

        MvcResult gradeResult = mvc.perform(post("/api/v1/submissions/{submissionId}/grades", submissionId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":95.5,\"feedback\":\"Clear and well supported.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String gradeId = json(gradeResult, "$.id");
        mvc.perform(get("/api/v1/assignments/{assignmentId}/my-submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentPublishedGrade").value(nullValue()));
        mvc.perform(get("/api/v1/submissions/{submissionId}/grades", submissionId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/submissions/{submissionId}/grades", submissionId)
                        .header("Authorization", otherTeacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":1,\"feedback\":\"unauthorized\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/grades/{gradeId}/publish", gradeId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(get("/api/v1/assignments/{assignmentId}/my-submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currentPublishedGrade.status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].currentPublishedGrade.score").value(95.5));

        mvc.perform(delete("/api/v1/classes/{classId}/members/{studentId}", classId, student.id())
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/assignments/{assignmentId}", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assignments/{assignmentId}/my-submissions", assignmentId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void submissionCompletionUsesStrictUtcDeadlineBoundary() throws Exception {
        Instant baseline = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        clock.set(baseline);
        String suffix = UUID.randomUUID().toString();
        TestAccount teacher = account("teacher-" + suffix + "@example.test", UserRole.TEACHER);
        TestAccount student = account("student-" + suffix + "@example.test", UserRole.STUDENT);
        String classId = createClassWithStudent(teacher, student);

        Instant closedDue = baseline.plusSeconds(120);
        String closedAssignmentId = createPublishedAssignment(teacher, classId, "Strict deadline", closedDue, true);
        String onTimeClosedUpload = createSubmissionIntent(student, closedAssignmentId, "closed-on-time", "at due");
        clock.set(closedDue);
        completeSuccessfully(student, onTimeClosedUpload)
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.version").value(1));

        String afterDeadlineUpload = createSubmissionIntent(student, closedAssignmentId, "closed-after-due", "too late");
        clock.set(closedDue.plusNanos(1));
        mvc.perform(post("/api/v1/submissions/{uploadId}/complete", afterDeadlineUpload)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", closedAssignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", "closed-new-request-after-due")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\",\"sizeBytes\":1024,\"text\":\"too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_CLOSED"));
        mvc.perform(get("/api/v1/assignments/{assignmentId}/submissions", closedAssignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        Instant openDue = baseline.plusSeconds(240);
        String openAssignmentId = createPublishedAssignment(teacher, classId, "Late work allowed", openDue, false);
        String onTimeOpenUpload = createSubmissionIntent(student, openAssignmentId, "open-on-time", "on time");
        clock.set(openDue);
        completeSuccessfully(student, onTimeOpenUpload)
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.version").value(1));

        String lateUpload = createSubmissionIntent(student, openAssignmentId, "open-late", "late work");
        clock.set(openDue.plusNanos(1));
        completeSuccessfully(student, lateUpload)
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/assignments/{assignmentId}/submissions", openAssignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].late").value(true))
                .andExpect(jsonPath("$[1].late").value(false));
    }

    @Test
    void concurrentSameKeyUploadCreationReturnsTheSingleExistingIntent() throws Exception {
        String suffix = UUID.randomUUID().toString();
        TestAccount teacher = account("teacher-concurrent-" + suffix + "@example.test", UserRole.TEACHER);
        TestAccount student = account("student-concurrent-" + suffix + "@example.test", UserRole.STUDENT);
        String classId = createClassWithStudent(teacher, student);
        String assignmentId = createPublishedAssignment(teacher, classId, "Concurrent idempotency",
                clock.instant().plusSeconds(3600), false);
        long uploadCountBefore = uploadIntents.count();
        String idempotencyKey = "parallel-same-key-" + suffix;
        String requestBody = "{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\","
                + "\"sizeBytes\":1024,\"text\":\"same request\"}";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var request = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent upload requests did not start together.");
                }
                return mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                                .header("Authorization", student.bearer())
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
            };
            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertEquals(201, firstResult.getResponse().getStatus());
            org.junit.jupiter.api.Assertions.assertEquals(201, secondResult.getResponse().getStatus());
            org.junit.jupiter.api.Assertions.assertEquals(json(firstResult, "$.uploadId"),
                    json(secondResult, "$.uploadId"));
            org.junit.jupiter.api.Assertions.assertEquals("PENDING", json(firstResult, "$.status"));
            org.junit.jupiter.api.Assertions.assertEquals("PENDING", json(secondResult, "$.status"));
            org.junit.jupiter.api.Assertions.assertFalse(firstResult.getResponse().getContentAsString().contains("\"uploadUrl\""));
            org.junit.jupiter.api.Assertions.assertFalse(secondResult.getResponse().getContentAsString().contains("\"expiresAt\""));
            assertEquivalentHttpResponse(firstResult, secondResult);
            org.junit.jupiter.api.Assertions.assertEquals(uploadCountBefore + 1, uploadIntents.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyDifferentBodiesCreatesOneIntentAndReturnsConflictForTheOther() throws Exception {
        String suffix = UUID.randomUUID().toString();
        TestAccount teacher = account("teacher-conflict-" + suffix + "@example.test", UserRole.TEACHER);
        TestAccount student = account("student-conflict-" + suffix + "@example.test", UserRole.STUDENT);
        String classId = createClassWithStudent(teacher, student);
        String assignmentId = createPublishedAssignment(teacher, classId, "Concurrent conflicting idempotency",
                clock.instant().plusSeconds(3600), false);
        long uploadCountBefore = uploadIntents.count();
        String idempotencyKey = "parallel-conflicting-key-" + suffix;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var request = (java.util.function.Function<String, java.util.concurrent.Callable<MvcResult>>) text -> () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent upload requests did not start together.");
                }
                return mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                                .header("Authorization", student.bearer())
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\","
                                        + "\"sizeBytes\":1024,\"text\":\"" + text + "\"}"))
                        .andReturn();
            };
            Future<MvcResult> first = executor.submit(request.apply("first body"));
            Future<MvcResult> second = executor.submit(request.apply("second body"));
            org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            MvcResult firstResult = first.get(20, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(20, TimeUnit.SECONDS);

            var results = java.util.List.of(firstResult, secondResult);
            var winner = results.stream().filter(result -> result.getResponse().getStatus() == 201)
                    .findFirst().orElseThrow(() -> new AssertionError("Exactly one request must create an intent."));
            var conflict = results.stream().filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst().orElseThrow(() -> new AssertionError("The different body must conflict."));
            org.junit.jupiter.api.Assertions.assertEquals(1,
                    results.stream().filter(result -> result.getResponse().getStatus() == 201).count());
            org.junit.jupiter.api.Assertions.assertEquals("IDEMPOTENCY_KEY_CONFLICT", json(conflict, "$.code"));
            org.junit.jupiter.api.Assertions.assertFalse(json(winner, "$.uploadId").isBlank());
            org.junit.jupiter.api.Assertions.assertEquals(uploadCountBefore + 1, uploadIntents.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertEquivalentHttpResponse(MvcResult first, MvcResult second) throws Exception {
        var firstResponse = first.getResponse();
        var secondResponse = second.getResponse();
        org.junit.jupiter.api.Assertions.assertEquals(firstResponse.getStatus(), secondResponse.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(firstResponse.getContentType(), secondResponse.getContentType());
        org.junit.jupiter.api.Assertions.assertEquals(firstResponse.getCharacterEncoding(),
                secondResponse.getCharacterEncoding());
        org.junit.jupiter.api.Assertions.assertEquals(firstResponse.getErrorMessage(), secondResponse.getErrorMessage());
        var firstHeaderNames = new java.util.TreeSet<>(firstResponse.getHeaderNames());
        var secondHeaderNames = new java.util.TreeSet<>(secondResponse.getHeaderNames());
        org.junit.jupiter.api.Assertions.assertEquals(firstHeaderNames, secondHeaderNames);
        for (String headerName : firstHeaderNames) {
            org.junit.jupiter.api.Assertions.assertEquals(firstResponse.getHeaders(headerName),
                    secondResponse.getHeaders(headerName), "HTTP header must match: " + headerName);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(firstResponse.getContentAsByteArray(),
                secondResponse.getContentAsByteArray());
    }

    private String createClassWithStudent(TestAccount teacher, TestAccount student) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/classes")
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Deadline class\",\"timeZone\":\"Asia/Tokyo\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String classId = json(result, "$.id");
        mvc.perform(post("/api/v1/classes/{classId}/members", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + student.email() + "\"}"))
                .andExpect(status().isCreated());
        return classId;
    }

    private String createPublishedAssignment(TestAccount teacher, String classId, String title,
                                             Instant dueAt, boolean closeAfterDeadline) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"instructions\":\"deadline test\","
                + "\"dueAt\":\"" + dueAt + "\",\"closeAfterDeadline\":" + closeAfterDeadline + "}";
        MvcResult result = mvc.perform(post("/api/v1/classes/{classId}/assignments", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String assignmentId = json(result, "$.id");
        mvc.perform(post("/api/v1/assignments/{assignmentId}/publish", assignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk());
        return assignmentId;
    }

    private String createSubmissionIntent(TestAccount student, String assignmentId,
                                          String idempotencyKey, String text) throws Exception {
        String body = "{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\","
                + "\"sizeBytes\":1024,\"text\":\"" + text + "\"}";
        MvcResult result = mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions", assignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result, "$.uploadId");
    }

    private org.springframework.test.web.servlet.ResultActions completeSuccessfully(
            TestAccount student, String uploadId) throws Exception {
        return mvc.perform(post("/api/v1/submissions/{uploadId}/complete", uploadId)
                        .header("Authorization", student.bearer()))
                .andExpect(status().isOk());
    }

    private TestAccount account(String email, UserRole role) {
        UUID id = UUID.randomUUID();
        UserAccount user = users.save(new UserAccount(id, email, "test-password-hash", role,
                true, true, email.substring(0, email.indexOf('@')), clock.instant()));
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("online-homework")
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("email_verified", user.isEmailVerified())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TestAccount(user.getId(), user.getEmail(), token);
    }

    private static String json(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    private record TestAccount(UUID id, String email, String token) {
        String bearer() { return "Bearer " + token; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {
        @Bean
        @Primary
        PrivateObjectStorage testPrivateObjectStorage() {
            return mock(PrivateObjectStorage.class);
        }

        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(Instant.now());
        }

        @Bean
        @Primary
        VerificationEmailSender testVerificationEmailSender() {
            return mock(VerificationEmailSender.class);
        }
    }

    static class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        MutableTestClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableTestClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void set(Instant instant) { current.set(instant); }

        @Override
        public ZoneId getZone() { return zone; }

        @Override
        public Clock withZone(ZoneId zone) { return new MutableTestClock(current, zone); }

        @Override
        public Instant instant() { return current.get(); }
    }
}
