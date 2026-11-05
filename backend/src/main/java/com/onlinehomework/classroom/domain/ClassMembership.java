package com.onlinehomework.classroom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "class_memberships")
public class ClassMembership {
    @Id
    private UUID id;
    @Column(name = "classroom_id", nullable = false)
    private UUID classroomId;
    @Column(name = "student_id", nullable = false)
    private UUID studentId;
    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected ClassMembership() { }

    public ClassMembership(UUID id, UUID classroomId, UUID studentId, Instant addedAt) {
        this.id = id;
        this.classroomId = classroomId;
        this.studentId = studentId;
        this.addedAt = addedAt;
    }

    public UUID getId() { return id; }
    public UUID getClassroomId() { return classroomId; }
    public UUID getStudentId() { return studentId; }
    public Instant getAddedAt() { return addedAt; }
}
