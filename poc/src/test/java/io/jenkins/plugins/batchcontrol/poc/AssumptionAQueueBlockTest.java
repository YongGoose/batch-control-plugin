package io.jenkins.plugins.batchcontrol.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.cli.CLICommandInvoker;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.queue.QueueTaskFuture;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Design assumption A: a {@link hudson.model.Queue.QueueDecisionHandler} intercepts every
 * scheduling path (UI build button, REST /build, REST /buildWithParameters, CLI build,
 * Pipeline Replay, upstream `build` step) and an approved-run marker action passes through.
 */
@WithJenkins
class AssumptionAQueueBlockTest {

    @AfterEach
    void tearDown() {
        PocQueueDecisionHandler.reset();
    }

    /** Waits up to 10s for the decision handler to record a blocked attempt. */
    private static boolean waitForBlockedAttempt() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!PocQueueDecisionHandler.observedBlockedCauses.isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static void assertBlockedAndNothingRan(JenkinsRule j, Job<?, ?> job, int expectedNextBuildNumber) {
        assertEquals(0, j.jenkins.getQueue().getItems().length, "queue must stay empty");
        assertEquals(expectedNextBuildNumber, job.getNextBuildNumber(), "build number must not increase");
        assertTrue(!PocQueueDecisionHandler.observedBlockedCauses.isEmpty(),
                "the decision handler must have been consulted and blocked the attempt");
        printObservedCauses();
    }

    private static void printObservedCauses() {
        List<String> names = new ArrayList<>();
        synchronized (PocQueueDecisionHandler.observedBlockedCauses) {
            for (List<Cause> causes : PocQueueDecisionHandler.observedBlockedCauses) {
                for (Cause c : causes) {
                    names.add(c.getClass().getName());
                }
            }
        }
        System.out.println("[PoC-A] observed blocked causes: " + names);
    }

    private static void assertObservedCause(Class<? extends Cause> type) {
        synchronized (PocQueueDecisionHandler.observedBlockedCauses) {
            boolean found = PocQueueDecisionHandler.observedBlockedCauses.stream()
                    .flatMap(List::stream)
                    .anyMatch(type::isInstance);
            assertTrue(found, "expected a blocked cause of type " + type.getName());
        }
    }

    /** Path 1: the sidebar "Build Now" button on the job page. */
    @Test
    void uiBuildButtonIsBlocked(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("plain");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("plain");
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnScriptError(false);
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            HtmlPage page = wc.getPage(p);
            HtmlAnchor buildNow = page.getAnchors().stream()
                    .filter(a -> a.getHrefAttribute().contains("build?delay=0sec"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Build Now link not found on the job page"));
            Page after = buildNow.click();
            if (!waitForBlockedAttempt() && after instanceof HtmlPage) {
                // If HtmlUnit did not run the task-link POST JavaScript, the GET fallback lands on
                // Jenkins' "POST required" interstitial; submitting its form is the same UI flow.
                HtmlForm form = ((HtmlPage) after).getForms().stream()
                        .filter(f -> "post".equalsIgnoreCase(f.getMethodAttribute()))
                        .findFirst()
                        .orElse(null);
                if (form != null) {
                    j.submit(form);
                }
            }
            if (!waitForBlockedAttempt()) {
                fail("UI build button never reached the QueueDecisionHandler");
            }
        }
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild(), "no build must have run");
        assertBlockedAndNothingRan(j, p, 1);
        assertObservedCause(Cause.UserIdCause.class);
    }

    /** Path 2: REST POST /job/X/build. */
    @Test
    void restBuildIsBlocked(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("plain");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("plain");
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            WebRequest req = new WebRequest(new URL(j.getURL(), "job/plain/build?delay=0sec"), HttpMethod.POST);
            wc.addCrumb(req);
            Page resp = wc.getPage(req);
            System.out.println("[PoC-A] POST /build status=" + resp.getWebResponse().getStatusCode());
        }
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
        assertBlockedAndNothingRan(j, p, 1);
        assertObservedCause(Cause.UserIdCause.class);
    }

    /** Path 3: REST POST /job/X/buildWithParameters. */
    @Test
    void restBuildWithParametersIsBlocked(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("paramjob");
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("FOO", "default")));
        PocQueueDecisionHandler.BLOCKED_JOBS.add("paramjob");
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
            WebRequest req = new WebRequest(
                    new URL(j.getURL(), "job/paramjob/buildWithParameters?FOO=bar"), HttpMethod.POST);
            wc.addCrumb(req);
            Page resp = wc.getPage(req);
            System.out.println("[PoC-A] POST /buildWithParameters status=" + resp.getWebResponse().getStatusCode());
        }
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
        assertBlockedAndNothingRan(j, p, 1);
        assertObservedCause(Cause.UserIdCause.class);
    }

    /** Path 4: CLI build command. */
    @Test
    void cliBuildIsBlocked(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("plain");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("plain");
        CLICommandInvoker.Result result = new CLICommandInvoker(j, "build").invokeWithArgs("plain");
        System.out.println("[PoC-A] CLI build exit=" + result.returnCode());
        System.out.println("[PoC-A] CLI build stderr=" + result.stderr());
        j.waitUntilNoActivity();
        assertNull(p.getLastBuild());
        assertBlockedAndNothingRan(j, p, 1);
        // hint says the CLI cause is hudson.cli.BuildCommand$CLICause, a UserIdCause subtype
        assertObservedCause(Cause.UserIdCause.class);
    }

    /** Path 5: Pipeline Replay of a completed run. */
    @Test
    void pipelineReplayIsBlocked(JenkinsRule j) throws Exception {
        WorkflowJob pipe = j.createProject(WorkflowJob.class, "pipe");
        pipe.setDefinition(new CpsFlowDefinition("echo 'v1'", true));
        WorkflowRun b1 = j.buildAndAssertSuccess(pipe);
        PocQueueDecisionHandler.BLOCKED_JOBS.add("pipe");

        ReplayAction replay = b1.getAction(ReplayAction.class);
        assertNotNull(replay, "ReplayAction should be available on the completed run");
        QueueTaskFuture<?> f = replay.run("echo 'v2'", Collections.emptyMap());
        assertNull(f, "replay submission must be rejected (no queue task future)");
        j.waitUntilNoActivity();
        assertBlockedAndNothingRan(j, pipe, 2);
    }

    /** Path 6: `build` step from an upstream Pipeline job. */
    @Test
    void upstreamBuildStepIsBlocked(JenkinsRule j) throws Exception {
        FreeStyleProject down = j.createFreeStyleProject("downstream");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("downstream");
        WorkflowJob up = j.createProject(WorkflowJob.class, "up");
        up.setDefinition(new CpsFlowDefinition("build job: 'downstream', wait: false", true));

        WorkflowRun r = up.scheduleBuild2(0).get();
        j.waitUntilNoActivity();
        System.out.println("[PoC-A] upstream build result=" + r.getResult());
        System.out.println("[PoC-A] upstream log tail=" + String.join(" | ", r.getLog(20)));

        assertNull(down.getLastBuild(), "downstream must not have built");
        assertBlockedAndNothingRan(j, down, 1);
        assertObservedCause(Cause.UpstreamCause.class);
    }

    /** Counterpart: a submission carrying the approved-run marker action passes through. */
    @Test
    void markerActionPassesThrough(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("plain");
        PocQueueDecisionHandler.BLOCKED_JOBS.add("plain");
        QueueTaskFuture<FreeStyleBuild> f = p.scheduleBuild2(0, (Cause) null, new PocMarkerAction());
        assertNotNull(f, "marker-carrying submission must be scheduled");
        FreeStyleBuild b = j.assertBuildStatusSuccess(f);
        assertEquals(1, b.getNumber());
        assertTrue(PocQueueDecisionHandler.observedBlockedCauses.isEmpty(),
                "nothing should have been recorded as blocked");
    }
}
