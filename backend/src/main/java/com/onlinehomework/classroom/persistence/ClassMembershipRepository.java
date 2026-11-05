package com.onlinehomework.classroom.persistence;

import com.onlinehomework.classroom.domain.ClassMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClassMembershipRepository extends JpaRepository<ClassMembership, UUID> {
    boolean existsByClassroomIdAndStudentId(UUID classroomId, UUID studentId);

    List<ClassMembership> findAllByClassroomIdOrderByAddedAtAsc(UUID classroomId);

    List<ClassMembership> findAllByStudentIdOrderByAddedAtDesc(UUID studentId);

    Optional<ClassMembership> findByClassroomIdAndStudentId(UUID classroomId, UUID studentId);

    long deleteByClassroomIdAndStudentId(UUID classroomId, UUID studentId);
}
