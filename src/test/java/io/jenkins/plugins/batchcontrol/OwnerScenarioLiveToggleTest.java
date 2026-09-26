package io.jenkins.plugins.batchcontrol;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner scenarios S-3 and S-4 — turning a control on while work is already running, and the
 * control group that runs untouched until the control is turned on.
 * Matrix rows T-OS-05 (enabling {@code approvalRequired} mid-build), T-OS-06 (enabling
 * {@code blockTimer} mid-build), T-OS-07 (repeated manual runs pass until the control is
 * switched on) and T-OS-08 (repeated timer runs pass until {@code blockTimer} is switched on).
 *
 * The decisive assertion of S-3 is that switching a control on never disturbs work in flight:
 * turning on governance must not kill a running batch. The reverse direction (S-4) pins that
 * nothing is blocked before the switch and everything is blocked right after it.
 *
 * Note on the S-4 model: an approval is bound to one request and consumed by a single queue
 * submission (SPEC 6, D-23), so "approve once, keep running" is not the current contract;
 * marker reuse is pinned by T-RT-02 and the model difference is recorded in matrix note 32.
 *
 * Accounts: administrator {@code admin} (flips the job settings), requester {@code u1}
 * (runs the job manually while it is still uncontrolled).
 *
 * SPEC basis: item 6 (queue-entry blocking, timer policy) and item 1 (a control that is off
 * changes nothing).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class OwnerScenarioLiveToggleTest {

    private static final String ADMIN = "admin";
    private static final String REQUESTER = "u1";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-OS-05 (owner scenario S-3): while u1's manual build is running, the administrator
     * switches {@code approvalRequired} on. The running build must finish normally, and only
     * the runs that come after the switch are blocked.
     */
    @Test
    public void t_os_05_enablingApprovalRequiredDoesNotDisturbTheRunningBuild() throws Exception {
        // The scenario starts from a job the control is NOT yet on for, which is what the
        // administrator switches on mid-build. Run control is globally on, so D-31 attaches the
        // property at creation and it has to be stripped to reach that starting state.
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("live-x"));
        String gate = "t-os-05";
        job.getBuildersList().add(new GatedBuilder(gate));

        Future<FreeStyleBuild> running;
        try (ACLContext ignored = as(REQUESTER)) {
            running = job.scheduleBuild2(0, new Cause.UserIdCause());
        }
        assertNotNull(running, "the manual run must be allowed while the control is still off");
        GatedBuilder.awaitStarted(gate);
        FreeStyleBuild inFlight = job.getBuildByNumber(1);
        assertNotNull(inFlight);
        assertTrue(inFlight.isBuilding(), "test precondition: build #1 must be in flight");

        // the administrator turns run control on for this job, mid-build
        setBatchControl(job, new BatchControlJobProperty(true));
        assertTrue(inFlight.isBuilding(), "turning the control on must not abort the running build");

        GatedBuilder.release(gate);
        j.assertBuildStatusSuccess(running); // the in-flight build completes normally
        RunRecord record = record("live-x#1");
        assertNotNull(record, "the completed run must still be recorded");
        assertEquals("SUCCESS", record.getResult());

        // from now on an unapproved manual run is blocked
        Page blocked = postBuild(REQUESTER, job);
        assertTrue(blocked.getWebResponse().getStatusCode() >= 400, "the next unapproved manual run must be refused, got HTTP "
                        + blocked.getWebResponse().getStatusCode());
        assertBlocked(job, 2);
        assertEquals(1, job.getBuilds().size(), "no build may have been added after the control was switched on");
    }

    /**
     * T-OS-06 (owner scenario S-3, timer variant): while a timer-caused build is running the
     * administrator switches {@code blockTimer} on. The running build must finish normally and
     * only the next timer firing is refused.
     */
    @Test
    public void t_os_06_enablingBlockTimerDoesNotDisturbTheRunningBuild() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("live-timer-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        // must be the job's only property (D-31 already attached one at creation), because the
        // test tightens blockTimer on this very instance further down
        setBatchControl(job, property);
        String gate = "t-os-06";
        job.getBuildersList().add(new GatedBuilder(gate));

        Future<FreeStyleBuild> running = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(running, "a timer cause passes by default, so the scheduled run must start");
        GatedBuilder.awaitStarted(gate);
        FreeStyleBuild inFlight = job.getBuildByNumber(1);
        assertNotNull(inFlight);
        assertTrue(inFlight.isBuilding(), "test precondition: build #1 must be in flight");

        // the administrator tightens the timer policy while the batch is running
        property.setBlockTimer(true);
        job.save();
        assertTrue(inFlight.isBuilding(), "tightening the timer policy must not abort the running build");

        GatedBuilder.release(gate);
        j.assertBuildStatusSuccess(running);

        // the next cron firing is refused
        Future<FreeStyleBuild> refused = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull(refused, "after blockTimer=true the next timer firing must be refused");
        assertBlocked(job, 2);
        assertEquals(1, job.getBuilds().size());
    }

    /**
     * T-OS-07 (owner scenario S-4): an uncontrolled job runs manually over and over without
     * trouble, every run being recorded, and only turning {@code approvalRequired} on flips it
     * to blocked.
     */
    @Test
    public void t_os_07_repeatedManualRunsPassUntilTheControlIsSwitchedOn() throws Exception {
        // S-4 starts from an uncontrolled job: D-31's creation-time property is stripped so that
        // the three runs below really are "before the switch"
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("repeat-x"));

        for (int number = 1; number <= 3; number++) {
            Page response = postBuild(REQUESTER, job);
            assertTrue(response.getWebResponse().getStatusCode() < 400, "run #" + number + " must not be blocked while approvalRequired is off, got HTTP "
                            + response.getWebResponse().getStatusCode());
            j.waitUntilNoActivity();

            FreeStyleBuild build = job.getBuildByNumber(number);
            assertNotNull(build, "run #" + number + " must have executed");
            j.assertBuildStatusSuccess(build);
            RunRecord record = record("repeat-x#" + number);
            assertNotNull(record, "run #" + number + " must be recorded");
            assertEquals("SUCCESS", record.getResult());
        }
        assertEquals(3, job.getBuilds().size(), "all three consecutive runs must exist");

        // the administrator switches the control on
        setBatchControl(job, new BatchControlJobProperty(true));

        Page blocked = postBuild(REQUESTER, job);
        assertTrue(blocked.getWebResponse().getStatusCode() >= 400, "the first run after the switch must be blocked, got HTTP "
                        + blocked.getWebResponse().getStatusCode());
        assertBlocked(job, 4);
        assertEquals(3, job.getBuilds().size(), "no fourth build may exist");
    }

    /**
     * T-OS-08 (owner scenario S-4, timer variant): a timer job fires repeatedly and is recorded
     * every time while {@code blockTimer} is off, and is refused from the moment it is on.
     */
    @Test
    public void t_os_08_repeatedTimerRunsPassUntilBlockTimerIsSwitchedOn() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("repeat-timer-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        // the timer block is switched on this instance further down, so it must be the one the
        // plugin reads and not sit behind D-31's creation-time property
        setBatchControl(job, property);

        for (int number = 1; number <= 3; number++) {
            Future<FreeStyleBuild> firing = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
            assertNotNull(firing, "timer firing #" + number + " must pass while blockTimer is off");
            j.assertBuildStatusSuccess(firing);
            j.waitUntilNoActivity();
            RunRecord record = record("repeat-timer-x#" + number);
            assertNotNull(record, "timer run #" + number + " must be recorded");
            assertEquals("SUCCESS", record.getResult());
        }
        assertEquals(3, job.getBuilds().size());

        // the administrator switches the timer block on
        property.setBlockTimer(true);
        job.save();

        Future<FreeStyleBuild> refused = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull(refused, "the first timer firing after the switch must be refused");
        assertBlocked(job, 4);
        assertEquals(3, job.getBuilds().size());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private Page postBuild(String userId, FreeStyleProject target) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(target.getUrl() + "build"), HttpMethod.POST));
    }

    private RunRecord record(String runId) {
        return FileStore.get().listRunRecords(YearMonth.now()).stream()
                .filter(rec -> runId.equals(rec.getRunId()))
                .findFirst().orElse(null);
    }

    /** Matrix common blocking baseline. */
    private void assertBlocked(Job<?, ?> target, int nextBuildNumberBefore) throws Exception {
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(nextBuildNumberBefore, target.getNextBuildNumber(), "nextBuildNumber must not move");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must still be empty after settling");
    }

    /**
     * A build step that blocks until the test releases it, so a job setting can be changed
     * while a build is provably in flight (no sleeps, no timing guesses). Only the gate id is
     * persisted, so the step survives a job save/reload; the latches live in static maps keyed
     * by that id.
     */
    public static class GatedBuilder extends Builder {

        private static final Map<String, CountDownLatch> STARTED = new ConcurrentHashMap<>();
        private static final Map<String, CountDownLatch> RELEASED = new ConcurrentHashMap<>();

        private final String gateId;

        public GatedBuilder(String gateId) {
            this.gateId = gateId;
            STARTED.put(gateId, new CountDownLatch(1));
            RELEASED.put(gateId, new CountDownLatch(1));
        }

        public String getGateId() {
            return gateId;
        }

        static void awaitStarted(String gateId) throws InterruptedException {
            assertTrue(STARTED.get(gateId).await(60, TimeUnit.SECONDS), "the gated build step must start within 60s");
        }

        static void release(String gateId) {
            RELEASED.get(gateId).countDown();
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            CountDownLatch started = STARTED.get(gateId);
            CountDownLatch released = RELEASED.get(gateId);
            if (started == null || released == null) {
                listener.getLogger().println("gate " + gateId + " is unknown, passing through");
                return true;
            }
            started.countDown();
            listener.getLogger().println("waiting on gate " + gateId);
            return released.await(120, TimeUnit.SECONDS);
        }

        @TestExtension
        public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

            @Override
            public boolean isApplicable(Class<? extends AbstractProject> jobType) {
                return true;
            }

            @Override
            public String getDisplayName() {
                return "Gated test build step";
            }
        }
    }
}
