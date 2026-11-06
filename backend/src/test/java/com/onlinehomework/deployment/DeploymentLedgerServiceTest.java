package com.onlinehomework.deployment;

import com.onlinehomework.deployment.application.DeploymentLedgerService;
import com.onlinehomework.deployment.domain.DeploymentAttemptStatus;
import com.onlinehomework.deployment.persistence.DeploymentDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class DeploymentLedgerServiceTest {
    private static final String REPOSITORY = "sxh9506-lang/online-homework";
    private static final String API = "test-api";

    @Autowired DeploymentLedgerService ledger;
    @Autowired DeploymentDeliveryRepository deliveries;

    @BeforeEach
    void clearLedger() {
        deliveries.deleteAll();
    }

    @Test
    void concurrentDuplicateEventsAtomicallyClaimOnceAndCallFakeHookOnceWithPinnedSha() throws Exception {
        String deploymentSha = "a".repeat(40);
        var hook = new FakeHook(false);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int pullRequestNumber = 42 + i;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return dispatch(deploymentSha, pullRequestNumber, hook);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int claimed = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(20, TimeUnit.SECONDS)) claimed++;
            }
            assertEquals(1, claimed);
        }
        assertEquals(1, hook.calls.get(), "the fake hook must be called at most once for the main deployment SHA");
        assertEquals(deploymentSha, hook.lastRef.get(), "the hook receives the pinned SHA, not branch 'main'");
        assertEquals(1, deliveries.count());
        assertEquals(DeploymentAttemptStatus.SUCCEEDED,
                ledger.find(REPOSITORY, API, deploymentSha).status());
    }

    @Test
    void duplicateClaimsForSameMainTipCallFakeAdapterOnlyOnce() {
        String deploymentSha = "b".repeat(40);
        var hook = new FakeHook(false);
        assertTrue(dispatch(deploymentSha, 42, hook));
        assertFalse(dispatch(deploymentSha, 43, hook),
                "another claim resolving to the same main tip must reuse the existing environment key");
        assertFalse(dispatch(deploymentSha, 42, hook),
                "a repeated claim for the same deployed main SHA must be skipped");
        assertEquals(1, hook.calls.get());
        assertEquals(deploymentSha, hook.lastRef.get());
        assertEquals(1, deliveries.count());
    }

    @Test
    void onlyConfirmedPreRequestFailureCanBeRetried() {
        String deploymentSha = "c".repeat(40);
        var initial = ledger.claim(REPOSITORY, API, deploymentSha, 42);
        assertTrue(initial.acquired());
        ledger.markSafeFailure(initial.claimId());

        var retry = ledger.claim(REPOSITORY, API, deploymentSha, 42);
        assertTrue(retry.acquired());
        assertEquals(2, retry.attemptCount());
        ledger.markRequesting(retry.claimId());
        ledger.markRequested(retry.claimId());
        ledger.markSucceeded(retry.claimId());
        assertEquals(DeploymentAttemptStatus.SUCCEEDED, ledger.find(REPOSITORY, API, deploymentSha).status());
    }

    @Test
    void timeoutBecomesUnknownAndBlocksBlindRetryUntilProviderReconciliation() {
        String deploymentSha = "d".repeat(40);
        var hook = new FakeHook(true);
        assertTrue(dispatch(deploymentSha, hook));
        assertEquals(1, hook.calls.get());
        assertEquals(DeploymentAttemptStatus.UNKNOWN, ledger.find(REPOSITORY, API, deploymentSha).status());
        assertFalse(dispatch(deploymentSha, hook), "unknown provider result must never trigger a blind resend");
        assertEquals(1, hook.calls.get());

        var existing = ledger.find(REPOSITORY, API, deploymentSha);
        ledger.reconcile(existing.claimId(), DeploymentLedgerService.ReconciliationOutcome.NOT_DEPLOYED);
        hook.timeout = false;
        assertTrue(dispatch(deploymentSha, hook), "retry is permitted only after provider confirms no deployment occurred");
        assertEquals(2, hook.calls.get());
        assertEquals(deploymentSha, hook.lastRef.get());
        assertEquals(DeploymentAttemptStatus.SUCCEEDED, ledger.find(REPOSITORY, API, deploymentSha).status());
    }

    @Test
    void unresolvedTimeoutBlocksSameShaButANewerMainTipGetsAnIndependentClaim() {
        String oldDeploymentSha = "e".repeat(40);
        String newDeploymentSha = "f".repeat(40);
        var hook = new FakeHook(true);
        assertTrue(dispatch(oldDeploymentSha, hook));
        assertEquals(DeploymentAttemptStatus.UNKNOWN, ledger.find(REPOSITORY, API, oldDeploymentSha).status());
        assertFalse(dispatch(oldDeploymentSha, 43, hook));
        assertEquals(1, hook.calls.get());

        hook.timeout = false;
        assertTrue(dispatch(newDeploymentSha, 43, hook), "a new main tip uses a distinct persistent key");
        assertEquals(2, hook.calls.get());
        assertEquals(newDeploymentSha, hook.lastRef.get());
        assertEquals(2, deliveries.count());
        assertEquals(DeploymentAttemptStatus.SUCCEEDED, ledger.find(REPOSITORY, API, newDeploymentSha).status());
    }

    @Test
    void apiAndWebUseIndependentPersistentClaimsForTheSameDeploymentSha() {
        String deploymentSha = "9".repeat(40);
        var api = ledger.claim(REPOSITORY, API, deploymentSha, 42);
        var web = ledger.claim(REPOSITORY, "test-web", deploymentSha, 42);

        assertTrue(api.acquired());
        assertTrue(web.acquired());
        assertEquals(2, deliveries.count());
    }

    private boolean dispatch(String deploymentSha, FakeHook hook) {
        return dispatch(deploymentSha, 42, hook);
    }

    private boolean dispatch(String deploymentSha, int pullRequestNumber, FakeHook hook) {
        var claim = ledger.claim(REPOSITORY, API, deploymentSha, pullRequestNumber);
        if (!claim.acquired()) return false;
        ledger.markRequesting(claim.claimId());
        try {
            hook.request(deploymentSha);
        } catch (FakeTimeoutException exception) {
            ledger.markUnknown(claim.claimId());
            return true;
        }
        ledger.markRequested(claim.claimId());
        ledger.markSucceeded(claim.claimId());
        return true;
    }

    private static final class FakeHook {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastRef = new AtomicReference<>();
        private volatile boolean timeout;

        private FakeHook(boolean timeout) { this.timeout = timeout; }

        private void request(String deploymentSha) {
            lastRef.set(deploymentSha);
            calls.incrementAndGet();
            if (timeout) throw new FakeTimeoutException();
        }
    }

    private static final class FakeTimeoutException extends RuntimeException { }
}
