package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovalQueueDecisionHandler;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import hudson.cli.CLICommandInvoker;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (blocking every unapproved manual run path at queue entry, with the
 * documented pass-throughs). Matrix rows T-06-01 .. T-06-12 and T-06-16.
 * T-06-13/14/15 (guidance and sidebar) live in RunRequestWebTest.
 *
 * Blocking assertion baseline (matrix header): queue empty + getNextBuildNumber() unchanged
 * + no build after waitUntilNoActivity().
 *
 * Fixture note (D-31): run control is on before the jobs are created, so every job here is
 * born with a BatchControlJobProperty already attached. Job settings therefore go in through
 * {@link BatchControlFixtures#setBatchControl} and "not controlled" through
 * {@link BatchControlFixtures#uncontrolled} — a bare addProperty would be shadowed by the
 * default one.
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/POC-RESULTS.md only (no src/main knowledge).
 */
@WithJenkins
public class QueueBlockTest {

    /** Item 6a fixture ids on the unsecured instance (any authenticated id holds every permission). */
    private static final String UNSECURED_REQUESTER = "bc-requester";
    private static final String UNSECURED_APPROVER = "bc-approver";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
    }

    /** T-06-01: POST /job/X/build does not enter the queue. */
    @Test
    public void t_06_01_restBuildPostIsBlocked() throws Exception {
        protect(job);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getPage(new WebRequest(wc.createCrumbedUrl(job.getUrl() + "build"), HttpMethod.POST));
        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-02: POST /job/X/buildWithParameters does not enter the queue. */
    @Test
    public void t_06_02_restBuildWithParametersIsBlocked() throws Exception {
        FreeStyleProject parametrized = j.createFreeStyleProject("batch-p");
        parametrized.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2000-01-01")));
        protect(parametrized);

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        WebRequest request = new WebRequest(
                wc.createCrumbedUrl(parametrized.getUrl() + "buildWithParameters"), HttpMethod.POST);
        request.setRequestParameters(Collections.singletonList(new NameValuePair("DATE", "2026-09-01")));
        wc.getPage(request);

        assertBlocked(parametrized, 1);
        assertTrue(parametrized.getBuilds().isEmpty());
    }

    /** T-06-03: CLI build does not enter the queue. */
    @Test
    public void t_06_03_cliBuildIsBlocked() throws Exception {
        protect(job);
        new CLICommandInvoker(j, "build").invokeWithArgs("batch-x");
        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-04: Pipeline Replay of an approval-required job does not enter the queue. */
    @Test
    public void t_06_04_pipelineReplayIsBlocked() throws Exception {
        WorkflowJob pipeline = uncontrolled(j.createProject(WorkflowJob.class, "pipe"));
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        activate(pipeline, UNSECURED_REQUESTER, UNSECURED_APPROVER); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(pipeline); // first run happens before the job becomes protected
        setBatchControl(pipeline, new BatchControlJobProperty(true));

        ReplayAction replay = pipeline.getBuildByNumber(1).getAction(ReplayAction.class);
        assertNotNull(replay, "a completed pipeline build must expose the replay action");
        assertNull(replay.run("echo 'replayed'", Collections.<String, String>emptyMap()), "replay of an approval-required job must not enter the queue");

        assertBlocked(pipeline, 2);
        assertEquals(1, pipeline.getBuilds().size(), "no second build may exist");
    }

    /** T-06-05: an upstream build step is blocked when blockUpstream=true (no allow list). */
    @Test
    public void t_06_05_upstreamBuildStepBlockedWhenBlockUpstream() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        setBatchControl(job, property);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a: only blockUpstream may be the reason (note 91)

        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        // PoC-confirmed side effect: a blocked build step fails the upstream run
        activate(upstream, UNSECURED_REQUESTER, UNSECURED_APPROVER); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertStatus(Result.FAILURE, upstream);

        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-06: a TimerTrigger (cron) cause passes by default. */
    @Test
    public void t_06_06_timerCausePassesByDefault() throws Exception {
        protect(job);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a: the timer door is open only once activated (note 91)
        // matrix note 4: reproduce cron firing by scheduling with a TimerTriggerCause
        Future<FreeStyleBuild> future = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(future, "a timer cause must pass by default");
        j.assertBuildStatusSuccess(future);
    }

    /** T-06-07: blockTimer=true blocks the cron cause silently (no exception, just refused + log). */
    @Test
    public void t_06_07_timerCauseBlockedWhenBlockTimer() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(true);
        setBatchControl(job, property);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a: only blockTimer may be the reason (note 91)

        // an unattended cause is refused quietly: scheduleBuild2 returns null, nothing is thrown
        Future<FreeStyleBuild> future = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull(future, "a blocked timer cause must be refused at queue entry");
        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** A frequent cron on a locked job logs one INFO line per hour, not one per refusal. */
    @Test
    public void blockedTimerInfoLogIsRateLimited() throws Exception {
        FreeStyleProject cronJob = j.createFreeStyleProject("timer-log-rate-limit");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(true);
        setBatchControl(cronJob, property);
        activate(cronJob, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a: the refusal is blockTimer's (note 91)

        List<LogRecord> infoRecords = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                if (logRecord.getLevel() == Level.INFO
                        && logRecord.getMessage().contains("'timer-log-rate-limit'")) {
                    infoRecords.add(logRecord);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger(ApprovalQueueDecisionHandler.class.getName());
        logger.addHandler(handler);
        try {
            assertNull(cronJob.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                    "the first timer cause must be refused");
            assertNull(cronJob.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                    "the second timer cause must be refused");
        } finally {
            logger.removeHandler(handler);
        }
        assertEquals(1, infoRecords.size(), "only the first refusal within the hour is logged at INFO");
    }

    /** T-06-08: the plugin's own approved submission passes and runs the build. */
    @Test
    public void t_06_08_approvedRequestPassesThrough() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1") // D-38 (#24): requesters need Item/Build
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        protect(job);

        RunRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "approved batch run", "a1");
        }
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();

        assertEquals(1, job.getBuilds().size(), "the approved submission must pass the queue gate");
        FreeStyleBuild build = job.getBuildByNumber(1);
        j.assertBuildStatusSuccess(build);
        assertNotNull(build.getCause(ApprovedCause.class), "the run must carry the plugin's ApprovedCause");
    }

    /** T-06-09: clicking the job page's build anchor (WebClient) does not enter the queue. */
    @Test
    public void t_06_09_buildNowAnchorClickIsBlocked() throws Exception {
        protect(job);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        HtmlPage page = wc.getPage(job);
        // If run control replaced the sidebar entry there may be no build anchor at all;
        // if any build anchor is still rendered, clicking it must not schedule anything.
        for (HtmlAnchor anchor : page.getAnchors()) {
            String href = anchor.getHrefAttribute();
            if (href == null || href.contains("batch-control")) {
                continue;
            }
            if (href.endsWith("/build") || href.contains("/build?")
                    || href.equals("build") || href.startsWith("build?")) {
                try {
                    anchor.click();
                } catch (Exception blockedResponse) {
                    // a 4xx response from the blocked POST is acceptable here
                }
            }
        }
        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-10: UpstreamCause passes by default (blockUpstream unset). */
    @Test
    public void t_06_10_upstreamPassesByDefault() throws Exception {
        protect(job);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a (note 91)
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        activate(upstream, UNSECURED_REQUESTER, UNSECURED_APPROVER); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "an upstream cause must pass by default");
        j.assertBuildStatusSuccess(build);
    }

    /** T-06-11: blockUpstream=true with allowedUpstreamJobs=[Y] lets Y through. */
    @Test
    public void t_06_11_allowedUpstreamJobPasses() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Arrays.asList("y"));
        setBatchControl(job, property);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a (note 91)

        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        activate(upstream, UNSECURED_REQUESTER, UNSECURED_APPROVER); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "an allow-listed upstream job must pass");
        j.assertBuildStatusSuccess(build);
    }

    /** T-06-12: an upstream job outside the allow list is blocked and itself ends FAILURE. */
    @Test
    public void t_06_12_nonAllowedUpstreamIsBlockedAndUpstreamFails() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Arrays.asList("y"));
        setBatchControl(job, property);
        activate(job, UNSECURED_REQUESTER, UNSECURED_APPROVER); // item 6a (note 91)

        WorkflowJob other = j.createProject(WorkflowJob.class, "z");
        other.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        // PoC-confirmed side effect pinned as regression: the blocked build step fails Z
        activate(other, UNSECURED_REQUESTER, UNSECURED_APPROVER); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertStatus(Result.FAILURE, other);

        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-16: a job without approvalRequired is unaffected by run control. */
    @Test
    public void t_06_16_nonApprovalJobUnaffected() throws Exception {
        // D-31 attaches approvalRequired=true at creation while run control is on; this row is
        // about a job that carries no batch-control property at all, so strip it back to that.
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("free-x"));
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl(free.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();

        FreeStyleBuild build = free.getBuildByNumber(1);
        assertNotNull(build, "a non-approval job must build normally with run control on");
        j.assertBuildStatusSuccess(build);
    }

    /**
     * T-SEC-32 (security-07 S-01, SPEC item 6 / D-16): a job's own approved run may legitimately
     * trigger itself again (for example {@code build job: env.JOB_NAME, wait: false}, guarded to
     * fire only once). A same-job UpstreamCause is not a marker replay: D-16 says an
     * UpstreamCause passes by default (blockUpstream=false) regardless of which job it names, so
     * the retriggered run must reach the queue and start. In-row guard: the same self-trigger is
     * still refused when blockUpstream=true — an empty/unset allow list blocks every upstream
     * job, including the job itself (D-16), exactly like T-06-12's non-allow-listed upstream job.
     */
    @Test
    public void t_sec_32_selfUpstreamCauseFollowsBlockUpstreamPolicy() throws Exception {
        secureWithRunControl(j);

        WorkflowJob passes = createSelfTriggeringJob("self-up-pass", false);
        requestAndApprove(passes);
        j.waitUntilNoActivity();
        assertEquals(2, passes.getBuilds().size(), "the approved run's self-trigger must reach the queue and start when blockUpstream=false (security-07 S-01, D-16)");
        WorkflowRun retriggered = passes.getBuildByNumber(2);
        assertNotNull(retriggered, "build #2 (the self-trigger) must exist");
        j.assertBuildStatusSuccess(retriggered);
        Cause.UpstreamCause upstreamCause = retriggered.getCause(Cause.UpstreamCause.class);
        assertNotNull(upstreamCause, "the retriggered run must carry an UpstreamCause");
        assertEquals(passes.getFullName(), upstreamCause.getUpstreamProject(), "the UpstreamCause must name the same job");

        // In-row guard: with blockUpstream=true the self-trigger is refused like any other
        // non-allow-listed upstream job (T-06-12).
        WorkflowJob blocked = createSelfTriggeringJob("self-up-block", true);
        requestAndApprove(blocked);
        j.waitUntilNoActivity();
        assertEquals(1, blocked.getBuilds().size(), "the self-trigger must not have produced a second build when blockUpstream=true");
        assertBlocked(blocked, 2);
    }

    // ---------------------------------------------------------------- helpers

    private void protect(FreeStyleProject target) throws Exception {
        setBatchControl(target, new BatchControlJobProperty(true));
    }

    /** A Pipeline job whose first build triggers a fresh queue submission of itself. */
    private WorkflowJob createSelfTriggeringJob(String name, boolean blockUpstream) throws Exception {
        WorkflowJob self = j.jenkins.createProject(WorkflowJob.class, name);
        self.setDefinition(new CpsFlowDefinition(
                "if (currentBuild.number == 1) {\n"
                        + "  build job: '" + name + "', wait: false\n"
                        + "}\n", true));
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(blockUpstream);
        setBatchControl(self, property);
        activate(self); // item 6a: the self-trigger is an upstream cause (note 91)
        return self;
    }

    /** Matrix common blocking baseline. */
    private void assertBlocked(Job<?, ?> target, int nextBuildNumberBefore) throws Exception {
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(nextBuildNumberBefore, target.getNextBuildNumber(), "nextBuildNumber must not move");
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must still be empty after settling");
    }
}
