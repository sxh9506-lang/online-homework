package com.onlinehomework.submission.api;

import com.onlinehomework.identity.api.AuthenticatedActor;
import com.onlinehomework.submission.application.SubmissionQueryService;
import com.onlinehomework.submission.api.SubmissionDtos.SubmissionResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assignments/{assignmentId}")
public class SubmissionController {
    private final SubmissionQueryService service;
    public SubmissionController(SubmissionQueryService service) { this.service = service; }
    @GetMapping("/submissions")
    @PreAuthorize("hasRole('TEACHER')")
    public List<SubmissionResponse> teacher(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {
        return service.forTeacher(AuthenticatedActor.from(jwt).userId(), assignmentId);
    }
    @GetMapping("/my-submissions")
    @PreAuthorize("hasRole('STUDENT')")
    public List<SubmissionResponse> student(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID assignmentId) {
        return service.forStudent(AuthenticatedActor.from(jwt).userId(), assignmentId);
    }
}
