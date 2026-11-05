package com.onlinehomework.grading.application;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.grading.api.GradeDtos.CreateGradeRequest;
import com.onlinehomework.grading.api.GradeDtos.GradeResponse;
import com.onlinehomework.grading.domain.GradeRevision;
import com.onlinehomework.grading.persistence.GradeRevisionRepository;
import com.onlinehomework.submission.application.SubmissionService;
import com.onlinehomework.submission.domain.SubmissionVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class GradingService {
    private final GradeRevisionRepository grades;
    private final SubmissionService submissions;
    private final Clock clock;

    public GradingService(GradeRevisionRepository grades, SubmissionService submissions, Clock clock) {
        this.grades = grades;
        this.submissions = submissions;
        this.clock = clock;
    }

    @Transactional
    public GradeResponse create(UUID teacherId, UUID submissionId, CreateGradeRequest request) {
        SubmissionVersion submission = submissions.lockForTeacherSubmission(teacherId, submissionId);
        GradeRevision grade = grades.save(new GradeRevision(UUID.randomUUID(), submissionId, teacherId,
                grades.maxRevision(submissionId) + 1, request.score(), request.feedback(), clock.instant()));
        return view(grade);
    }

    @Transactional(readOnly = true)
    public List<GradeResponse> list(UUID teacherId, UUID submissionId) {
        submissions.requireTeacherSubmission(teacherId, submissionId);
        return grades.findAllBySubmissionIdOrderByRevisionDesc(submissionId).stream().map(this::view).toList();
    }

    @Transactional
    public GradeResponse publish(UUID teacherId, UUID gradeId) {
        GradeRevision grade = grades.findByIdForUpdate(gradeId)
                .orElseThrow(() -> DomainException.notFound("GRADE_NOT_FOUND", "Grade was not found."));
        submissions.lockForTeacherSubmission(teacherId, grade.getSubmissionId());
        try {
            grade.publish(clock.instant());
        } catch (IllegalStateException exception) {
            throw DomainException.conflict("GRADE_NOT_DRAFT", "Grade is already published.");
        }
        return view(grade);
    }

    private GradeResponse view(GradeRevision grade) {
        return new GradeResponse(grade.getId(), grade.getSubmissionId(), grade.getRevision(), grade.getStatus(),
                grade.getScore(), grade.getFeedback(), grade.getCreatedAt(), grade.getPublishedAt());
    }
}
