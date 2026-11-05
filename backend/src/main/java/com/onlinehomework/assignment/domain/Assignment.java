package com.onlinehomework.assignment.domain;

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
@Table(name = "assignments")
public class Assignment {
    @Id
    private UUID id;
    @Column(name = "classroom_id", nullable = false)
    private UUID classroomId;
    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(length = 10000)
    private String instructions;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssignmentStatus status;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "close_after_deadline", nullable = false)
    private boolean closeAfterDeadline;
    @Column(name = "attachment_upload_id")
    private UUID attachmentUploadId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Version
    private long version;

    protected Assignment() { }

    public Assignment(UUID id, UUID classroomId, UUID teacherId, String title, String instructions,
                      Instant dueAt, boolean closeAfterDeadline, Instant createdAt) {
        this.id = id;
        this.classroomId = classroomId;
        this.teacherId = teacherId;
        this.title = title;
        this.instructions = instructions;
        this.status = AssignmentStatus.DRAFT;
        this.dueAt = dueAt;
        this.closeAfterDeadline = closeAfterDeadline;
        this.createdAt = createdAt;
    }

    public void updateDraft(String title, String instructions, Instant dueAt, Boolean closeAfterDeadline) {
        if (status != AssignmentStatus.DRAFT) {
            throw new IllegalStateException("Only draft assignments can be edited.");
        }
        if (title != null) this.title = title;
        if (instructions != null) this.instructions = instructions;
        if (dueAt != null) this.dueAt = dueAt;
        if (closeAfterDeadline != null) this.closeAfterDeadline = closeAfterDeadline;
    }

    public void publish(Instant now) {
        if (status != AssignmentStatus.DRAFT) {
            throw new IllegalStateException("Only draft assignments can be published.");
        }
        this.status = AssignmentStatus.PUBLISHED;
        this.publishedAt = now;
    }

    public void attachUpload(UUID uploadId) {
        if (attachmentUploadId != null) {
            throw new IllegalStateException("An assignment may have only one attachment.");
        }
        this.attachmentUploadId = uploadId;
    }

    public UUID getId() { return id; }
    public UUID getClassroomId() { return classroomId; }
    public UUID getTeacherId() { return teacherId; }
    public String getTitle() { return title; }
    public String getInstructions() { return instructions; }
    public AssignmentStatus getStatus() { return status; }
    public Instant getDueAt() { return dueAt; }
    public boolean isCloseAfterDeadline() { return closeAfterDeadline; }
    public UUID getAttachmentUploadId() { return attachmentUploadId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
