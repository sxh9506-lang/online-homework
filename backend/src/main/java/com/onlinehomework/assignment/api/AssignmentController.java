package com.onlinehomework.assignment.api;

import com.onlinehomework.assignment.api.AssignmentDtos.AssignmentResponse;
import com.onlinehomework.assignment.api.AssignmentDtos.CreateAssignmentRequest;
import com.onlinehomework.assignment.api.AssignmentDtos.UpdateAssignmentRequest;
import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.identity.api.AuthenticatedActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {
    private final AssignmentService assignments;

    public AssignmentController(AssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/classes/{classId}/assignments")
    public List<AssignmentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return assignments.list(actor.userId(), actor.role(), classId);
    }

    @PostMapping("/classes/{classId}/assignments")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId,
                                     @Valid @RequestBody CreateAssignmentRequest request) {
        return assignments.createDraft(AuthenticatedActor.from(jwt).userId(), classId, request);
    }

    @GetMapping("/assignments/{assignmentId}")
    public AssignmentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return assignments.get(actor.userId(), actor.role(), assignmentId);
    }

    @PatchMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId,
                                     @Valid @RequestBody UpdateAssignmentRequest request) {
        return assignments.updateDraft(AuthenticatedActor.from(jwt).userId(), assignmentId, request);
    }

    @PostMapping("/assignments/{assignmentId}/publish")
    @PreAuthorize("hasRole('TEACHER')")
    public AssignmentResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {
        return assignments.publish(AuthenticatedActor.from(jwt).userId(), assignmentId);
    }
}
