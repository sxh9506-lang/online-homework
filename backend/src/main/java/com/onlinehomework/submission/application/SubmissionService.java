package com.onlinehomework.submission.application;

import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.common.application.FileMetadataQuery;
import com.onlinehomework.submission.api.SubmissionDtos.SubmissionResponse;
import com.onlinehomework.submission.domain.SubmissionVersion;
import com.onlinehomework.submission.persistence.SubmissionVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SubmissionService {
    private final SubmissionVersionRepository submissions;
    private final AssignmentService assignments;
    private final FileMetadataQuery files;

    public SubmissionService(SubmissionVersionRepository submissions, AssignmentService assignments,
                             FileMetadataQuery files) {
        this.submissions = submissions;
        this.assignments = assignments;
        this.files = files;
    }

    @Transactional
    public SubmissionVersion createCompleted(UUID assignmentId, UUID studentId, UUID uploadIntentId,
                                             String text, Instant completedAt, boolean late) {
        int nextVersion = submissions.findMaxVersion(assignmentId, studentId) + 1;
        return submissions.save(new SubmissionVersion(UUID.randomUUID(), assignmentId, studentId,
                nextVersion, text, uploadIntentId, completedAt, late));
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> listForTeacher(UUID teacherId, UUID assignmentId) {
        assignments.requireOwnedAssignment(teacherId, assignmentId);
        return submissions.findAllByAssignmentIdOrderByStudentIdAscVersionDesc(assignmentId)
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> listMine(UUID studentId, UUID assignmentId) {
        assignments.requireStudentAssignment(studentId, assignmentId);
        return submissions.findAllByAssignmentIdAndStudentIdOrderByVersionDesc(assignmentId, studentId)
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SubmissionVersion requireTeacherSubmission(UUID teacherId, UUID submissionId) {
        SubmissionVersion submission = submissions.findById(submissionId)
                .orElseThrow(() -> DomainException.notFound("SUBMISSION_NOT_FOUND", "Submission was not found."));
        assignments.requireOwnedAssignment(teacherId, submission.getAssignmentId());
        return submission;
    }

    @Transactional
    public SubmissionVersion lockForTeacherSubmission(UUID teacherId, UUID submissionId) {
        SubmissionVersion submission = submissions.findByIdForUpdate(submissionId)
                .orElseThrow(() -> DomainException.notFound("SUBMISSION_NOT_FOUND", "Submission was not found."));
        assignments.requireOwnedAssignment(teacherId, submission.getAssignmentId());
        return submission;
    }

    @Transactional(readOnly = true)
    public SubmissionVersion requireStudentSubmission(UUID studentId, UUID submissionId) {
        SubmissionVersion submission = submissions.findById(submissionId)
                .orElseThrow(() -> DomainException.notFound("SUBMISSION_NOT_FOUND", "Submission was not found."));
        assignments.requireStudentAssignment(studentId, submission.getAssignmentId());
        if (!submission.getStudentId().equals(studentId)) {
            throw DomainException.notFound("SUBMISSION_NOT_FOUND", "Submission was not found.");
        }
        return submission;
    }

    @Transactional(readOnly = true)
    public SubmissionResponse getForStudent(UUID studentId, UUID submissionId) {
        return response(requireStudentSubmission(studentId, submissionId));
    }

    @Transactional(readOnly = true)
    public SubmissionResponse getForTeacher(UUID teacherId, UUID submissionId) {
        return response(requireTeacherSubmission(teacherId, submissionId));
    }

    public SubmissionResponse response(SubmissionVersion submission) {
        return new SubmissionResponse(submission.getId(), submission.getAssignmentId(), submission.getStudentId(),
                submission.getVersion(), submission.getText(), submission.getSubmittedAt(), submission.isLate(),
                files.findCompletedFile(submission.getUploadIntentId()).orElse(null), null);
    }
}
