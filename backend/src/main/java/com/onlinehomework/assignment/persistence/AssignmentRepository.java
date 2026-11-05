package com.onlinehomework.assignment.persistence;

import com.onlinehomework.assignment.domain.Assignment;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    List<Assignment> findAllByClassroomIdOrderByCreatedAtDesc(UUID classroomId);

    List<Assignment> findAllByClassroomIdAndStatusOrderByCreatedAtDesc(UUID classroomId, AssignmentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.id = :id")
    Optional<Assignment> findByIdForUpdate(@Param("id") UUID id);
}
