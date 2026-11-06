package com.onlinehomework.deployment.api;

import com.onlinehomework.common.api.DomainException;
import com.onlinehomework.deployment.application.DeploymentLedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/internal/deployment-ledger")
public class DeploymentLedgerController {
    private static final String WORKFLOW_TOKEN_HEADER = "X-Deployment-Ledger-Token";
    private static final String OPERATOR_TOKEN_HEADER = "X-Deployment-Ledger-Operator-Token";
    private static final String REPOSITORY_HEADER = "X-Deployment-Ledger-Repository";

    private final DeploymentLedgerService ledger;
    private final String configuredWorkflowToken;
    private final String configuredOperatorToken;
    private final String allowedRepository;

    public DeploymentLedgerController(DeploymentLedgerService ledger,
                                      @Value("${app.deployment-ledger.workflow-token:}") String configuredWorkflowToken,
                                      @Value("${app.deployment-ledger.operator-token:}") String configuredOperatorToken,
                                      @Value("${app.deployment-ledger.allowed-repository:}")
                                      String allowedRepository) {
        this.ledger = ledger;
        this.configuredWorkflowToken = configuredWorkflowToken == null ? "" : configuredWorkflowToken;
        this.configuredOperatorToken = configuredOperatorToken == null ? "" : configuredOperatorToken;
        this.allowedRepository = allowedRepository == null ? "" : allowedRepository.trim();
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.OK)
    public DeploymentLedgerService.ClaimResult claim(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @Valid @RequestBody ClaimRequest request) {
        authorizeWorkflow(token, repositoryHeader);
        if (!allowedRepository.equalsIgnoreCase(request.repository().trim())) {
            throw DomainException.forbidden();
        }
        return ledger.claim(request.repository(), request.environment(), request.deploymentSha(), request.pullRequestNumber());
    }

    @GetMapping("/claims")
    public DeploymentLedgerService.ClaimResult find(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @RequestParam String repository,
            @RequestParam String environment,
            @RequestParam String deploymentSha) {
        authorizeWorkflow(token, repositoryHeader);
        if (!allowedRepository.equalsIgnoreCase(repository.trim())) throw DomainException.forbidden();
        return ledger.find(repository, environment, deploymentSha);
    }

    @PostMapping("/claims/{claimId}/requesting")
    public DeploymentLedgerService.ClaimResult markRequesting(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId) {
        authorizeWorkflow(token, repositoryHeader);
        return ledger.markRequesting(claimId);
    }

    @PostMapping("/claims/{claimId}/requested")
    public DeploymentLedgerService.ClaimResult markRequested(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId) {
        authorizeWorkflow(token, repositoryHeader);
        return ledger.markRequested(claimId);
    }

    @PostMapping("/claims/{claimId}/succeeded")
    public DeploymentLedgerService.ClaimResult markSucceeded(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId) {
        authorizeWorkflow(token, repositoryHeader);
        return ledger.markSucceeded(claimId);
    }

    @PostMapping("/claims/{claimId}/unknown")
    public DeploymentLedgerService.ClaimResult markUnknown(
            @RequestHeader(name = WORKFLOW_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId) {
        authorizeWorkflow(token, repositoryHeader);
        return ledger.markUnknown(claimId);
    }

    @PostMapping("/claims/{claimId}/safe-failure")
    public DeploymentLedgerService.ClaimResult markSafeFailure(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId) {
        authorizeOperator(token, repositoryHeader);
        return ledger.markSafeFailure(claimId);
    }

    @PostMapping("/claims/{claimId}/reconcile")
    public DeploymentLedgerService.ClaimResult reconcile(
            @RequestHeader(name = OPERATOR_TOKEN_HEADER, required = false) String token,
            @RequestHeader(name = REPOSITORY_HEADER, required = false) String repositoryHeader,
            @PathVariable UUID claimId,
            @Valid @RequestBody ReconcileRequest request) {
        authorizeOperator(token, repositoryHeader);
        return ledger.reconcile(claimId, request.outcome());
    }

    private void authorizeWorkflow(String suppliedToken, String suppliedRepository) {
        rejectSharedCredentials();
        authorize(suppliedToken, configuredWorkflowToken, suppliedRepository);
    }

    private void authorizeOperator(String suppliedToken, String suppliedRepository) {
        rejectSharedCredentials();
        authorize(suppliedToken, configuredOperatorToken, suppliedRepository);
    }

    private void rejectSharedCredentials() {
        if (!configuredWorkflowToken.isBlank() && !configuredOperatorToken.isBlank()
                && MessageDigest.isEqual(configuredWorkflowToken.getBytes(StandardCharsets.UTF_8),
                configuredOperatorToken.getBytes(StandardCharsets.UTF_8))) {
            throw new DomainException("DEPLOYMENT_LEDGER_DISABLED", HttpStatus.SERVICE_UNAVAILABLE,
                    "Workflow and operator ledger credentials must be distinct.", true);
        }
    }

    private void authorize(String suppliedToken, String configuredToken, String suppliedRepository) {
        if (allowedRepository.isBlank()) {
            throw new DomainException("DEPLOYMENT_LEDGER_DISABLED", HttpStatus.SERVICE_UNAVAILABLE,
                    "The deployment ledger repository allowlist is not configured.", true);
        }
        if (configuredToken.isBlank()) {
            throw new DomainException("DEPLOYMENT_LEDGER_DISABLED", HttpStatus.SERVICE_UNAVAILABLE,
                    "The deployment ledger endpoint is not configured for this operation.", true);
        }
        if (suppliedToken == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new DomainException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED,
                    "Deployment ledger authentication is required.");
        }
        if (suppliedRepository == null || !allowedRepository.equalsIgnoreCase(suppliedRepository.trim())) {
            throw DomainException.forbidden();
        }
    }

    public record ClaimRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$") String repository,
            @NotBlank @Pattern(regexp = "^(test-api|test-web)$") String environment,
            @NotBlank @Pattern(regexp = "(?i)^[0-9a-f]{40}$") String deploymentSha,
            @Positive int pullRequestNumber) { }

    public record ReconcileRequest(DeploymentLedgerService.ReconciliationOutcome outcome) { }
}
