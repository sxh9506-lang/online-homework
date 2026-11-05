package com.onlinehomework.grading.application;

import com.onlinehomework.grading.domain.GradeStatus;
import com.onlinehomework.grading.persistence.GradeRevisionRepository;
import com.onlinehomework.submission.application.CurrentPublishedGradeQuery;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PublishedGradeQueryAdapter implements CurrentPublishedGradeQuery {
    private final GradeRevisionRepository grades;

    public PublishedGradeQueryAdapter(GradeRevisionRepository grades) {
        this.grades = grades;
    }

    @Override
    public Optional<PublishedGradeView> findCurrentPublishedGrade(UUID submissionId) {
        return grades.findFirstBySubmissionIdAndStatusOrderByRevisionDesc(submissionId, GradeStatus.PUBLISHED)
                .map(grade -> new PublishedGradeView(grade.getId(), grade.getSubmissionId(), grade.getRevision(),
                        grade.getStatus().name(), grade.getScore(), grade.getFeedback(), grade.getCreatedAt(),
                        grade.getPublishedAt()));
    }
}
