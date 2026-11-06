package com.onlinehomework.file.application;

import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadPurpose;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.application.SubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadServiceIdempotencyRecoveryTest {
    @Test
    void uniqueKeyInsertConflictReturnsTheMatchingExistingIntent() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID existingUploadId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-06T05:00:00Z");
        String requestText = "same payload";
        String fingerprint = sha256("answer.pdf\napplication/pdf\n1024\n" + requestText);
        UploadIntent existing = new UploadIntent(existingUploadId, actorId, assignmentId, UploadPurpose.SUBMISSION,
                "existing-key-hash", fingerprint, "homework/" + assignmentId + "/" + existingUploadId,
                "answer.pdf", "application/pdf", 1024, requestText, now, now.plus(Duration.ofMinutes(15)));

        UploadIntentRepository uploads = mock(UploadIntentRepository.class);
        when(uploads.findByActorIdAndAssignmentIdAndPurposeAndIdempotencyKeyHash(
                eq(actorId), eq(assignmentId), eq(UploadPurpose.SUBMISSION), anyString()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        doThrow(new DataIntegrityViolationException("simulated upload idempotency unique-key race"))
                .when(uploads).save(any(UploadIntent.class));

        AssignmentService assignments = mock(AssignmentService.class);
        when(assignments.lockForStudentSubmission(actorId, assignmentId)).thenReturn(
                new AssignmentService.AssignmentPolicy(assignmentId, UUID.randomUUID(), null,
                        false, AssignmentStatus.PUBLISHED));
        PrivateObjectStorage storage = mock(PrivateObjectStorage.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus(), new SimpleTransactionStatus());

        UploadService service = new UploadService(uploads, assignments, mock(SubmissionService.class), storage,
                new FileRules(), Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofMinutes(2), transactionManager);

        UploadIntentResponse response = service.createSubmissionIntent(actorId, assignmentId, "race-key",
                new CreateUploadRequest("answer.pdf", "application/pdf", 1024, requestText));

        assertEquals(existingUploadId, response.uploadId());
        assertEquals(UploadStatus.PENDING, response.status());
        verify(storage, never()).createUploadUrl(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                anyString(), any());
        verify(uploads, times(2)).findByActorIdAndAssignmentIdAndPurposeAndIdempotencyKeyHash(
                eq(actorId), eq(assignmentId), eq(UploadPurpose.SUBMISSION), anyString());
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
