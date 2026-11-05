package com.onlinehomework.submission.api;

import com.onlinehomework.common.api.FileMetadata;
import com.onlinehomework.submission.application.CurrentPublishedGradeQuery.PublishedGradeView;
import java.time.Instant;
import java.util.UUID;

public final class SubmissionDtos {
    private SubmissionDtos() {}
    public record SubmissionResponse(UUID id, UUID assignmentId, UUID studentUserId,
                                     int version, String text, Instant completedAt,
                                     boolean late, FileMetadata attachment,
                                     PublishedGradeView currentPublishedGrade) {
        public SubmissionResponse withCurrentPublishedGrade(PublishedGradeView grade) {
            return new SubmissionResponse(id, assignmentId, studentUserId, version, text, completedAt,
                    late, attachment, grade);
        }
    }
}
