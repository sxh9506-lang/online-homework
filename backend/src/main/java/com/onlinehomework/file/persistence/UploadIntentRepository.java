package com.onlinehomework.file.persistence;

import com.onlinehomework.file.domain.UploadIntent;
import com.onlinehomework.file.domain.UploadPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UploadIntentRepository extends JpaRepository<UploadIntent, UUID> {
    Optional<UploadIntent> findByActorIdAndAssignmentIdAndPurposeAndIdempotencyKeyHash(
            UUID actorId, UUID assignmentId, UploadPurpose purpose, String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UploadIntent u where u.id = :id")
    Optional<UploadIntent> findByIdForUpdate(@Param("id") UUID id);
}
