package com.onlinehomework.classroom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "classrooms")
public class Classroom {
    @Id
    private UUID id;
    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 2000)
    private String description;
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected Classroom() { }

    public Classroom(UUID id, UUID teacherId, String name, String description, String timeZone, Instant createdAt) {
        this.id = id;
        this.teacherId = teacherId;
        this.name = name;
        this.description = description;
        this.timeZone = timeZone;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getTeacherId() { return teacherId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getTimeZone() { return timeZone; }
    public Instant getCreatedAt() { return createdAt; }
}
