package com.onlinehomework.file.application;

import com.onlinehomework.assignment.domain.Assignment;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.assignment.persistence.AssignmentRepository;
import com.onlinehomework.classroom.application.ClassroomService;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.file.api.UploadDtos.CompleteUploadResponse;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.DownloadLinkResponse;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadPurpose;
import com.onlinehomework.file.domain.UploadStatus;
import com.onlinehomework.file.persistence.UploadIntentRepository;
import com.onlinehomework.file.storage.PrivateObjectStorage;
import com.onlinehomework.submission.domain.SubmissionVersion;
import com.onlinehomework.submission.persistence.SubmissionVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class UploadService {
    private final UploadIntentRepository uploads;
    private final SubmissionVersionRepository submissions;
    private final AssignmentRepository assignments;
    private final com.onlinehomework.assignment.application.AssignmentService assignmentService;
    private final ClassroomService classrooms;
    private final PrivateObjectStorage storage;
    private final FileRules fileRules;
    private final Clock clock;
    private final Duration uploadUrlTtl;
    private final Duration intentTtl;
    private final Duration downloadUrlTtl;

    public UploadService(UploadIntentRepository uploads, SubmissionVersionRepository submissions,
                         AssignmentRepository assignments,
                         com.onlinehomework.assignment.application.AssignmentService assignmentService,
                         ClassroomService classrooms, PrivateObjectStorage storage, FileRules fileRules, Clock clock,
                         @Value("${app.storage.upload-url-ttl}") Duration uploadUrlTtl,
                         @Value("${app.storage.intent-ttl:PT15M}") Duration intentTtl,
                         @Value("${app.storage.download-url-ttl}") Duration downloadUrlTtl) {
        this.uploads = uploads;
        this.submissions = submissions;
        this.assignments = assignments;
        this.assignmentService = assignmentService;
        this.classrooms = classrooms;
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
        Assignment assignment = assignmentService.requireStudentAssignment(studentId, assignmentId);
        checkDeadline(assignment, clock.instant());
        return createIntent(studentId, assignment, UploadPurpose.SUBMISSION, idempotencyKey, request);
    }

    @Transactional
    public UploadIntentResponse createAssignmentAttachmentIntent(UUID teacherId, UUID assignmentId,
                                                                 String idempotencyKey, CreateUploadRequest request) {
        Assignment assignment = assignmentService.requireOwnedAssignment(teacherId, assignmentId);
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            throw DomainException.conflict("ASSIGNMENT_NOT_DRAFT", "Attachments can only be added to a draft assignment.");
        }
        if (assignment.getAttachmentUploadId() != null) {
            throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_EXISTS", "This assignment already has an attachment.");
        }
        return createIntent(teacherId, assignment, UploadPurpose.ASSIGNMENT_ATTACHMENT, idempotencyKey, request);
    }

    private UploadIntentResponse createIntent(UUID actorId, Assignment assignment, UploadPurpose purpose,
                                             String idempotencyKey, CreateUploadRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw DomainException.invalid("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key must contain 1 to 128 characters.");
        }
        fileRules.validateDeclaration(request.fileName(), request.contentType(), request.sizeBytes());
        String keyHash = sha256(idempotencyKey.trim());
        String fingerprint = sha256(fileRules.sanitizeFileName(request.fileName()) + "\n"
                + request.contentType().trim().toLowerCase() + "\n" + request.sizeBytes() + "\n"
                + (request.text() == null ? "" : request.text()));
        UploadIntent existing = uploads.findByActorIdAndAssignmentIdAndPurposeAndIdempotencyKeyHash(
                actorId, assignment.getId(), purpose, keyHash).orElse(null);
        Instant now = clock.instant();
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw DomainException.conflict("IDEMPOTENCY_KEY_CONFLICT",
                        "This Idempotency-Key was already used for a different request.");
            }
            if (existing.getStatus() == UploadStatus.COMPLETED) return intentResponse(existing, null);
            if (now.isAfter(existing.getExpiresAt())) {
                throw DomainException.conflict("UPLOAD_EXPIRED", "Upload intent expired; create a new request with a new Idempotency-Key.");
            }
            return intentResponse(existing, signedUpload(existing));
        }

        UUID uploadId = UUID.randomUUID();
        String objectKey = "homework/" + assignment.getId() + "/" + uploadId;
        UploadIntent intent = new UploadIntent(uploadId, actorId, assignment.getId(), purpose, keyHash, fingerprint,
                objectKey, fileRules.sanitizeFileName(request.fileName()), request.contentType().trim(),
                request.sizeBytes(), purpose == UploadPurpose.SUBMISSION ? request.text() : null,
                now, now.plus(intentTtl));
        uploads.save(intent);
        return intentResponse(intent, signedUpload(intent));
    }

    @Transactional
    public CompleteUploadResponse complete(UUID actorId, String role, UUID uploadId) {
        UploadIntent intent = uploads.findByIdForUpdate(uploadId)
                .orElseThrow(() -> DomainException.notFound("UPLOAD_NOT_FOUND", "Upload intent was not found."));
        if (!intent.getActorId().equals(actorId)) throw DomainException.forbidden();
        if (intent.getStatus() == UploadStatus.COMPLETED) return completionResponse(intent);
        if (clock.instant().isAfter(intent.getExpiresAt())) {
            throw DomainException.conflict("UPLOAD_EXPIRED", "Upload intent has expired.");
        }

        Assignment assignment;
        if (intent.getPurpose() == UploadPurpose.SUBMISSION) {
            if (!"STUDENT".equals(role)) throw DomainException.forbidden();
            assignment = assignmentService.requireStudentAssignment(actorId, intent.getAssignmentId());
        } else {
            if (!"TEACHER".equals(role)) throw DomainException.forbidden();
            assignment = assignmentService.requireOwnedAssignment(actorId, intent.getAssignmentId());
            if (assignment.getStatus() != AssignmentStatus.DRAFT || assignment.getAttachmentUploadId() != null) {
                throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_NOT_ALLOWED", "This assignment cannot accept an attachment now.");
            }
        }

        PrivateObjectStorage.StoredObject stored = storage.inspect(intent.getObjectKey(), intent.getId().toString());
        fileRules.validateActual(intent.getFileName(), intent.getDeclaredContentType(), intent.getDeclaredSizeBytes(), stored);
        Instant completedAt = clock.instant();

        if (intent.getPurpose() == UploadPurpose.ASSIGNMENT_ATTACHMENT) {
            Assignment locked = assignments.findByIdForUpdate(assignment.getId())
                    .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
            if (locked.getStatus() != AssignmentStatus.DRAFT || locked.getAttachmentUploadId() != null) {
                throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_NOT_ALLOWED", "This assignment cannot accept an attachment now.");
            }
            locked.attachUpload(intent.getId());
            intent.complete(stored.versionId(), locked.getId(), null, null, completedAt);
            return completionResponse(intent);
        }

        if (assignment.isCloseAfterDeadline() && assignment.getDueAt() != null && completedAt.isAfter(assignment.getDueAt())) {
            throw DomainException.conflict("ASSIGNMENT_CLOSED", "This assignment is closed for new submissions.");
        }
        Assignment locked = assignments.findByIdForUpdate(assignment.getId())
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        if (locked.isCloseAfterDeadline() && locked.getDueAt() != null && completedAt.isAfter(locked.getDueAt())) {
            throw DomainException.conflict("ASSIGNMENT_CLOSED", "This assignment is closed for new submissions.");
        }
        int nextVersion = submissions.findMaxVersion(locked.getId(), actorId) + 1;
        boolean isLate = locked.getDueAt() != null && completedAt.isAfter(locked.getDueAt());
        SubmissionVersion submission = submissions.save(new SubmissionVersion(UUID.randomUUID(), locked.getId(), actorId,
                nextVersion, intent.getSubmissionText(), intent.getId(), completedAt, isLate));
        intent.complete(stored.versionId(), submission.getId(), nextVersion, isLate, completedAt);
        return completionResponse(intent);
    }

    @Transactional(readOnly = true)
    public DownloadLinkResponse assignmentAttachment(UUID actorId, String role, UUID assignmentId) {
        Assignment assignment;
        if ("TEACHER".equals(role)) {
            assignment = assignmentService.requireOwnedAssignment(actorId, assignmentId);
        } else {
            assignment = assignmentService.requireStudentAssignment(actorId, assignmentId);
        }
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
        SubmissionVersion submission = submissions.findById(submissionId)
                .orElseThrow(() -> DomainException.notFound("SUBMISSION_NOT_FOUND", "Submission was not found."));
        Assignment assignment;
        if ("TEACHER".equals(role)) {
            assignment = assignmentService.requireOwnedAssignment(actorId, submission.getAssignmentId());
        } else {
            assignment = assignmentService.requireStudentAssignment(actorId, submission.getAssignmentId());
            if (!submission.getStudentId().equals(actorId)) throw DomainException.forbidden();
        }
        UploadIntent intent = uploads.findById(submission.getUploadIntentId())
                .filter(upload -> upload.getStatus() == UploadStatus.COMPLETED)
                .orElseThrow(() -> DomainException.notFound("FILE_NOT_FOUND", "Submission attachment was not found."));
        return download(intent);
    }

    private DownloadLinkResponse download(UploadIntent intent) {
        Instant expiresAt = clock.instant().plus(downloadUrlTtl);
        return new DownloadLinkResponse(storage.createDownloadUrl(intent.getObjectKey(), intent.getObjectVersionId(), downloadUrlTtl), expiresAt);
    }

    private PrivateObjectStorage.SignedUpload signedUpload(UploadIntent intent) {
        return storage.createUploadUrl(intent.getObjectKey(), intent.getDeclaredContentType(), intent.getDeclaredSizeBytes(),
                intent.getId().toString(), uploadUrlTtl);
    }

    private UploadIntentResponse intentResponse(UploadIntent intent, PrivateObjectStorage.SignedUpload signed) {
        return new UploadIntentResponse(intent.getId(), intent.getStatus().name(), signed == null ? null : signed.url(),
                signed == null ? java.util.Map.of() : signed.requiredHeaders(), intent.getExpiresAt(),
                intent.getPurpose() == UploadPurpose.SUBMISSION && intent.getStatus() == UploadStatus.COMPLETED ? intent.getResultId() : null,
                intent.getResultVersion(), intent.getResultIsLate(),
                intent.getPurpose() == UploadPurpose.ASSIGNMENT_ATTACHMENT && intent.getStatus() == UploadStatus.COMPLETED ? intent.getResultId() : null);
    }

    private CompleteUploadResponse completionResponse(UploadIntent intent) {
        return new CompleteUploadResponse(intent.getId(), intent.getStatus().name(),
                intent.getPurpose() == UploadPurpose.SUBMISSION ? intent.getResultId() : null,
                intent.getResultVersion(), intent.getResultIsLate(),
                intent.getPurpose() == UploadPurpose.ASSIGNMENT_ATTACHMENT ? intent.getResultId() : null,
                intent.getCompletedAt());
    }

    private void checkDeadline(Assignment assignment, Instant now) {
        if (assignment.isCloseAfterDeadline() && assignment.getDueAt() != null && now.isAfter(assignment.getDueAt())) {
            throw DomainException.conflict("ASSIGNMENT_CLOSED", "This assignment is closed for new submissions.");
        }
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
