package com.onlinehomework.grading.domain;
import jakarta.persistence.*; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="grade_revisions") public class GradeRevision {
 @Id private UUID id; @Column(name="submission_id",nullable=false) private UUID submissionId; @Column(name="teacher_id",nullable=false) private UUID teacherId;
 @Column(nullable=false) private int revision; @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private GradeStatus status;
 private BigDecimal score; @Column(nullable=false,length=10000) private String feedback; @Column(nullable=false) private Instant createdAt; private Instant publishedAt;
 protected GradeRevision() {} public GradeRevision(UUID id,UUID submissionId,UUID teacherId,int revision,BigDecimal score,String feedback,Instant createdAt){this.id=id;this.submissionId=submissionId;this.teacherId=teacherId;this.revision=revision;this.score=score;this.feedback=feedback;this.status=GradeStatus.DRAFT;this.createdAt=createdAt;}
 public void publish(Instant now){if(status!=GradeStatus.DRAFT) throw new IllegalStateException(); status=GradeStatus.PUBLISHED; publishedAt=now;}
 public UUID getId(){return id;} public UUID getSubmissionId(){return submissionId;} public UUID getTeacherId(){return teacherId;} public int getRevision(){return revision;} public GradeStatus getStatus(){return status;} public BigDecimal getScore(){return score;} public String getFeedback(){return feedback;} public Instant getCreatedAt(){return createdAt;} public Instant getPublishedAt(){return publishedAt;}
}
