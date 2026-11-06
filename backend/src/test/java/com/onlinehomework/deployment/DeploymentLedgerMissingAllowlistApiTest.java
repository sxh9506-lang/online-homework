package com.onlinehomework.deployment;

import com.onlinehomework.deployment.persistence.DeploymentDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.deployment-ledger.workflow-token=allowlist-test-workflow-token",
        "app.deployment-ledger.operator-token=allowlist-test-operator-token",
        "app.deployment-ledger.allowed-repository="
})
class DeploymentLedgerMissingAllowlistApiTest {
    private static final String REPOSITORY = "sxh9506-lang/online-homework";

    @Autowired MockMvc mvc;
    @Autowired DeploymentDeliveryRepository deliveries;

    @Test
    void missingAllowlistDisablesWorkflowAndOperatorRequestsBeforeLedgerAccess() throws Exception {
        long before = deliveries.count();
        String claimBody = "{\"repository\":\"" + REPOSITORY + "\",\"environment\":\"test-api\","
                + "\"deploymentSha\":\"" + "6".repeat(40) + "\",\"pullRequestNumber\":42}";

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", "allowlist-test-workflow-token")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", UUID.randomUUID())
                        .header("X-Deployment-Ledger-Operator-Token", "allowlist-test-operator-token")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_DEPLOYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        assertEquals(before, deliveries.count());
    }
}
