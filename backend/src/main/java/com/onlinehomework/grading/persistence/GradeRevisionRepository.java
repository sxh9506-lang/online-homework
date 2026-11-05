package com.onlinehomework.grading.persistence;
import com.onlinehomework.grading.domain.GradeRevision; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import java.util.*;
public interface GradeRevisionRepository extends JpaRepository<GradeRevision,UUID>{ @Query("select coalesce(max(g.revision),0) from GradeRevision g where g.submissionId=:id") int maxRevision(@Param("id") UUID id); List<GradeRevision> findAllBySubmissionIdOrderByRevisionDesc(UUID id); }
