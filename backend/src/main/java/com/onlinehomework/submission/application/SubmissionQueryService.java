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
    public SubmissionQueryService(SubmissionVersionRepository submissions, AssignmentService assignments,
                                  FileMetadataQuery files) {
        this.submissions = submissions; this.assignments = assignments; this.files = files;
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
                .stream().map(this::response).toList();
    }
    private SubmissionResponse response(SubmissionVersion s) {
        return new SubmissionResponse(s.getId(), s.getAssignmentId(), s.getStudentId(), s.getVersion(),
                s.getText(), s.getSubmittedAt(), s.isLate(), files.findCompletedFile(s.getUploadIntentId()).orElse(null), null);
    }
}
