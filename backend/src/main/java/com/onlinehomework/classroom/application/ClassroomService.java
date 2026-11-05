package com.onlinehomework.classroom.application;

import com.onlinehomework.classroom.api.ClassroomDtos.AddMemberRequest;
import com.onlinehomework.classroom.api.ClassroomDtos.ClassResponse;
import com.onlinehomework.classroom.api.ClassroomDtos.CreateClassRequest;
import com.onlinehomework.classroom.api.ClassroomDtos.MemberResponse;
import com.onlinehomework.classroom.domain.ClassMembership;
import com.onlinehomework.classroom.domain.Classroom;
import com.onlinehomework.classroom.persistence.ClassMembershipRepository;
import com.onlinehomework.classroom.persistence.ClassroomRepository;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.identity.domain.UserAccount;
import com.onlinehomework.identity.domain.UserRole;
import com.onlinehomework.identity.persistence.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.List;
import java.util.UUID;

@Service
public class ClassroomService {
    private final ClassroomRepository classrooms;
    private final ClassMembershipRepository memberships;
    private final UserAccountRepository users;
    private final Clock clock;

    public ClassroomService(ClassroomRepository classrooms, ClassMembershipRepository memberships,
                            UserAccountRepository users, Clock clock) {
        this.classrooms = classrooms;
        this.memberships = memberships;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public ClassResponse create(UUID teacherId, CreateClassRequest request) {
        String zone;
        try {
            zone = ZoneId.of(request.timeZone().trim()).getId();
        } catch (DateTimeException exception) {
            throw DomainException.invalid("INVALID_TIME_ZONE", "timeZone must be a valid IANA time-zone ID.");
        }
        Classroom classroom = classrooms.save(new Classroom(UUID.randomUUID(), teacherId, request.name().trim(),
                request.description(), zone, clock.instant()));
        return response(classroom);
    }

    @Transactional(readOnly = true)
    public List<ClassResponse> list(UUID actorId, String role) {
        if ("TEACHER".equals(role)) {
            return classrooms.findAllByTeacherIdOrderByCreatedAtDesc(actorId).stream().map(this::response).toList();
        }
        List<UUID> ids = memberships.findAllByStudentIdOrderByAddedAtDesc(actorId).stream()
                .map(ClassMembership::getClassroomId).toList();
        return classrooms.findAllById(ids).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public ClassResponse get(UUID actorId, String role, UUID classroomId) {
        Classroom classroom = classrooms.findById(classroomId)
                .orElseThrow(() -> DomainException.notFound("CLASS_NOT_FOUND", "Class was not found."));
        authorizeClass(actorId, role, classroom);
        return response(classroom);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID teacherId, UUID classroomId) {
        Classroom classroom = requireOwnedClass(teacherId, classroomId);
        return memberships.findAllByClassroomIdOrderByAddedAtAsc(classroom.getId()).stream()
                .map(membership -> users.findById(membership.getStudentId())
                        .map(user -> new MemberResponse(user.getId(), user.getEmail(), user.getDisplayName(), membership.getAddedAt()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public MemberResponse addMember(UUID teacherId, UUID classroomId, AddMemberRequest request) {
        Classroom classroom = classrooms.findByIdForUpdate(classroomId)
                .orElseThrow(() -> DomainException.notFound("CLASS_NOT_FOUND", "Class was not found."));
        requireOwner(teacherId, classroom);
        String exactEmail = request.email().trim();
        UserAccount student = users.findByEmail(exactEmail)
                .filter(user -> user.getRole() == UserRole.STUDENT)
                .orElseThrow(() -> DomainException.notFound("STUDENT_ACCOUNT_NOT_FOUND",
                        "No registered student account matches this email. Ask the student to register first."));
        if (!student.isEmailVerified()) {
            throw new DomainException("STUDENT_EMAIL_NOT_VERIFIED", HttpStatus.CONFLICT,
                    "The student must verify the login email before joining a class.");
        }
        if (memberships.existsByClassroomIdAndStudentId(classroomId, student.getId())) {
            throw DomainException.conflict("MEMBER_ALREADY_EXISTS", "Student is already a member of this class.");
        }
        ClassMembership membership = memberships.save(new ClassMembership(UUID.randomUUID(), classroomId,
                student.getId(), clock.instant()));
        return new MemberResponse(student.getId(), student.getEmail(), student.getDisplayName(), membership.getAddedAt());
    }

    @Transactional
    public void removeMember(UUID teacherId, UUID classroomId, UUID studentId) {
        requireOwnedClass(teacherId, classroomId);
        if (memberships.deleteByClassroomIdAndStudentId(classroomId, studentId) == 0) {
            throw DomainException.notFound("CLASS_MEMBER_NOT_FOUND", "Class member was not found.");
        }
    }

    @Transactional(readOnly = true)
    public Classroom requireOwnedClass(UUID teacherId, UUID classroomId) {
        return classrooms.findById(classroomId)
                .filter(classroom -> classroom.getTeacherId().equals(teacherId))
                .orElseThrow(DomainException::forbidden);
    }

    @Transactional(readOnly = true)
    public Classroom requireClassMember(UUID studentId, UUID classroomId) {
        Classroom classroom = classrooms.findById(classroomId)
                .orElseThrow(() -> DomainException.notFound("CLASS_NOT_FOUND", "Class was not found."));
        if (!memberships.existsByClassroomIdAndStudentId(classroomId, studentId)) {
            throw DomainException.forbidden();
        }
        return classroom;
    }

    private void authorizeClass(UUID actorId, String role, Classroom classroom) {
        if ("TEACHER".equals(role)) {
            requireOwner(actorId, classroom);
        } else if (!memberships.existsByClassroomIdAndStudentId(classroom.getId(), actorId)) {
            throw DomainException.forbidden();
        }
    }

    private void requireOwner(UUID teacherId, Classroom classroom) {
        if (!classroom.getTeacherId().equals(teacherId)) {
            throw DomainException.forbidden();
        }
    }

    private ClassResponse response(Classroom classroom) {
        return new ClassResponse(classroom.getId(), classroom.getName(), classroom.getDescription(),
                classroom.getTimeZone(), classroom.getCreatedAt());
    }
}
