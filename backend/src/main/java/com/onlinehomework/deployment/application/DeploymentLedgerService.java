package com.onlinehomework.deployment.application;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.deployment.domain.DeploymentAttemptStatus;
import com.onlinehomework.deployment.domain.DeploymentDelivery;
import com.onlinehomework.deployment.persistence.DeploymentDeliveryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Service
public class DeploymentLedgerService {
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern DEPLOYMENT_SHA = Pattern.compile("^[0-9a-f]{40}$");

    private final DeploymentDeliveryRepository deliveries;
    private final TransactionTemplate transactions;
    private final String allowedRepository;

    public DeploymentLedgerService(DeploymentDeliveryRepository deliveries,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${app.deployment-ledger.allowed-repository:sxh9506-lang/online-homework}")
                                   String allowedRepository) {
        this.deliveries = deliveries;
        this.transactions = new TransactionTemplate(transactionManager);
        this.allowedRepository = normalizeRepository(allowedRepository);
    }

    public ClaimResult claim(String repository, String environment, String deploymentSha, int pullRequestNumber) {
        Key key = validateKey(repository, environment, deploymentSha, pullRequestNumber);
        try {
            return transactions.execute(status -> claimInTransaction(key));
        } catch (DomainException exception) {
            throw exception;
        } catch (DataIntegrityViolationException duplicateOrConstraintFailure) {
            return recoverConcurrentInsert(key);
        } catch (RuntimeException exception) {
            throw ledgerUnavailable();
        }
    }

    public ClaimResult find(String repository, String environment, String deploymentSha) {
        Key key = validateKey(repository, environment, deploymentSha, 1);
        try {
            return deliveries.findByRepositoryNameAndTargetEnvironmentAndDeploymentSha(
                            key.repository(), key.environment(), key.deploymentSha())
                    .map(delivery -> result(delivery, false))
                    .orElseThrow(() -> DomainException.notFound("DEPLOYMENT_CLAIM_NOT_FOUND",
                            "Deployment ledger entry was not found."));
        } catch (DomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ledgerUnavailable();
        }
    }

    public ClaimResult markRequesting(UUID claimId) {
        return update(claimId, delivery -> delivery.markRequesting(Instant.now()));
    }

    public ClaimResult markRequested(UUID claimId) {
        return update(claimId, delivery -> delivery.markRequested(Instant.now()));
    }

    public ClaimResult markSucceeded(UUID claimId) {
        return update(claimId, delivery -> delivery.markSucceeded(Instant.now()));
    }

    public ClaimResult markSafeFailure(UUID claimId) {
        return update(claimId, delivery -> delivery.markSafeFailure(Instant.now()));
    }

    public ClaimResult markUnknown(UUID claimId) {
        return update(claimId, delivery -> delivery.markUnknown(Instant.now()));
    }

    public ClaimResult reconcile(UUID claimId, ReconciliationOutcome outcome) {
        if (outcome == null) {
            throw DomainException.invalid("INVALID_RECONCILIATION_OUTCOME", "A reconciliation outcome is required.");
        }
        return update(claimId, delivery -> {
            Instant now = Instant.now();
            switch (outcome) {
                case DEPLOYED -> delivery.reconcileAsDeployed(now);
                case NOT_DEPLOYED -> delivery.reconcileAsNotDeployed(now);
                case UNKNOWN -> delivery.reconcileAsUnknown(now);
            }
        });
    }

    private ClaimResult claimInTransaction(Key key) {
        Optional<DeploymentDelivery> existing = deliveries.findByKeyForUpdate(
                key.repository(), key.environment(), key.deploymentSha());
        if (existing.isPresent()) {
            DeploymentDelivery delivery = existing.get();
            if (delivery.getStatus() == DeploymentAttemptStatus.SAFE_FAILURE) {
                delivery.reclaimAfterSafeFailure(Instant.now());
                return result(delivery, true);
            }
            return result(delivery, false);
        }

        DeploymentDelivery delivery = new DeploymentDelivery(UUID.randomUUID(), key.repository(), key.environment(),
                key.deploymentSha(), key.pullRequestNumber(), Instant.now());
        deliveries.saveAndFlush(delivery);
        return result(delivery, true);
    }

    private ClaimResult recoverConcurrentInsert(Key key) {
        try {
            return transactions.execute(status -> {
                DeploymentDelivery delivery = deliveries.findByKeyForUpdate(
                                key.repository(), key.environment(), key.deploymentSha())
                        .orElseThrow(DeploymentLedgerService::ledgerUnavailable);
                if (delivery.getStatus() == DeploymentAttemptStatus.SAFE_FAILURE) {
                    delivery.reclaimAfterSafeFailure(Instant.now());
                    return result(delivery, true);
                }
                return result(delivery, false);
            });
        } catch (DomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ledgerUnavailable();
        }
    }

    private ClaimResult update(UUID claimId, Consumer<DeploymentDelivery> mutation) {
        if (claimId == null) {
            throw DomainException.invalid("INVALID_DEPLOYMENT_CLAIM", "A deployment claim ID is required.");
        }
        try {
            return transactions.execute(status -> {
                DeploymentDelivery delivery = deliveries.findByIdForUpdate(claimId)
                        .orElseThrow(() -> DomainException.notFound("DEPLOYMENT_CLAIM_NOT_FOUND",
                                "Deployment ledger entry was not found."));
                if (!allowedRepository.equals(delivery.getRepositoryName())) {
                    throw DomainException.forbidden();
                }
                try {
                    mutation.accept(delivery);
                } catch (IllegalStateException exception) {
                    throw DomainException.conflict("DEPLOYMENT_LEDGER_STATE_CONFLICT",
                            "The deployment ledger state does not allow this transition.");
                }
                return result(delivery, false);
            });
        } catch (DomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ledgerUnavailable();
        }
    }

    private Key validateKey(String repository, String environment, String deploymentSha, int pullRequestNumber) {
        if (repository == null || !REPOSITORY.matcher(repository.trim()).matches()
                || environment == null || deploymentSha == null || pullRequestNumber < 1) {
            throw DomainException.invalid("INVALID_DEPLOYMENT_CLAIM", "Deployment claim metadata is invalid.");
        }
        String normalizedRepository = normalizeRepository(repository);
        if (!allowedRepository.equals(normalizedRepository)) {
            throw DomainException.forbidden();
        }
        String normalizedEnvironment = environment.trim().toLowerCase(Locale.ROOT);
        if (!normalizedEnvironment.equals("test-api") && !normalizedEnvironment.equals("test-web")) {
            throw DomainException.invalid("INVALID_DEPLOYMENT_CLAIM", "Deployment environment is not allowed.");
        }
        String normalizedSha = deploymentSha.trim().toLowerCase(Locale.ROOT);
        if (!DEPLOYMENT_SHA.matcher(normalizedSha).matches()) {
            throw DomainException.invalid("INVALID_DEPLOYMENT_CLAIM", "Deployment SHA must be a full 40-character commit ID.");
        }
        return new Key(normalizedRepository, normalizedEnvironment, normalizedSha, pullRequestNumber);
    }

    private static String normalizeRepository(String repository) {
        return repository == null ? "" : repository.trim().toLowerCase(Locale.ROOT);
    }

    private static ClaimResult result(DeploymentDelivery delivery, boolean acquired) {
        return new ClaimResult(delivery.getId(), acquired, delivery.getStatus(), delivery.getAttemptCount());
    }

    private static DomainException ledgerUnavailable() {
        return new DomainException("DEPLOYMENT_LEDGER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                "The deployment idempotency ledger is temporarily unavailable.", true);
    }

    private record Key(String repository, String environment, String deploymentSha, int pullRequestNumber) { }

    public record ClaimResult(UUID claimId, boolean acquired, DeploymentAttemptStatus status, int attemptCount) { }

    public enum ReconciliationOutcome { DEPLOYED, NOT_DEPLOYED, UNKNOWN }
}
