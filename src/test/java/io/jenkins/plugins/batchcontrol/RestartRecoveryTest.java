package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsSessionRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 4 (restart durability of approved-but-unsubmitted requests) and item 7
 * (recovery-time expiry judgment). Matrix rows T-04-02, T-07-04 and T-RT-17.
 *
 * T-RT-17 approximation (matrix note 11): the exact "scheduleBuild2 done, onStarted not
 * reached" crash timing is reproduced by submitting under quiet-down (the item is queued
 * but no build starts) and then restarting the session, so both a restored queue item and
 * the startup recovery could try to run the request; requestId-based idempotency must
 * leave exactly one build.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RestartRecoveryTest {

    private static final Instant T0 = Instant.parse("2026-09-20T09:00:00Z");
    private static final String JOB = "batch-x";

    @Rule
    public JenkinsSessionRule session = new JenkinsSessionRule();

    private String requestId;

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-04-02: an APPROVED request with no queue item is submitted exactly once after restart. */
    @Test
    public void t_04_02_approvedUnsubmittedRunsExactlyOnceAfterRestart() throws Throwable {
        session.then(r -> {
            FreeStyleProject job = prepare(r);
            RunRequest request = createAsU1(r, job);

            r.jenkins.doQuietDown();          // approval is recorded but no build can start
            approveAsA1(request.getId());
            r.jenkins.getQueue().clear();     // drop the queue item: approved, never submitted

            requestId = request.getId();
            assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(requestId).getStatus());
            assertTrue("no build may exist before the restart", job.getBuilds().isEmpty());
        });
        session.then(r -> {
            r.waitUntilNoActivity();
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertNotNull(job);
            assertEquals("startup recovery must submit the approved request exactly once",
                    1, job.getBuilds().size());
            assertEquals(2, job.getNextBuildNumber());

            FreeStyleBuild build = job.getBuildByNumber(1);
            ApprovedCause cause = build.getCause(ApprovedCause.class);
            assertNotNull("the recovered run must carry the ApprovedCause", cause);
            assertEquals(requestId, cause.getRequestId());

            RunRequest reloaded = RunRequestService.get().load(requestId);
            assertEquals("the request must be marked executed after the recovered run",
                    RequestStatus.EXECUTED, reloaded.getStatus());
            assertNotNull(reloaded.getExecutedRunId());
        });
    }

    /** T-07-04: downtime longer than approvedRunTimeout does NOT expire the request; recovery-time judgment. */
    @Test
    public void t_07_04_recoveryJudgesExpiryFromRecoveryTimeNotApprovalTime() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setApprovedRunTimeoutMinutes(60);
            cfg.save();

            FreeStyleProject job = prepare(r);
            RunRequest request = createAsU1(r, job);
            r.jenkins.doQuietDown();
            approveAsA1(request.getId());
            r.jenkins.getQueue().clear();

            requestId = request.getId();
            assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(requestId).getStatus());
        });

        // matrix note 3: the controller stays down for 61 minutes (clock moves between sessions)
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));

        session.then(r -> {
            r.waitUntilNoActivity();
            RunRequest reloaded = RunRequestService.get().load(requestId);
            assertNotEquals("restart downtime is the documented exception: the request must not expire",
                    RequestStatus.EXPIRED, reloaded.getStatus());

            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertEquals("the recovered approval must be submitted normally",
                    1, job.getBuilds().size());
            assertEquals(RequestStatus.EXECUTED,
                    RunRequestService.get().load(requestId).getStatus());
        });
    }

    /** T-RT-17: queued-but-not-started at shutdown -> after restart exactly one build (no duplicate). */
    @Test
    public void t_rt_17_noDuplicateBuildWhenQueuedItemSurvivesRestart() throws Throwable {
        session.then(r -> {
            FreeStyleProject job = prepare(r);
            RunRequest request = createAsU1(r, job);

            r.jenkins.doQuietDown();
            approveAsA1(request.getId());
            // scheduleBuild2 has completed but onStarted was never reached: the item is
            // still in the queue when the session shuts down (crash-gap approximation)
            assertEquals(1, r.jenkins.getQueue().getItems().length);
            assertTrue(job.getBuilds().isEmpty());
            requestId = request.getId();
        });
        session.then(r -> {
            r.waitUntilNoActivity();
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertEquals("restored queue item + startup recovery must be deduplicated by requestId: "
                    + "exactly one build per request", 1, job.getBuilds().size());
            assertEquals(2, job.getNextBuildNumber());

            RunRequest reloaded = RunRequestService.get().load(requestId);
            assertEquals(RequestStatus.EXECUTED, reloaded.getStatus());
            assertNotNull(reloaded.getExecutedRunId());
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Installs a persistable security setup, run control and the protected job. */
    private FreeStyleProject prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject job = r.createFreeStyleProject(JOB);
        job.addProperty(new BatchControlJobProperty(true));
        return job;
    }

    private RunRequest createAsU1(JenkinsRule r, FreeStyleProject job) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get()
                    .create(job, new LinkedHashMap<>(), "restart durability run", "a1");
        }
    }

    private void approveAsA1(String id) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(id, "ok");
        }
    }
}
