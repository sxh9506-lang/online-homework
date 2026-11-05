package com.onlinehomework.submission.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CurrentPublishedGradeQuery {
    Optional<PublishedGradeView> findCurrentPublishedGrade(UUID submissionId);

    record PublishedGradeView(UUID id, UUID submissionId, int revision, BigDecimal score,
                              String feedback, Instant createdAt, Instant publishedAt) { }
}
