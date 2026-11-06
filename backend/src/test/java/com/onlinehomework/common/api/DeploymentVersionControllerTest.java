package com.onlinehomework.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploymentVersionControllerTest {
    @Test
    void reportsFullRenderCommitSha() {
        DeploymentVersionController controller = new DeploymentVersionController("a".repeat(40));

        assertEquals("a".repeat(40), controller.version().commit());
    }

    @Test
    void missingOrMalformedCommitFailsClosedAsUnknown() {
        assertEquals("unknown", new DeploymentVersionController(null).version().commit());
        assertEquals("unknown", new DeploymentVersionController("").version().commit());
        assertEquals("unknown", new DeploymentVersionController("not-a-full-sha").version().commit());
        assertEquals("unknown", new DeploymentVersionController("A".repeat(40)).version().commit());
    }
}
