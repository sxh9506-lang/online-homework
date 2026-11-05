package com.onlinehomework.submission.application;

import com.onlinehomework.assignment.application.AssignmentService;
import com.onlinehomework.common.application.FileMetadataQuery;
import com.onlinehomework.submission.api.SubmissionDtos.SubmissionResponse;
import com.onlinehomework.submission.domain.SubmissionVersion;
import com.onlinehomework.submission.persistence.SubmissionVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SubmissionQueryService {
    private final SubmissionVersionRepository submissions;
    private final AssignmentService assignments;
    private final FileMetadataQuery files;
    private final CurrentPublishedGradeQuery currentGrades;

    public SubmissionQueryService(SubmissionVersionRepository submissions, AssignmentService assignments,
                                  FileMetadataQuery files, CurrentPublishedGradeQuery currentGrades) {
        this.submissions = submissions;
        this.assignments = assignments;
        this.files = files;
        this.currentGrades = currentGrades;
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> forTeacher(UUID teacherId, UUID assignmentId) {
        assignments.requireOwnedAssignment(teacherId, assignmentId);
        return submissions.findAllByAssignmentIdOrderByStudentIdAscVersionDesc(assignmentId)
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public List<SubmissionResponse> forStudent(UUID studentId, UUID assignmentId) {
        assignments.requireStudentAssignment(studentId, assignmentId);
        return submissions.findAllByAssignmentIdAndStudentIdOrderByVersionDesc(assignmentId, studentId)
                .stream().map(submission -> response(submission).withCurrentPublishedGrade(
                        currentGrades.findCurrentPublishedGrade(submission.getId()).orElse(null)))
                .toList();
    }

    private SubmissionResponse response(SubmissionVersion submission) {
        return new SubmissionResponse(submission.getId(), submission.getAssignmentId(), submission.getStudentId(),
                submission.getVersion(), submission.getText(), submission.getSubmittedAt(), submission.isLate(),
                files.findCompletedFile(submission.getUploadIntentId()).orElse(null), null);
    }
}
