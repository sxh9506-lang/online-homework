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
        "app.deployment-ledger.workflow-token=shared-test-credential",
        "app.deployment-ledger.operator-token=shared-test-credential"
})
class DeploymentLedgerSharedTokenApiTest {
    private static final String REPOSITORY = "sxh9506-lang/online-homework";

    @Autowired MockMvc mvc;
    @Autowired DeploymentDeliveryRepository deliveries;

    @Test
    void sharedWorkflowAndOperatorCredentialFailsClosedBeforeLedgerAccess() throws Exception {
        long before = deliveries.count();
        String body = "{\"repository\":\"" + REPOSITORY + "\",\"environment\":\"test-api\","
                + "\"deploymentSha\":\"" + "5".repeat(40) + "\",\"pullRequestNumber\":42}";

        mvc.perform(post("/internal/deployment-ledger/claims")
                        .header("X-Deployment-Ledger-Token", "shared-test-credential")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        mvc.perform(post("/internal/deployment-ledger/claims/{id}/reconcile", UUID.randomUUID())
                        .header("X-Deployment-Ledger-Operator-Token", "shared-test-credential")
                        .header("X-Deployment-Ledger-Repository", REPOSITORY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"NOT_DEPLOYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPLOYMENT_LEDGER_DISABLED"));

        assertEquals(before, deliveries.count());
    }
}
