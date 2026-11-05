package com.onlinehomework.grading.persistence;

import com.onlinehomework.grading.domain.GradeRevision;
import com.onlinehomework.grading.domain.GradeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GradeRevisionRepository extends JpaRepository<GradeRevision, UUID> {
    @Query("select coalesce(max(g.revision), 0) from GradeRevision g where g.submissionId = :id")
    int maxRevision(@Param("id") UUID submissionId);

    List<GradeRevision> findAllBySubmissionIdOrderByRevisionDesc(UUID submissionId);

    Optional<GradeRevision> findFirstBySubmissionIdAndStatusOrderByRevisionDesc(UUID submissionId, GradeStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GradeRevision g where g.id = :id")
    Optional<GradeRevision> findByIdForUpdate(@Param("id") UUID gradeId);
}
