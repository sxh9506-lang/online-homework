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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.deployment-ledger.workflow-token=",
        "app.deployment-ledger.operator-token="
})
class DeploymentLedgerDisabledApiTest {
    @Autowired MockMvc mvc;
    @Autowired DeploymentDeliveryRepository deliveries;

    @Test
    void endpointRejectsAllRequestsWhenWorkflowOrOperatorTokenIsNotConfigured() throws Exception {
        long before = deliveries.count();
        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", "any-supplied-value")
                        .header("X-Deployment-Ledger-Repository", "sxh9506-lang/online-homework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repository\":\"sxh9506-lang/online-homework\",\"environment\":\"test-api\","
                                + "\"deploymentSha\":\"2" + "2".repeat(39) + "\",\"pullRequestNumber\":42}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));
        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", java.util.UUID.randomUUID())
                        .header("X-Deployment-Ledger-Operator-Token", "any-supplied-value")
                        .header("X-Deployment-Ledger-Repository", "sxh9506-lang/online-homework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_DEPLOYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));
        assertEquals(before, deliveries.count());
    }
}
