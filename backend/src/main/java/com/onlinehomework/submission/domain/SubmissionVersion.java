package com.onlinehomework.submission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submission_versions")
public class SubmissionVersion {
    @Id
    private UUID id;
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;
    @Column(name = "student_id", nullable = false)
    private UUID studentId;
    @Column(nullable = false)
    private int version;
    @Column(length = 10000)
    private String text;
    @Column(name = "upload_intent_id", nullable = false, unique = true)
    private UUID uploadIntentId;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "is_late", nullable = false)
    private boolean late;

    protected SubmissionVersion() { }

    public SubmissionVersion(UUID id, UUID assignmentId, UUID studentId, int version, String text,
                             UUID uploadIntentId, Instant submittedAt, boolean late) {
        this.id = id;
        this.assignmentId = assignmentId;
        this.studentId = studentId;
        this.version = version;
        this.text = text;
        this.uploadIntentId = uploadIntentId;
        this.submittedAt = submittedAt;
        this.late = late;
    }

    public UUID getId() { return id; }
    public UUID getAssignmentId() { return assignmentId; }
    public UUID getStudentId() { return studentId; }
    public int getVersion() { return version; }
    public String getText() { return text; }
    public UUID getUploadIntentId() { return uploadIntentId; }
    public Instant getSubmittedAt() { return submittedAt; }
    public boolean isLate() { return late; }
}
