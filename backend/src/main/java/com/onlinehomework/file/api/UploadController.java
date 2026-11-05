package com.onlinehomework.file.api;

import com.onlinehomework.file.api.UploadDtos.CompleteUploadResponse;
import com.onlinehomework.file.api.UploadDtos.CreateUploadRequest;
import com.onlinehomework.file.api.UploadDtos.DownloadLinkResponse;
import com.onlinehomework.file.api.UploadDtos.UploadIntentResponse;
import com.onlinehomework.file.application.UploadService;
import com.onlinehomework.identity.api.AuthenticatedActor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadIntentResponse createSubmission(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID assignmentId,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @Valid @RequestBody CreateUploadRequest request) {
        return uploads.createSubmissionIntent(AuthenticatedActor.from(jwt).userId(), assignmentId, idempotencyKey, request);
    }

    @PostMapping("/assignments/{assignmentId}/attachment-upload-intents")
    @PreAuthorize("hasRole('TEACHER')")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadIntentResponse createAssignmentAttachment(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable UUID assignmentId,
                                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                           @Valid @RequestBody CreateUploadRequest request) {
        return uploads.createAssignmentAttachmentIntent(AuthenticatedActor.from(jwt).userId(), assignmentId,
                idempotencyKey, request);
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public CompleteUploadResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID uploadId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return uploads.complete(actor.userId(), actor.role(), uploadId);
    }

    @PostMapping("/assignments/{assignmentId}/attachment-download")
    public DownloadLinkResponse assignmentAttachment(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable UUID assignmentId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return uploads.assignmentAttachment(actor.userId(), actor.role(), assignmentId);
    }

    @PostMapping("/submissions/{submissionId}/attachment-download")
    public DownloadLinkResponse submissionAttachment(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable UUID submissionId) {
        AuthenticatedActor actor = AuthenticatedActor.from(jwt);
        return uploads.submissionAttachment(actor.userId(), actor.role(), submissionId);
    }
}
