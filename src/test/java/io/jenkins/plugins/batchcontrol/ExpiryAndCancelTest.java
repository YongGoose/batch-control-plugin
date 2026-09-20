package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.springframework.security.core.Authentication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 7 (request expiry and cancellation) plus the D-20 check-at-submit criterion.
 * Matrix rows T-07-01, T-07-02, T-07-03, T-07-05, T-07-06, T-07-07 and T-RT-16.
 * (T-07-04 needs a restart and lives in RestartRecoveryTest.)
 *
 * Time never passes for real: BatchClock is fixed/moved (matrix note 1) and
 * ExpiryPeriodicWork.doRun() is invoked directly (matrix note 2).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class ExpiryAndCancelTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
    }

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-07-01: a PENDING request past pendingTimeoutHours becomes EXPIRED via the periodic work. */
    @Test
    public void t_07_01_pendingRequestExpiresAfterTimeout() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

        RunRequest request = createAs("u1");
        assertEquals(RequestStatus.PENDING, request.getStatus());

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        runExpiryWork();

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("a PENDING request past the timeout must become EXPIRED",
                RequestStatus.EXPIRED, reloaded.getStatus());
    }

    /** T-07-02: a user who is neither the requester nor a Manage holder gets 403 on cancel. */
    @Test
    public void t_07_02_cancelByOtherUserIs403() throws Exception {
        RunRequest request = createAs("u1");

        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login("u2");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/requests/" + request.getId() + "/cancel"),
                HttpMethod.POST));
        assertEquals("cancel is restricted to the requester or a Manage holder",
                403, page.getWebResponse().getStatusCode());

        assertEquals("the request must stay PENDING",
                RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus());
    }

    /** T-07-03: an APPROVED request that never reached the queue expires after approvedRunTimeoutMinutes. */
    @Test
    public void t_07_03_approvedButUnsubmittedRequestExpires() throws Exception {
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));

        RunRequest request = createAs("u1");
        // hold the executor so the approval is recorded but the build never starts,
        // then drop the queue item to model "approved but never submitted"
        j.jenkins.doQuietDown();
        approveAs("a1", request.getId());
        j.jenkins.getQueue().clear();
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(request.getId()).getStatus());

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        runExpiryWork();

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("an APPROVED request not submitted within the timeout must become EXPIRED",
                RequestStatus.EXPIRED, reloaded.getStatus());

        j.jenkins.doCancelQuietDown();
        j.waitUntilNoActivity();
        assertTrue("an expired approval must never run", job.getBuilds().isEmpty());
    }

    /** T-07-05: the requester cancels their own PENDING request. */
    @Test
    public void t_07_05_requesterCancelsOwnPendingRequest() throws Exception {
        RunRequest request = createAs("u1");

        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().cancel(request.getId());
        }

        assertEquals("the request must be CANCELLED and stay on record",
                RequestStatus.CANCELLED, RunRequestService.get().load(request.getId()).getStatus());
    }

    /** T-07-06: a Manage holder who is not the requester can cancel a PENDING request. */
    @Test
    public void t_07_06_manageHolderCancelsPendingRequest() throws Exception {
        RunRequest request = createAs("u1");

        try (ACLContext ignored = as("m1")) {
            RunRequestService.get().cancel(request.getId());
        }

        assertEquals(RequestStatus.CANCELLED,
                RunRequestService.get().load(request.getId()).getStatus());
    }

    /** T-07-07: cancel is refused once the request is APPROVED (PENDING only). */
    @Test
    public void t_07_07_cancelRefusedAfterApproval() throws Exception {
        RunRequest request = createAs("u1");
        j.jenkins.doQuietDown(); // keep the approved submission from starting so the state stays APPROVED
        approveAs("a1", request.getId());
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(request.getId()).getStatus());

        assertRefused("cancel must only be possible while PENDING", () -> {
            try (ACLContext ignored = as("u1")) {
                RunRequestService.get().cancel(request.getId());
            }
        });
        assertEquals("the refused cancel must not change the state",
                RequestStatus.APPROVED, RunRequestService.get().load(request.getId()).getStatus());

        j.jenkins.doCancelQuietDown();
        j.waitUntilNoActivity();
    }

    /**
     * T-RT-16: check-at-submit. A request whose timeout has already passed is raced between
     * the approve/submit path and ExpiryPeriodicWork; the expired request must never be
     * submitted and the final state must converge to exactly EXPIRED (matrix note 10;
     * the public submit path is approve(), so the race is approve vs expiry work).
     */
    @Test
    public void t_rt_16_expiredRequestIsNeverSubmittedEvenUnderRace() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        RunRequest request = createAs("u1");
        String requestId = request.getId();

        // the clock is already past the timeout when both contenders start
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));

        Authentication approverAuth = User.getById("a1", true).impersonate2();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> approveRace = pool.submit((Callable<Void>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try (ACLContext ignored = ACL.as2(approverAuth)) {
                    RunRequestService.get().approve(requestId, "too late");
                } catch (RuntimeException expectedWhenExpiryWins) {
                    // refusing the late approval is a valid outcome
                }
                return null;
            });
            Future<?> expiryRace = pool.submit((Callable<Void>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                runExpiryWork();
                return null;
            });
            approveRace.get(30, TimeUnit.SECONDS);
            expiryRace.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        RunRequest reloaded = RunRequestService.get().load(requestId);
        assertEquals("the expired request must converge to EXPIRED, never EXECUTED",
                RequestStatus.EXPIRED, reloaded.getStatus());
        assertNull("no run may be linked to the expired request", reloaded.getExecutedRunId());

        j.waitUntilNoActivity();
        assertTrue("the expired approval must never have been submitted", job.getBuilds().isEmpty());
        assertEquals(0, j.jenkins.getQueue().getItems().length);
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createAs(String userId) {
        Map<String, String> parameters = new LinkedHashMap<>();
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get().create(job, parameters, "scheduled batch run", "a1");
        }
    }

    private void approveAs(String userId, String requestId) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, "ok");
        }
    }

    private static void runExpiryWork() throws Exception {
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static void assertRefused(String message, ThrowingRunnable action) {
        boolean refused = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(message, refused);
    }
}
