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
        "app.deployment-ledger.workflow-token=malformed-allowlist-workflow-token",
        "app.deployment-ledger.operator-token=malformed-allowlist-operator-token",
        "app.deployment-ledger.allowed-repository=sxh9506-lang/online homework"
})
class DeploymentLedgerMalformedAllowlistApiTest {
    private static final String REPOSITORY = "sxh9506-lang/online-homework";

    @Autowired MockMvc mvc;
    @Autowired DeploymentDeliveryRepository deliveries;

    @Test
    void malformedAllowlistDisablesWorkflowAndOperatorRequestsBeforeLedgerAccess() throws Exception {
        long before = deliveries.count();
        String claimBody = "{\"repository\":\"" + REPOSITORY + "\",\"environment\":\"test-api\","
                + "\"deploymentSha\":\"" + "7".repeat(40) + "\",\"pullRequestNumber\":42}";

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", "malformed-allowlist-workflow-token")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", UUID.randomUUID())
                        .header("X-Deployment-Ledger-Operator-Token", "malformed-allowlist-operator-token")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_DEPLOYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        assertEquals(before, deliveries.count());
    }
}
