package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.scm.NullSCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import jenkins.branch.BranchSource;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 10 (run dashboard / run records). Matrix rows T-10-01, T-10-02, T-10-03,
 * T-10-04, T-10-05 and T-10-07 (T-10-06 is an e2e P2 row, Phase 5).
 *
 * Records are asserted through the FileStore read API (listRunRecords), the dashboard
 * gate through HTTP status codes. Written from docs/SPEC.md, docs/ARCHITECTURE.md
 * sections 2/5 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RunRecordListenerTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ).everywhere().to("nohist"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** T-10-01: Freestyle and Pipeline completions are both recorded with an accurate causeType. */
    @Test
    public void t_10_01_freestyleAndPipelineAreBothRecorded() throws Exception {
        // This row measures the recorder, not the queue gate: both jobs stay out of run control
        // (D-31 would otherwise make them approval-required at creation and block the USER cause).
        FreeStyleProject freestyle = uncontrolled(j.createFreeStyleProject("fs-x"));
        j.assertBuildStatusSuccess(freestyle.scheduleBuild2(0, userCause("u1")));

        WorkflowJob pipeline = uncontrolled(j.createProject(WorkflowJob.class, "pipe-x"));
        pipeline.setDefinition(new CpsFlowDefinition("echo 'record me'", true));
        j.assertBuildStatusSuccess(pipeline.scheduleBuild2(0, new CauseAction(userCause("u1"))));
        j.waitUntilNoActivity();

        RunRecord freestyleRecord = record("fs-x#1");
        assertNotNull(freestyleRecord, "a Freestyle completion must append a RunRecord");
        assertEquals("fs-x", freestyleRecord.getJobFullName());
        assertEquals(CauseType.USER, freestyleRecord.getCauseType());
        assertEquals("SUCCESS", freestyleRecord.getResult());
        assertEquals("u1", freestyleRecord.getUser(), "the triggering user must be recorded");
        assertNotNull(freestyleRecord.getStartedAt(), "the record must carry the start time");

        RunRecord pipelineRecord = record("pipe-x#1");
        assertNotNull(pipelineRecord, "a Pipeline completion must append a RunRecord");
        assertEquals("pipe-x", pipelineRecord.getJobFullName());
        assertEquals(CauseType.USER, pipelineRecord.getCauseType());
        assertEquals("SUCCESS", pipelineRecord.getResult());
    }

    /** T-10-02: an ABORTED build records who interrupted it (Jenkins InterruptedBuildAction). */
    @Test
    public void t_10_02_abortedBuildRecordsAbortingUser() throws Exception {
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "abort-x");
        pipeline.setDefinition(new CpsFlowDefinition("sleep 60", true));

        QueueTaskFuture<WorkflowRun> future = pipeline.scheduleBuild2(0);
        assertNotNull(future);
        WorkflowRun run = future.waitForStart();
        long deadline = System.currentTimeMillis() + 10_000;
        while (run.getExecutor() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNotNull(run.getExecutor(), "the running build must expose its executor");
        run.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("u1"));
        j.waitForCompletion(run);
        j.assertBuildStatus(Result.ABORTED, run);

        RunRecord record = record("abort-x#1");
        assertNotNull(record, "an aborted completion must still append a RunRecord");
        assertEquals("ABORTED", record.getResult());
        assertEquals("u1", record.getAbortedBy(), "the interrupting user recorded by Jenkins must be surfaced as abortedBy");
    }

    /** T-10-03: causes classify as USER / TIMER / UPSTREAM / SCM / OTHER / APPROVED_REQUEST. */
    @Test
    public void t_10_03_causeTypesAreClassified() throws Exception {
        // the first five causes must reach the recorder unblocked, so the job starts outside run
        // control (D-31 attaches approvalRequired=true at creation while run control is on)
        FreeStyleProject target = uncontrolled(j.createFreeStyleProject("cause-x"));

        // #1 USER
        j.assertBuildStatusSuccess(target.scheduleBuild2(0, userCause("u1")));
        // #2 TIMER (matrix note 4: cron firing reproduced by a TimerTriggerCause schedule)
        j.assertBuildStatusSuccess(target.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        // #3 UPSTREAM (real build-step invocation, waits for the downstream run)
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "up");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'cause-x', wait: true", true));
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();
        // #4 SCM
        j.assertBuildStatusSuccess(target.scheduleBuild2(0,
                new SCMTrigger.SCMTriggerCause("simulated polling detected changes")));
        // #5 OTHER (a cause outside every known classification)
        j.assertBuildStatusSuccess(target.scheduleBuild2(0, new SyntheticCause()));
        // #6 APPROVED_REQUEST (protect the job only now, so the earlier causes were unaffected)
        setBatchControl(target, new BatchControlJobProperty(true));
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(target, new LinkedHashMap<>(),
                    "classification probe", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(6, target.getBuilds().size(), "all six classification builds must have run");

        assertCauseType("cause-x#1", CauseType.USER);
        assertCauseType("cause-x#2", CauseType.TIMER);
        assertCauseType("cause-x#3", CauseType.UPSTREAM);
        assertCauseType("cause-x#4", CauseType.SCM);
        assertCauseType("cause-x#5", CauseType.OTHER);
        assertCauseType("cause-x#6", CauseType.APPROVED_REQUEST);
        assertEquals("u1", record("cause-x#1").getUser(), "the USER record must carry the triggering user");
    }

    /** T-10-04: an approved-request run links record and request in both directions. */
    @Test
    public void t_10_04_approvedRequestRunLinksRecordAndRequest() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("appr-x");
        setBatchControl(job, new BatchControlJobProperty(true));

        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(),
                    "month-end batch", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size());

        RunRecord record = record("appr-x#1");
        assertNotNull(record);
        assertEquals(CauseType.APPROVED_REQUEST, record.getCauseType());
        assertEquals(request.getId(), record.getRunRequestId(), "the record must link to the request detail via runRequestId");

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("appr-x#1", reloaded.getExecutedRunId(), "the request must carry the executed run id");
    }

    /** T-10-05: the dashboard is gated by ViewHistory (403 without, 200 with). */
    @Test
    public void t_10_05_dashboardWithoutViewHistoryIs403() throws Exception {
        JenkinsRule.WebClient noHistory = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).login("nohist");
        Page denied = noHistory.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/dashboard/"), HttpMethod.GET));
        assertEquals(403, denied.getWebResponse().getStatusCode(), "without ViewHistory the dashboard must answer 403");

        JenkinsRule.WebClient viewer = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false).login("viewer");
        Page allowed = viewer.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/dashboard/"), HttpMethod.GET));
        assertEquals(200, allowed.getWebResponse().getStatusCode(), "with ViewHistory the dashboard must render");
    }

    /**
     * T-10-07 (approximated, matrix note 22): a multibranch child job is record-only.
     * The multibranch project is real (WorkflowMultiBranchProject) but its branch comes from
     * SingleSCMSource over NullSCM instead of a real SCM indexing fixture, and the
     * control-exemption is asserted in the branch job's natural state (no approval property
     * can be configured on a computed child). Branch builds may FAIL (NullSCM has no
     * Jenkinsfile); a completion of any result must still be recorded.
     */
    @Test
    public void t_10_07_multibranchChildIsRecordedButNotControlled() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "mb");
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        Queue.Item indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();

        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "indexing must have created the branch child job");
        if (branch.getLastBuild() == null) {
            // some branch-api versions do not auto-build the discovered branch
            QueueTaskFuture<WorkflowRun> first = branch.scheduleBuild2(0);
            assertNotNull(first, "the branch child must be buildable");
            first.get();
        }
        j.waitUntilNoActivity();
        long recorded = FileStore.get().listRunRecords(YearMonth.now()).stream()
                .filter(rec -> "mb/main".equals(rec.getJobFullName()))
                .count();
        assertTrue(recorded >= 1, "a multibranch child completion must be recorded");

        // record-only: with run control on, a manual user-cause run of the child is not blocked
        int nextNumber = branch.getNextBuildNumber();
        QueueTaskFuture<WorkflowRun> manual = branch.scheduleBuild2(0,
                new CauseAction(userCause("u1")));
        assertNotNull(manual, "run control must not block a multibranch child (record-only)");
        manual.get();
        j.waitUntilNoActivity();
        assertNotNull(branch.getBuildByNumber(nextNumber), "the manual run must exist");
        assertNotNull(record("mb/main#" + nextNumber), "the manual run must be recorded too");
    }

    // ---------------------------------------------------------------- helpers

    /** A cause outside every classified family; must fall back to OTHER. */
    public static final class SyntheticCause extends Cause {
        @Override
        public String getShortDescription() {
            return "synthetic test cause";
        }
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** A UserIdCause constructed while authenticated as the given user. */
    private Cause userCause(String userId) {
        try (ACLContext ignored = as(userId)) {
            return new Cause.UserIdCause();
        }
    }

    private RunRecord record(String runId) {
        return FileStore.get().listRunRecords(YearMonth.now()).stream()
                .filter(rec -> runId.equals(rec.getRunId()))
                .findFirst().orElse(null);
    }

    private void assertCauseType(String runId, CauseType expected) {
        RunRecord record = record(runId);
        assertNotNull(record, "a RunRecord must exist for " + runId);
        assertEquals(expected, record.getCauseType(), "wrong causeType for " + runId);
    }
}
