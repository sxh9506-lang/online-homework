package com.onlinehomework.file.application;

import com.onlinehomework.assignment.api.AssignmentDtos.AssignmentResponse;
import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.assignment.application.AssignmentService.AssignmentPolicy;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.DownloadLinkResponse;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadPurpose;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.api.SubmissionDtos.SubmissionResponse;
import com.onlinehomework.submission.application.SubmissionService;
import com.onlinehomework.submission.domain.SubmissionVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class UploadService {
    private final UploadIntentRepository uploads;
    private final AssignmentService assignments;
    private final SubmissionService submissions;
    private final PrivateObjectStorage storage;
    private final FileRules fileRules;
    private final Clock clock;
    private final Duration uploadUrlTtl;
    private final Duration intentTtl;
    private final Duration downloadUrlTtl;

    public UploadService(UploadIntentRepository uploads, AssignmentService assignments,
                         SubmissionService submissions, PrivateObjectStorage storage, FileRules fileRules,
                         Clock clock, @Value("${app.storage.upload-url-ttl}") Duration uploadUrlTtl,
                         @Value("${app.storage.intent-ttl:PT15M}") Duration intentTtl,
                         @Value("${app.storage.download-url-ttl}") Duration downloadUrlTtl) {
        this.uploads = uploads;
        this.assignments = assignments;
        this.submissions = submissions;
        this.storage = storage;
        this.fileRules = fileRules;
        this.clock = clock;
        this.uploadUrlTtl = uploadUrlTtl;
        this.intentTtl = intentTtl;
        this.downloadUrlTtl = downloadUrlTtl;
    }

    @Transactional
    public UploadIntentResponse createSubmissionIntent(UUID studentId, UUID assignmentId,
                                                       String idempotencyKey, CreateUploadRequest request) {
        AssignmentPolicy policy = assignments.lockForStudentSubmission(studentId, assignmentId);
        requireOpenAtCreation(policy, clock.instant());
        return createIntent(studentId, policy.assignmentId(), UploadPurpose.SUBMISSION, idempotencyKey, request);
    }

    @Transactional
    public UploadIntentResponse createAssignmentAttachmentIntent(UUID teacherId, UUID assignmentId,
                                                                 String idempotencyKey, CreateUploadRequest request) {
        AssignmentPolicy policy = assignments.lockForTeacherAttachment(teacherId, assignmentId);
        return createIntent(teacherId, policy.assignmentId(), UploadPurpose.ASSIGNMENT_ATTACHMENT,
                idempotencyKey, request);
    }

    private UploadIntentResponse createIntent(UUID actorId, UUID assignmentId, UploadPurpose purpose,
                                             String idempotencyKey, CreateUploadRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw DomainException.invalid("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key must contain 1 to 200 characters.");
        }
        fileRules.validateDeclaration(request.fileName(), request.contentType(), request.sizeBytes());
        String keyHash = sha256(idempotencyKey.trim());
        String fingerprint = sha256(fileRules.sanitizeFileName(request.fileName()) + "\n"
                + request.contentType().trim().toLowerCase(Locale.ROOT) + "\n" + request.sizeBytes() + "\n"
                + (request.text() == null ? "" : request.text()));
        UploadIntent existing = uploads.findByActorIdAndAssignmentIdAndPurposeAndIdempotencyKeyHash(
                actorId, assignmentId, purpose, keyHash).orElse(null);
        Instant now = clock.instant();
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw DomainException.conflict("IDEMPOTENCY_KEY_CONFLICT",
                        "This Idempotency-Key was already used for a different request.");
            }
            if (existing.getStatus() != UploadStatus.COMPLETED && now.isAfter(existing.getExpiresAt())) {
                throw DomainException.conflict("UPLOAD_EXPIRED",
                        "Upload intent expired; create a new request with a new Idempotency-Key.");
            }
            // Reusing a completed intent is safe: its presigned PUT is write-once via If-None-Match: *.
            return intentResponse(existing);
        }

        UUID uploadId = UUID.randomUUID();
        String objectKey = "homework/" + assignmentId + "/" + uploadId;
        UploadIntent intent = new UploadIntent(uploadId, actorId, assignmentId, purpose, keyHash, fingerprint,
                objectKey, fileRules.sanitizeFileName(request.fileName()), request.contentType().trim(),
                request.sizeBytes(), purpose == UploadPurpose.SUBMISSION ? request.text() : null,
                now, now.plus(intentTtl));
        uploads.save(intent);
        return intentResponse(intent);
    }

    @Transactional
    public SubmissionResponse completeSubmission(UUID studentId, String role, UUID uploadId) {
        UploadIntent intent = uploads.findByIdForUpdate(uploadId)
                .orElseThrow(() -> DomainException.notFound("UPLOAD_NOT_FOUND", "Upload intent was not found."));
        if (intent.getPurpose() != UploadPurpose.SUBMISSION) {
            throw DomainException.notFound("UPLOAD_NOT_FOUND", "Submission upload was not found.");
        }
        if (!"STUDENT".equals(role) || !intent.getActorId().equals(studentId)) {
            throw DomainException.forbidden();
        }
        assignments.requireStudentAssignment(studentId, intent.getAssignmentId());
        if (intent.getStatus() == UploadStatus.COMPLETED) {
            return submissions.getForStudent(studentId, intent.getResultId());
        }
        requireNotExpired(intent);

        PrivateObjectStorage.StoredObject stored = storage.inspect(intent.getObjectKey(), intent.getId().toString());
        fileRules.validateActual(intent.getFileName(), intent.getDeclaredContentType(),
                intent.getDeclaredSizeBytes(), stored);
        Instant completedAt = clock.instant();
        AssignmentPolicy policy = assignments.lockForStudentSubmission(studentId, intent.getAssignmentId());
        if (policy.closeAfterDeadline() && policy.dueAt() != null && completedAt.isAfter(policy.dueAt())) {
            throw DomainException.conflict("ASSIGNMENT_CLOSED", "This assignment is closed for new submissions.");
        }
        boolean late = policy.dueAt() != null && completedAt.isAfter(policy.dueAt());
        SubmissionVersion submission = submissions.createCompleted(policy.assignmentId(), studentId, intent.getId(),
                intent.getSubmissionText(), completedAt, late);
        intent.complete(stored.versionId(), submission.getId(), submission.getVersion(), late, completedAt);
        return submissions.response(submission);
    }

    @Transactional
    public AssignmentResponse completeAssignmentAttachment(UUID teacherId, String role,
                                                           UUID assignmentId, UUID uploadId) {
        UploadIntent intent = uploads.findByIdForUpdate(uploadId)
                .orElseThrow(() -> DomainException.notFound("UPLOAD_NOT_FOUND", "Upload intent was not found."));
        if (intent.getPurpose() != UploadPurpose.ASSIGNMENT_ATTACHMENT
                || !intent.getAssignmentId().equals(assignmentId)) {
            throw DomainException.notFound("UPLOAD_NOT_FOUND", "Assignment attachment upload was not found.");
        }
        if (!"TEACHER".equals(role) || !intent.getActorId().equals(teacherId)) {
            throw DomainException.forbidden();
        }
        if (intent.getStatus() == UploadStatus.COMPLETED) {
            assignments.requireOwnedAssignment(teacherId, assignmentId);
            return assignments.get(teacherId, "TEACHER", assignmentId);
        }
        requireNotExpired(intent);
        assignments.requireDraftAcceptingAttachment(teacherId, assignmentId);
        PrivateObjectStorage.StoredObject stored = storage.inspect(intent.getObjectKey(), intent.getId().toString());
        fileRules.validateActual(intent.getFileName(), intent.getDeclaredContentType(),
                intent.getDeclaredSizeBytes(), stored);
        Instant completedAt = clock.instant();
        assignments.attachValidatedFile(teacherId, assignmentId, intent.getId());
        intent.complete(stored.versionId(), assignmentId, null, null, completedAt);
        return assignments.get(teacherId, "TEACHER", assignmentId);
    }

    @Transactional(readOnly = true)
    public DownloadLinkResponse assignmentAttachment(UUID actorId, String role, UUID assignmentId) {
        var assignment = "TEACHER".equals(role)
                ? assignments.requireOwnedAssignment(actorId, assignmentId)
                : assignments.requireStudentAssignment(actorId, assignmentId);
        if (assignment.getAttachmentUploadId() == null) {
            throw DomainException.notFound("FILE_NOT_FOUND", "Assignment attachment was not found.");
        }
        UploadIntent intent = uploads.findById(assignment.getAttachmentUploadId())
                .filter(upload -> upload.getStatus() == UploadStatus.COMPLETED)
                .orElseThrow(() -> DomainException.notFound("FILE_NOT_FOUND", "Assignment attachment was not found."));
        return download(intent);
    }

    @Transactional(readOnly = true)
    public DownloadLinkResponse submissionAttachment(UUID actorId, String role, UUID submissionId) {
        SubmissionVersion submission = "TEACHER".equals(role)
                ? submissions.requireTeacherSubmission(actorId, submissionId)
                : submissions.requireStudentSubmission(actorId, submissionId);
        UploadIntent intent = uploads.findById(submission.getUploadIntentId())
                .filter(upload -> upload.getStatus() == UploadStatus.COMPLETED)
                .orElseThrow(() -> DomainException.notFound("FILE_NOT_FOUND", "Submission attachment was not found."));
        return download(intent);
    }

    private void requireNotExpired(UploadIntent intent) {
        if (clock.instant().isAfter(intent.getExpiresAt())) {
            throw DomainException.conflict("UPLOAD_EXPIRED", "Upload intent has expired.");
        }
    }

    private void requireOpenAtCreation(AssignmentPolicy policy, Instant now) {
        if (policy.closeAfterDeadline() && policy.dueAt() != null && now.isAfter(policy.dueAt())) {
            throw DomainException.conflict("ASSIGNMENT_CLOSED", "This assignment is closed for new submissions.");
        }
    }

    private UploadIntentResponse intentResponse(UploadIntent intent) {
        PrivateObjectStorage.SignedUpload signed = storage.createUploadUrl(intent.getObjectKey(),
                intent.getDeclaredContentType(), intent.getDeclaredSizeBytes(), intent.getId().toString(), uploadUrlTtl);
        return new UploadIntentResponse(intent.getId(), signed.url(), clock.instant().plus(uploadUrlTtl),
                signed.requiredHeaders());
    }

    private DownloadLinkResponse download(UploadIntent intent) {
        Instant expiresAt = clock.instant().plus(downloadUrlTtl);
        String url = storage.createDownloadUrl(intent.getObjectKey(), intent.getObjectVersionId(), downloadUrlTtl);
        return new DownloadLinkResponse(url, expiresAt);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
