package com.onlinehomework.deployment.persistence;

import com.onlinehomework.deployment.domain.DeploymentDelivery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeploymentDeliveryRepository extends JpaRepository<DeploymentDelivery, UUID> {
    Optional<DeploymentDelivery> findByRepositoryNameAndTargetEnvironmentAndDeploymentSha(
            String repositoryName, String targetEnvironment, String deploymentSha);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeploymentDelivery d where d.repositoryName = :repositoryName "
            + "and d.targetEnvironment = :targetEnvironment and d.deploymentSha = :deploymentSha")
    Optional<DeploymentDelivery> findByKeyForUpdate(@Param("repositoryName") String repositoryName,
                                                     @Param("targetEnvironment") String targetEnvironment,
                                                     @Param("deploymentSha") String deploymentSha);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeploymentDelivery d where d.id = :id")
    Optional<DeploymentDelivery> findByIdForUpdate(@Param("id") UUID id);
}
