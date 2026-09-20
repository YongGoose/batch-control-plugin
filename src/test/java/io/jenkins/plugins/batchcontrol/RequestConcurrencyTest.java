package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.springframework.security.core.Authentication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 7, D-20: request state transitions are atomic (compare-and-set).
 * Matrix rows T-RT-14 (double approve) and T-RT-15 (approve vs cancel race),
 * reproduced with two threads and a CyclicBarrier (matrix note 10); assertions are
 * on resulting state and build count only.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RequestConcurrencyTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
    }

    /** T-RT-14: two simultaneous approvals -> exactly one succeeds, exactly one build. */
    @Test
    public void t_rt_14_concurrentDoubleApproveYieldsExactlyOneBuild() throws Exception {
        String requestId = createPendingAs("u1");
        Authentication approverAuth = User.getById("a1", true).impersonate2();

        AtomicInteger successes = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> approveOnce = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try (ACLContext ignored = ACL.as2(approverAuth)) {
                    RunRequestService.get().approve(requestId, "concurrent approve");
                    successes.incrementAndGet();
                } catch (RuntimeException lostTheRace) {
                    // exactly one of the two approvals must land here
                }
                return null;
            };
            Future<?> first = pool.submit(approveOnce);
            Future<?> second = pool.submit(approveOnce);
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("exactly one of two concurrent approvals may succeed (compare-and-set)",
                1, successes.get());

        j.waitUntilNoActivity();
        assertEquals("the build must run exactly once", 1, job.getBuilds().size());
        assertEquals(2, job.getNextBuildNumber());

        RunRequest reloaded = RunRequestService.get().load(requestId);
        assertTrue("the request must be decided exactly once",
                reloaded.getStatus() == RequestStatus.APPROVED
                        || reloaded.getStatus() == RequestStatus.EXECUTED);
    }

    /** T-RT-15: approve vs cancel race -> exactly one wins, no mixed state. */
    @Test
    public void t_rt_15_approveVersusCancelExactlyOneWins() throws Exception {
        String requestId = createPendingAs("u1");
        Authentication approverAuth = User.getById("a1", true).impersonate2();
        Authentication requesterAuth = User.getById("u1", true).impersonate2();

        AtomicInteger approveWins = new AtomicInteger();
        AtomicInteger cancelWins = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> approving = pool.submit((Callable<Void>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try (ACLContext ignored = ACL.as2(approverAuth)) {
                    RunRequestService.get().approve(requestId, "race approve");
                    approveWins.incrementAndGet();
                } catch (RuntimeException lostTheRace) {
                    // acceptable: cancel won
                }
                return null;
            });
            Future<?> cancelling = pool.submit((Callable<Void>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try (ACLContext ignored = ACL.as2(requesterAuth)) {
                    RunRequestService.get().cancel(requestId);
                    cancelWins.incrementAndGet();
                } catch (RuntimeException lostTheRace) {
                    // acceptable: approve won
                }
                return null;
            });
            approving.get(30, TimeUnit.SECONDS);
            cancelling.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("exactly one of approve/cancel may win (first CAS wins)",
                1, approveWins.get() + cancelWins.get());

        j.waitUntilNoActivity();
        RunRequest reloaded = RunRequestService.get().load(requestId);
        if (cancelWins.get() == 1) {
            assertEquals(RequestStatus.CANCELLED, reloaded.getStatus());
            assertTrue("a cancelled request must never have started a build", job.getBuilds().isEmpty());
            assertNull("a CANCELLED request must not carry an executedRunId", reloaded.getExecutedRunId());
        } else {
            assertTrue("the approved request must be decided",
                    reloaded.getStatus() == RequestStatus.APPROVED
                            || reloaded.getStatus() == RequestStatus.EXECUTED);
            assertEquals("the approval must run the build exactly once", 1, job.getBuilds().size());
        }
        // mixed-state invariant, independent of who won
        assertFalse("no mixed state: CANCELLED with a build or executedRunId is forbidden",
                reloaded.getStatus() == RequestStatus.CANCELLED
                        && (!job.getBuilds().isEmpty() || reloaded.getExecutedRunId() != null));
    }

    // ---------------------------------------------------------------- helpers

    private String createPendingAs(String userId) {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return RunRequestService.get()
                    .create(job, new LinkedHashMap<>(), "race-condition run", "a1")
                    .getId();
        }
    }
}
