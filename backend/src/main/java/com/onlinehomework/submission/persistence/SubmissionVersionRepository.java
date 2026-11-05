package com.onlinehomework.submission.persistence;

import com.onlinehomework.submission.domain.SubmissionVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionVersionRepository extends JpaRepository<SubmissionVersion, UUID> {
    @Query("select coalesce(max(s.version), 0) from SubmissionVersion s where s.assignmentId = :assignmentId and s.studentId = :studentId")
    int findMaxVersion(@Param("assignmentId") UUID assignmentId, @Param("studentId") UUID studentId);

    List<SubmissionVersion> findAllByAssignmentIdAndStudentIdOrderByVersionDesc(UUID assignmentId, UUID studentId);

    List<SubmissionVersion> findAllByAssignmentIdOrderByStudentIdAscVersionDesc(UUID assignmentId);

    Optional<SubmissionVersion> findByIdAndAssignmentId(UUID id, UUID assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SubmissionVersion s where s.id = :id")
    Optional<SubmissionVersion> findByIdForUpdate(@Param("id") UUID id);
}
