package com.onlinehomework.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_intents")
public class UploadIntent {
    @Id
    private UUID id;
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UploadPurpose purpose;
    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @Column(name = "declared_content_type", nullable = false, length = 128)
    private String declaredContentType;
    @Column(name = "declared_size_bytes", nullable = false)
    private long declaredSizeBytes;
    @Column(name = "submission_text", length = 10000)
    private String submissionText;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UploadStatus status;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "object_version_id", length = 1024)
    private String objectVersionId;
    @Column(name = "result_id")
    private UUID resultId;
    @Column(name = "result_version")
    private Integer resultVersion;
    @Column(name = "result_is_late")
    private Boolean resultIsLate;
    @Version
    private long version;

    protected UploadIntent() { }

    public UploadIntent(UUID id, UUID actorId, UUID assignmentId, UploadPurpose purpose,
                        String idempotencyKeyHash, String requestFingerprint, String objectKey,
                        String fileName, String declaredContentType, long declaredSizeBytes,
                        String submissionText, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.actorId = actorId;
        this.assignmentId = assignmentId;
        this.purpose = purpose;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.requestFingerprint = requestFingerprint;
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.declaredContentType = declaredContentType;
        this.declaredSizeBytes = declaredSizeBytes;
        this.submissionText = submissionText;
        this.status = UploadStatus.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void complete(String objectVersionId, UUID resultId, Integer resultVersion, Boolean isLate, Instant completedAt) {
        if (status == UploadStatus.COMPLETED) return;
        this.status = UploadStatus.COMPLETED;
        this.objectVersionId = objectVersionId;
        this.resultId = resultId;
        this.resultVersion = resultVersion;
        this.resultIsLate = isLate;
        this.completedAt = completedAt;
    }

    public UUID getId() { return id; }
    public UUID getActorId() { return actorId; }
    public UUID getAssignmentId() { return assignmentId; }
    public UploadPurpose getPurpose() { return purpose; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getObjectKey() { return objectKey; }
    public String getFileName() { return fileName; }
    public String getDeclaredContentType() { return declaredContentType; }
    public long getDeclaredSizeBytes() { return declaredSizeBytes; }
    public String getSubmissionText() { return submissionText; }
    public UploadStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getObjectVersionId() { return objectVersionId; }
    public UUID getResultId() { return resultId; }
    public Integer getResultVersion() { return resultVersion; }
    public Boolean getResultIsLate() { return resultIsLate; }
}
