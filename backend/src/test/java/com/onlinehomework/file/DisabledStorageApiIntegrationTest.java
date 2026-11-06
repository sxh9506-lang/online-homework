package com.onlinehomework.file;

import com.jayway.jsonpath.JsonPath;
import com.onlinehomework.assignment.persistence.AssignmentRepository;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadPurpose;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.identity.application.VerificationEmailSender;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import com.onlinehomework.submission.domain.SubmissionVersion;
import com.onlinehomework.submission.persistence.SubmissionVersionRepository;
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
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DisabledStorageApiIntegrationTest.TestMailConfiguration.class)
class DisabledStorageApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired AssignmentRepository assignments;
    @Autowired UploadIntentRepository uploads;
    @Autowired SubmissionVersionRepository submissions;
    @Autowired JwtEncoder jwtEncoder;

    @Test
    void disabledStorageGuardPrecedesReplayConflictAndResourceLookupPaths() throws Exception {
        String suffix = UUID.randomUUID().toString();
        TestAccount teacher = account("teacher-" + suffix + "@example.test", UserRole.TEACHER);
        TestAccount student = account("student-" + suffix + "@example.test", UserRole.STUDENT);
        String classId = createClassWithStudent(teacher, student);
        String draftAssignmentId = createDraftAssignment(teacher, classId, "Draft " + suffix);
        String publishedAssignmentId = createDraftAssignment(teacher, classId, "Published " + suffix);
        mvc.perform(post("/api/v1/assignments/{assignmentId}/publish", publishedAssignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk());

        long beforeFailedCreates = uploads.count();
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions",
                                publishedAssignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", "disabled-submission-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest("student work"))));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/attachment-upload-intents",
                                draftAssignmentId)
                        .header("Authorization", teacher.bearer())
                        .header("Idempotency-Key", "disabled-attachment-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest(null))));
        assertEquals(beforeFailedCreates, uploads.count(),
                "failed intent creation must roll back the database row instead of leaving a partial record");

        Instant now = Instant.now();
        UUID submissionUploadId = UUID.randomUUID();
        UUID attachmentUploadId = UUID.randomUUID();
        String submissionConflictKey = "disabled-conflict-submission-" + suffix;
        String attachmentConflictKey = "disabled-conflict-attachment-" + suffix;
        uploads.save(new UploadIntent(submissionUploadId, student.id(), UUID.fromString(publishedAssignmentId),
                UploadPurpose.SUBMISSION, sha256(submissionConflictKey), requestFingerprint("seeded pending submission"),
                "homework/" + publishedAssignmentId + "/" + submissionUploadId,
                "answer.pdf", "application/pdf", 1024, "seeded pending submission", now, now.plusSeconds(900)));
        uploads.save(new UploadIntent(attachmentUploadId, teacher.id(), UUID.fromString(draftAssignmentId),
                UploadPurpose.ASSIGNMENT_ATTACHMENT, sha256(attachmentConflictKey), requestFingerprint(null),
                "homework/" + draftAssignmentId + "/" + attachmentUploadId,
                "answer.pdf", "application/pdf", 1024, null, now, now.plusSeconds(900)));

        long beforeConflictRetries = uploads.count();
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions",
                                publishedAssignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", submissionConflictKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest("seeded pending submission"))));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/attachment-upload-intents",
                                draftAssignmentId)
                        .header("Authorization", teacher.bearer())
                        .header("Idempotency-Key", attachmentConflictKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest(null))));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/submissions",
                                publishedAssignmentId)
                        .header("Authorization", student.bearer())
                        .header("Idempotency-Key", submissionConflictKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest("different payload"))));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/attachment-upload-intents",
                                draftAssignmentId)
                        .header("Authorization", teacher.bearer())
                        .header("Idempotency-Key", attachmentConflictKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadRequest("different payload"))));
        assertEquals(beforeConflictRetries, uploads.count(),
                "disabled storage must precede replay/conflict lookup and must not mutate intents");
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", UUID.randomUUID())
                .header("Authorization", student.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/submissions/{uploadId}/complete", UUID.randomUUID())
                .header("Authorization", student.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post(
                        "/api/v1/assignments/{assignmentId}/attachment-upload-intents/{uploadId}/complete",
                        draftAssignmentId, UUID.randomUUID())
                .header("Authorization", teacher.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/attachment-download",
                        UUID.randomUUID())
                .header("Authorization", teacher.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/submissions/{submissionId}/attachment-download",
                        UUID.randomUUID())
                .header("Authorization", student.bearer())));

        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", submissionUploadId)
                .header("Authorization", student.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", attachmentUploadId)
                .header("Authorization", teacher.bearer())));

        long visibleSubmissionsBeforeCompletion = submissions.count();
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/submissions/{uploadId}/complete", submissionUploadId)
                .header("Authorization", student.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post(
                        "/api/v1/assignments/{assignmentId}/attachment-upload-intents/{uploadId}/complete",
                        draftAssignmentId, attachmentUploadId)
                .header("Authorization", teacher.bearer())));
        assertEquals(visibleSubmissionsBeforeCompletion, submissions.count(),
                "unavailable storage must not create a visible submission version");

        UploadIntent failedSubmission = uploads.findById(submissionUploadId).orElseThrow();
        assertEquals(com.onlinehomework.file.domain.UploadStatus.PENDING, failedSubmission.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(failedSubmission.getCompletedAt());
        UploadIntent failedAttachment = uploads.findById(attachmentUploadId).orElseThrow();
        assertEquals(com.onlinehomework.file.domain.UploadStatus.PENDING, failedAttachment.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(failedAttachment.getCompletedAt());
        var unmodifiedDraft = assignments.findById(UUID.fromString(draftAssignmentId)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(unmodifiedDraft.getAttachmentUploadId());

        UUID submissionVersionId = UUID.randomUUID();
        failedSubmission.complete("fixture-submission-version", submissionVersionId, 1, false, now);
        uploads.save(failedSubmission);
        submissions.save(new SubmissionVersion(submissionVersionId, UUID.fromString(publishedAssignmentId),
                student.id(), 1, "student work", submissionUploadId, now, false));
        long submissionsBeforeReplay = submissions.count();
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/submissions/{uploadId}/complete", submissionUploadId)
                .header("Authorization", student.bearer())));
        assertEquals(submissionsBeforeReplay, submissions.count(),
                "replaying a completed submission must return the original result without creating another version");

        UploadIntent completedAttachment = uploads.findById(attachmentUploadId).orElseThrow();
        completedAttachment.complete("fixture-version", UUID.fromString(draftAssignmentId), null, null, now);
        uploads.save(completedAttachment);
        var draftAssignment = assignments.findById(UUID.fromString(draftAssignmentId)).orElseThrow();
        draftAssignment.attachUpload(attachmentUploadId);
        assignments.save(draftAssignment);
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/upload-intents/{uploadId}/upload-url", attachmentUploadId)
                .header("Authorization", teacher.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/assignments/{assignmentId}/attachment-download",
                        draftAssignmentId)
                .header("Authorization", teacher.bearer())));
        assertAttachmentsUnavailable(mvc.perform(post("/api/v1/submissions/{submissionId}/attachment-download",
                        submissionVersionId)
                .header("Authorization", student.bearer())));
        mvc.perform(get("/api/v1/assignments/{assignmentId}", draftAssignmentId)
                        .header("Authorization", teacher.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.id").value(attachmentUploadId.toString()));
        assertAttachmentsUnavailable(mvc.perform(post(
                        "/api/v1/assignments/{assignmentId}/attachment-upload-intents/{uploadId}/complete",
                        draftAssignmentId, attachmentUploadId)
                .header("Authorization", teacher.bearer())));
    }

    private String createClassWithStudent(TestAccount teacher, TestAccount student) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/classes")
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Storage class\",\"timeZone\":\"Asia/Tokyo\"}"))
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

    private String createDraftAssignment(TestAccount teacher, String classId, String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/classes/{classId}/assignments", classId)
                        .header("Authorization", teacher.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"instructions\":\"private file test\","
                                + "\"closeAfterDeadline\":false}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result, "$.id");
    }

    private TestAccount account(String email, UserRole role) {
        UUID id = UUID.randomUUID();
        UserAccount user = users.save(new UserAccount(id, email, "test-only-placeholder-hash", role,
                true, true, "Storage test", Instant.now()));
        Instant now = Instant.now();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                        .issuer("online-homework")
                        .subject(user.getId().toString())
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(600))
                        .claim("email", user.getEmail())
                        .claim("role", user.getRole().name())
                        .claim("email_verified", true)
                        .build())).getTokenValue();
        return new TestAccount(user.getId(), user.getEmail(), token);
    }

    private static void assertAttachmentsUnavailable(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ATTACHMENTS_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private static String uploadRequest(String text) {
        return "{\"fileName\":\"answer.pdf\",\"contentType\":\"application/pdf\","
                + "\"sizeBytes\":1024" + (text == null ? "" : ",\"text\":\"" + text + "\"") + "}";
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String requestFingerprint(String text) throws Exception {
        return sha256("answer.pdf\napplication/pdf\n1024\n" + (text == null ? "" : text));
    }

    private static String json(MvcResult result, String path) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    private record TestAccount(UUID id, String email, String token) {
        String bearer() { return "Bearer " + token; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestMailConfiguration {
        @Bean
        @Primary
        VerificationEmailSender testVerificationEmailSender() { return mock(VerificationEmailSender.class); }
    }
}
