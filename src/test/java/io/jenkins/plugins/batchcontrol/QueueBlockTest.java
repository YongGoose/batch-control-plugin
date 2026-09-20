package io.jenkins.plugins.batchcontrol;

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
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.CLICommandInvoker;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 6 (blocking every unapproved manual run path at queue entry, with the
 * documented pass-throughs). Matrix rows T-06-01 .. T-06-12 and T-06-16.
 * T-06-13/14/15 (guidance and sidebar) live in RunRequestWebTest.
 *
 * Blocking assertion baseline (matrix header): queue empty + getNextBuildNumber() unchanged
 * + no build after waitUntilNoActivity().
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and docs/POC-RESULTS.md only (no src/main knowledge).
 */
public class QueueBlockTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;

    @Before
    public void setUp() throws Exception {
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
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "pipe");
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(pipeline); // first run happens before the job becomes protected
        pipeline.addProperty(new BatchControlJobProperty(true));

        ReplayAction replay = pipeline.getBuildByNumber(1).getAction(ReplayAction.class);
        assertNotNull("a completed pipeline build must expose the replay action", replay);
        assertNull("replay of an approval-required job must not enter the queue",
                replay.run("echo 'replayed'", Collections.<String, String>emptyMap()));

        assertBlocked(pipeline, 2);
        assertEquals("no second build may exist", 1, pipeline.getBuilds().size());
    }

    /** T-06-05: an upstream build step is blocked when blockUpstream=true (no allow list). */
    @Test
    public void t_06_05_upstreamBuildStepBlockedWhenBlockUpstream() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        job.addProperty(property);

        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        // PoC-confirmed side effect: a blocked build step fails the upstream run
        j.buildAndAssertStatus(Result.FAILURE, upstream);

        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-06: a TimerTrigger (cron) cause passes by default. */
    @Test
    public void t_06_06_timerCausePassesByDefault() throws Exception {
        protect(job);
        // matrix note 4: reproduce cron firing by scheduling with a TimerTriggerCause
        Future<FreeStyleBuild> future = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull("a timer cause must pass by default", future);
        j.assertBuildStatusSuccess(future);
    }

    /** T-06-07: blockTimer=true blocks the cron cause silently (no exception, just refused + log). */
    @Test
    public void t_06_07_timerCauseBlockedWhenBlockTimer() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(true);
        job.addProperty(property);

        // an unattended cause is refused quietly: scheduleBuild2 returns null, nothing is thrown
        Future<FreeStyleBuild> future = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNull("a blocked timer cause must be refused at queue entry", future);
        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-08: the plugin's own approved submission passes and runs the build. */
    @Test
    public void t_06_08_approvedRequestPassesThrough() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
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

        assertEquals("the approved submission must pass the queue gate", 1, job.getBuilds().size());
        FreeStyleBuild build = job.getBuildByNumber(1);
        j.assertBuildStatusSuccess(build);
        assertNotNull("the run must carry the plugin's ApprovedCause",
                build.getCause(ApprovedCause.class));
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
        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull("an upstream cause must pass by default", build);
        j.assertBuildStatusSuccess(build);
    }

    /** T-06-11: blockUpstream=true with allowedUpstreamJobs=[Y] lets Y through. */
    @Test
    public void t_06_11_allowedUpstreamJobPasses() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Arrays.asList("y"));
        job.addProperty(property);

        WorkflowJob upstream = j.createProject(WorkflowJob.class, "y");
        upstream.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull("an allow-listed upstream job must pass", build);
        j.assertBuildStatusSuccess(build);
    }

    /** T-06-12: an upstream job outside the allow list is blocked and itself ends FAILURE. */
    @Test
    public void t_06_12_nonAllowedUpstreamIsBlockedAndUpstreamFails() throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Arrays.asList("y"));
        job.addProperty(property);

        WorkflowJob other = j.createProject(WorkflowJob.class, "z");
        other.setDefinition(new CpsFlowDefinition("build job: 'batch-x', wait: false", true));
        // PoC-confirmed side effect pinned as regression: the blocked build step fails Z
        j.buildAndAssertStatus(Result.FAILURE, other);

        assertBlocked(job, 1);
        assertTrue(job.getBuilds().isEmpty());
    }

    /** T-06-16: a job without approvalRequired is unaffected by run control. */
    @Test
    public void t_06_16_nonApprovalJobUnaffected() throws Exception {
        FreeStyleProject free = j.createFreeStyleProject("free-x");
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getPage(new WebRequest(wc.createCrumbedUrl(free.getUrl() + "build"), HttpMethod.POST));
        j.waitUntilNoActivity();

        FreeStyleBuild build = free.getBuildByNumber(1);
        assertNotNull("a non-approval job must build normally with run control on", build);
        j.assertBuildStatusSuccess(build);
    }

    // ---------------------------------------------------------------- helpers

    private void protect(FreeStyleProject target) throws Exception {
        target.addProperty(new BatchControlJobProperty(true));
    }

    /** Matrix common blocking baseline. */
    private void assertBlocked(Job<?, ?> target, int nextBuildNumberBefore) throws Exception {
        assertEquals("the queue must stay empty", 0, j.jenkins.getQueue().getItems().length);
        j.waitUntilNoActivity();
        assertEquals("nextBuildNumber must not move", nextBuildNumberBefore, target.getNextBuildNumber());
        assertEquals("the queue must still be empty after settling",
                0, j.jenkins.getQueue().getItems().length);
    }
}
