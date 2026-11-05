package com.onlinehomework.classroom.api;

import com.onlinehomework.classroom.api.ClassroomDtos.AddMemberRequest;
import com.onlinehomework.classroom.api.ClassroomDtos.ClassResponse;
import com.onlinehomework.classroom.api.ClassroomDtos.CreateClassRequest;
import com.onlinehomework.classroom.api.ClassroomDtos.MemberResponse;
import com.onlinehomework.classroom.application.ClassroomService;
import com.onlinehomework.identity.api.AuthenticatedActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassroomController {
    private final ClassroomService service;

    public ClassroomController(ClassroomService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClassResponse> list(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return service.list(actor.userId(), actor.role());
    }

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateClassRequest request) {
        return service.create(AuthenticatedActor.from(jwt).userId(), request);
    }

    @GetMapping("/{classId}")
    public ClassResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return service.get(actor.userId(), actor.role(), classId);
    }

    @GetMapping("/{classId}/members")
    @PreAuthorize("hasRole('TEACHER')")
    public List<MemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId) {
        return service.listMembers(AuthenticatedActor.from(jwt).userId(), classId);
    }

    @PostMapping("/{classId}/members")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId,
                                    @Valid @RequestBody AddMemberRequest request) {
        return service.addMember(AuthenticatedActor.from(jwt).userId(), classId, request);
    }

    @DeleteMapping("/{classId}/members/{studentId}")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId,
                             @PathVariable UUID studentId) {
        service.removeMember(AuthenticatedActor.from(jwt).userId(), classId, studentId);
    }
}
