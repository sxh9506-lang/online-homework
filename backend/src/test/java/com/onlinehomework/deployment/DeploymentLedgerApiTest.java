package com.onlinehomework.deployment;

import com.jayway.jsonpath.JsonPath;
import com.onlinehomework.deployment.persistence.DeploymentDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeploymentLedgerApiTest {
    private static final String WORKFLOW_TOKEN = UUID.randomUUID().toString();
    private static final String OPERATOR_TOKEN = UUID.randomUUID().toString();
    private static final String REPOSITORY = "sxh9506-lang/online-homework";

    @Autowired MockMvc mvc;
    @Autowired DeploymentDeliveryRepository deliveries;

    @DynamicPropertySource
    static void ledgerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.deployment-ledger.workflow-token", () -> WORKFLOW_TOKEN);
        registry.add("app.deployment-ledger.operator-token", () -> OPERATOR_TOKEN);
        registry.add("RENDER_GIT_COMMIT", () -> "a".repeat(40));
    }

    @BeforeEach
    void clearLedger() { deliveries.deleteAll(); }

    @Test
    void deploymentVersionIsPublicAndReportsTheValidatedCommitSha() throws Exception {
        mvc.perform(get("/internal/deployment-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value("a".repeat(40)));
    }

    @Test
    void workflowTokenCanClaimAndTransitionWithoutUserJwt() throws Exception {
        String claimId = claim("1".repeat(40), "test-api");
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/requesting", claimId)
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTING"));
    }

    @Test
    void missingOrWrongWorkflowTokenAndRepositoryAreRejectedBeforeLedgerDataIsReadOrWritten() throws Exception {
        String body = claimBody("1".repeat(40), "test-api");

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", "invalid-token")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", "attacker/other")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String otherRepository = body.replace(REPOSITORY, "attacker/other");
        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(otherRepository))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        org.junit.jupiter.api.Assertions.assertEquals(0, deliveries.count());
    }

    @Test
    void workflowTokenCannotRunManualSafeFailureOrReconciliation() throws Exception {
        String safeFailureClaim = claim("3".repeat(40), "test-api");
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/safe-failure", safeFailureClaim)
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/safe-failure", safeFailureClaim)
                        .header("X-Deployment-Ledger-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SAFE_FAILURE"));

        String unknownClaim = claim("4".repeat(40), "test-web");
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/requesting", unknownClaim)
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/unknown", unknownClaim)
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        String reconcileBody = "{\"outcome\":\"NOT_DEPLOYED\"}";
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", unknownClaim)
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(reconcileBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", unknownClaim)
                        .header("X-Deployment-Ledger-Operator-Token", OPERATOR_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(reconcileBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SAFE_FAILURE")));
    }

    private String claim(String sha, String environment) throws Exception {
        MvcResult result = mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", WORKFLOW_TOKEN)
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(sha, environment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acquired", is(true)))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.claimId");
    }

    private static String claimBody(String sha, String environment) {
        return "{\"repository\":\"" + REPOSITORY + "\",\"environment\":\"" + environment
                + "\",\"deploymentSha\":\"" + sha + "\",\"pullRequestNumber\":42}";
    }
}
