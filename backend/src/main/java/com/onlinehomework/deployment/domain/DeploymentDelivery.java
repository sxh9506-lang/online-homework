package com.onlinehomework.deployment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deployment_delivery_ledger", uniqueConstraints = @UniqueConstraint(
        name = "deployment_delivery_ledger_key_uq",
        columnNames = {"repository_name", "target_environment", "deployment_sha"}))
public class DeploymentDelivery {
    @Id
    private UUID id;

    @Column(name = "repository_name", nullable = false, length = 255)
    private String repositoryName;

    @Column(name = "target_environment", nullable = false, length = 32)
    private String targetEnvironment;

    @Column(name = "deployment_sha", nullable = false, length = 40)
    private String deploymentSha;

    @Column(name = "pull_request_number", nullable = false)
    private int pullRequestNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DeploymentAttemptStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "request_started_at")
    private Instant requestStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected DeploymentDelivery() { }

    public DeploymentDelivery(UUID id, String repositoryName, String targetEnvironment,
                              String deploymentSha, int pullRequestNumber, Instant now) {
        this.id = id;
        this.repositoryName = repositoryName;
        this.targetEnvironment = targetEnvironment;
        this.deploymentSha = deploymentSha;
        this.pullRequestNumber = pullRequestNumber;
        this.status = DeploymentAttemptStatus.CLAIMED;
        this.attemptCount = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reclaimAfterSafeFailure(Instant now) {
        if (status != DeploymentAttemptStatus.SAFE_FAILURE) {
            throw new IllegalStateException("Only a confirmed safe failure can be reclaimed.");
        }
        status = DeploymentAttemptStatus.CLAIMED;
        attemptCount += 1;
        requestStartedAt = null;
        completedAt = null;
        updatedAt = now;
    }

    public void markRequesting(Instant now) {
        requireState(DeploymentAttemptStatus.CLAIMED, DeploymentAttemptStatus.REQUESTING);
        if (status == DeploymentAttemptStatus.CLAIMED) {
            status = DeploymentAttemptStatus.REQUESTING;
            requestStartedAt = now;
            updatedAt = now;
        }
    }

    public void markRequested(Instant now) {
        requireState(DeploymentAttemptStatus.REQUESTING, DeploymentAttemptStatus.REQUESTED,
                DeploymentAttemptStatus.SUCCEEDED);
        if (status == DeploymentAttemptStatus.REQUESTING) {
            status = DeploymentAttemptStatus.REQUESTED;
            updatedAt = now;
        }
    }

    public void markSucceeded(Instant now) {
        requireState(DeploymentAttemptStatus.REQUESTED, DeploymentAttemptStatus.SUCCEEDED);
        if (status == DeploymentAttemptStatus.REQUESTED) {
            status = DeploymentAttemptStatus.SUCCEEDED;
            completedAt = now;
            updatedAt = now;
        }
    }

    public void markSafeFailure(Instant now) {
        requireState(DeploymentAttemptStatus.CLAIMED, DeploymentAttemptStatus.SAFE_FAILURE);
        if (status == DeploymentAttemptStatus.CLAIMED) {
            status = DeploymentAttemptStatus.SAFE_FAILURE;
            updatedAt = now;
        }
    }

    public void markUnknown(Instant now) {
        requireState(DeploymentAttemptStatus.REQUESTING, DeploymentAttemptStatus.REQUESTED,
                DeploymentAttemptStatus.UNKNOWN);
        if (status != DeploymentAttemptStatus.UNKNOWN) {
            status = DeploymentAttemptStatus.UNKNOWN;
            updatedAt = now;
        }
    }

    public void reconcileAsDeployed(Instant now) {
        requireState(DeploymentAttemptStatus.REQUESTING, DeploymentAttemptStatus.REQUESTED,
                DeploymentAttemptStatus.UNKNOWN, DeploymentAttemptStatus.SUCCEEDED);
        if (status != DeploymentAttemptStatus.SUCCEEDED) {
            status = DeploymentAttemptStatus.SUCCEEDED;
            completedAt = now;
            updatedAt = now;
        }
    }

    public void reconcileAsNotDeployed(Instant now) {
        requireState(DeploymentAttemptStatus.CLAIMED, DeploymentAttemptStatus.REQUESTING,
                DeploymentAttemptStatus.REQUESTED, DeploymentAttemptStatus.UNKNOWN,
                DeploymentAttemptStatus.SAFE_FAILURE);
        if (status != DeploymentAttemptStatus.SAFE_FAILURE) {
            status = DeploymentAttemptStatus.SAFE_FAILURE;
            completedAt = null;
            updatedAt = now;
        }
    }

    public void reconcileAsUnknown(Instant now) {
        requireState(DeploymentAttemptStatus.CLAIMED, DeploymentAttemptStatus.REQUESTING,
                DeploymentAttemptStatus.REQUESTED, DeploymentAttemptStatus.UNKNOWN);
        if (status != DeploymentAttemptStatus.UNKNOWN) {
            status = DeploymentAttemptStatus.UNKNOWN;
            updatedAt = now;
        }
    }

    private void requireState(DeploymentAttemptStatus... allowed) {
        for (DeploymentAttemptStatus value : allowed) {
            if (status == value) return;
        }
        throw new IllegalStateException("Invalid deployment ledger state transition.");
    }

    public UUID getId() { return id; }
    public String getRepositoryName() { return repositoryName; }
    public String getTargetEnvironment() { return targetEnvironment; }
    public String getDeploymentSha() { return deploymentSha; }
    public int getPullRequestNumber() { return pullRequestNumber; }
    public DeploymentAttemptStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRequestStartedAt() { return requestStartedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
