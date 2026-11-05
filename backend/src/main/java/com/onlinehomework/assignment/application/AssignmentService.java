package com.onlinehomework.assignment.application;

import com.onlinehomework.assignment.api.AssignmentDtos.AssignmentResponse;
import com.onlinehomework.assignment.api.AssignmentDtos.CreateAssignmentRequest;
import com.onlinehomework.assignment.api.AssignmentDtos.UpdateAssignmentRequest;
import com.onlinehomework.assignment.domain.Assignment;
import com.onlinehomework.assignment.domain.AssignmentStatus;
import com.onlinehomework.assignment.persistence.AssignmentRepository;
import com.onlinehomework.classroom.application.ClassroomService;
import com.onlinehomework.classroom.domain.Classroom;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.common.application.FileMetadataQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AssignmentService {
    private final AssignmentRepository assignments;
    private final ClassroomService classrooms;
    private final FileMetadataQuery files;
    private final Clock clock;

    public AssignmentService(AssignmentRepository assignments, ClassroomService classrooms,
                             FileMetadataQuery files, Clock clock) {
        this.assignments = assignments;
        this.classrooms = classrooms;
        this.files = files;
        this.clock = clock;
    }

    @Transactional
    public AssignmentResponse createDraft(UUID teacherId, UUID classId, CreateAssignmentRequest request) {
        classrooms.requireOwnedClass(teacherId, classId);
        Assignment assignment = assignments.save(new Assignment(UUID.randomUUID(), classId, teacherId,
                request.title().trim(), request.instructions(), request.dueAt(), request.closeAfterDeadline(),
                clock.instant()));
        return response(assignment);
    }

    @Transactional(readOnly = true)
    public List<AssignmentResponse> list(UUID actorId, String role, UUID classId) {
        List<Assignment> list;
        if ("TEACHER".equals(role)) {
            classrooms.requireOwnedClass(actorId, classId);
            list = assignments.findAllByClassroomIdOrderByCreatedAtDesc(classId);
        } else {
            classrooms.requireClassMember(actorId, classId);
            list = assignments.findAllByClassroomIdAndStatusOrderByCreatedAtDesc(classId, AssignmentStatus.PUBLISHED);
        }
        return list.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public AssignmentResponse get(UUID actorId, String role, UUID assignmentId) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        authorizeRead(actorId, role, assignment);
        return response(assignment);
    }

    @Transactional
    public AssignmentResponse updateDraft(UUID teacherId, UUID assignmentId, UpdateAssignmentRequest request) {
        Assignment assignment = assignments.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireOwnedClass(teacherId, assignment.getClassroomId());
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            throw DomainException.conflict("ASSIGNMENT_NOT_DRAFT", "Published assignments cannot be edited in the MVP.");
        }
        assignment.updateDraft(request.title() == null ? null : request.title().trim(), request.instructions(),
                request.dueAt(), request.closeAfterDeadline());
        return response(assignment);
    }

    @Transactional
    public AssignmentResponse publish(UUID teacherId, UUID assignmentId) {
        Assignment assignment = assignments.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireOwnedClass(teacherId, assignment.getClassroomId());
        try {
            assignment.publish(clock.instant());
        } catch (IllegalStateException exception) {
            throw DomainException.conflict("ASSIGNMENT_NOT_DRAFT", "Assignment is not a draft.");
        }
        return response(assignment);
    }

    @Transactional(readOnly = true)
    public Assignment requireOwnedAssignment(UUID teacherId, UUID assignmentId) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireOwnedClass(teacherId, assignment.getClassroomId());
        return assignment;
    }

    @Transactional
    public AssignmentPolicy lockForStudentSubmission(UUID studentId, UUID assignmentId) {
        Assignment assignment = assignments.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireClassMember(studentId, assignment.getClassroomId());
        requirePublished(assignment);
        return policy(assignment);
    }

    @Transactional
    public AssignmentPolicy lockForTeacherAttachment(UUID teacherId, UUID assignmentId) {
        Assignment assignment = assignments.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireOwnedClass(teacherId, assignment.getClassroomId());
        if (assignment.getStatus() != AssignmentStatus.DRAFT || assignment.getAttachmentUploadId() != null) {
            throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_NOT_ALLOWED", "This draft cannot accept another attachment.");
        }
        return policy(assignment);
    }

    @Transactional
    public AssignmentResponse attachValidatedFile(UUID teacherId, UUID assignmentId, UUID uploadIntentId) {
        Assignment assignment = assignments.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireOwnedClass(teacherId, assignment.getClassroomId());
        if (assignment.getStatus() != AssignmentStatus.DRAFT) {
            throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_NOT_ALLOWED", "Only drafts can accept an attachment.");
        }
        try {
            assignment.attachUpload(uploadIntentId);
        } catch (IllegalStateException exception) {
            if (!uploadIntentId.equals(assignment.getAttachmentUploadId())) {
                throw DomainException.conflict("ASSIGNMENT_ATTACHMENT_EXISTS", "This assignment already has an attachment.");
            }
        }
        return response(assignment);
    }

    @Transactional(readOnly = true)
    public Assignment requireStudentAssignment(UUID studentId, UUID assignmentId) {
        Assignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> DomainException.notFound("ASSIGNMENT_NOT_FOUND", "Assignment was not found."));
        classrooms.requireClassMember(studentId, assignment.getClassroomId());
        requirePublished(assignment);
        return assignment;
    }

    private void authorizeRead(UUID actorId, String role, Assignment assignment) {
        if ("TEACHER".equals(role)) {
            classrooms.requireOwnedClass(actorId, assignment.getClassroomId());
        } else {
            classrooms.requireClassMember(actorId, assignment.getClassroomId());
            requirePublished(assignment);
        }
    }

    private void requirePublished(Assignment assignment) {
        if (assignment.getStatus() != AssignmentStatus.PUBLISHED) {
            throw DomainException.notFound("ASSIGNMENT_NOT_PUBLISHED", "Assignment was not found.");
        }
    }

    private AssignmentPolicy policy(Assignment assignment) {
        return new AssignmentPolicy(assignment.getId(), assignment.getClassroomId(), assignment.getDueAt(),
                assignment.isCloseAfterDeadline(), assignment.getStatus());
    }

    public AssignmentResponse response(Assignment assignment) {
        return new AssignmentResponse(assignment.getId(), assignment.getClassroomId(), assignment.getTitle(),
                assignment.getInstructions(), assignment.getStatus(), assignment.getDueAt(),
                assignment.isCloseAfterDeadline(), assignment.getAttachmentUploadId() == null ? null
                : files.findCompletedFile(assignment.getAttachmentUploadId()).orElse(null),
                assignment.getCreatedAt(), assignment.getPublishedAt());
    }

    public record AssignmentPolicy(UUID assignmentId, UUID classId, Instant dueAt,
                                   boolean closeAfterDeadline, AssignmentStatus status) { }
}
