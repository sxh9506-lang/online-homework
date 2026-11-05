package com.onlinehomework.grading.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "grade_revisions")
public class GradeRevision {
    @Id
    private UUID id;
    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;
    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;
    @Column(nullable = false)
    private int revision;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GradeStatus status;
    @Column(precision = 7, scale = 2)
    private BigDecimal score;
    @Column(nullable = false, length = 10000)
    private String feedback;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected GradeRevision() { }

    public GradeRevision(UUID id, UUID submissionId, UUID teacherId, int revision,
                         BigDecimal score, String feedback, Instant createdAt) {
        this.id = id;
        this.submissionId = submissionId;
        this.teacherId = teacherId;
        this.revision = revision;
        this.status = GradeStatus.DRAFT;
        this.score = score;
        this.feedback = feedback;
        this.createdAt = createdAt;
    }

    public void publish(Instant publishedAt) {
        if (status != GradeStatus.DRAFT) throw new IllegalStateException("Only draft grades can be published.");
        this.status = GradeStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    public UUID getId() { return id; }
    public UUID getSubmissionId() { return submissionId; }
    public UUID getTeacherId() { return teacherId; }
    public int getRevision() { return revision; }
    public GradeStatus getStatus() { return status; }
    public BigDecimal getScore() { return score; }
    public String getFeedback() { return feedback; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
