package io.jenkins.plugins.batchcontrol;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.IncidentTransition;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.IOException;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.UnstableBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 11 (automatic incident registration and handling) plus the D-19 logTail
 * masking criterion. Matrix rows T-11-01 .. T-11-07 and T-RT-13.
 *
 * Incidents are asserted through the IncidentService public API only.
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 2/5 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
public class IncidentTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.VIEW_HISTORY).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** T-11-01: a cron-caused FAILURE opens an incident (cause never matters) with a bounded logTail. */
    @Test
    public void t_11_01_cronFailureOpensIncidentWithLogTail() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("cron-fail");
        job.getBuildersList().add(new FailureBuilder());
        // matrix note 4: cron firing reproduced by a TimerTriggerCause schedule
        j.assertBuildStatus(Result.FAILURE,
                job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("cron-fail#1");
        assertNotNull("a FAILURE must open an incident regardless of its cause (cron included)",
                incident);
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertEquals("cron-fail", incident.getJobFullName());
        assertEquals("FAILURE", incident.getResult());
        List<String> logTail = incident.getLogTail();
        assertNotNull("the incident must carry a console log excerpt", logTail);
        assertFalse("the logTail excerpt must not be empty for a real build", logTail.isEmpty());
        assertTrue("the logTail excerpt is capped at 100 lines", logTail.size() <= 100);
    }

    /** T-11-02: an approved, successful rerun sets resolvedByRunId but never auto-resolves. */
    @Test
    public void t_11_02_successfulRerunLinksResolvedByRunIdButStaysOpen() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("rerun-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.addProperty(new BatchControlJobProperty(true));
        job.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0,
                new TimerTrigger.TimerTriggerCause(),
                new ParametersAction(new StringParameterValue("DATE", "2026-09-01"))));
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("rerun-x#1");
        assertNotNull(incident);
        assertEquals(IncidentStatus.OPEN, incident.getStatus());

        job.getBuildersList().clear(); // the rerun must succeed
        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(rerun.getId(), "approved rerun");
        }
        j.waitUntilNoActivity();

        FreeStyleBuild second = job.getBuildByNumber(2);
        assertNotNull("the approved rerun must have executed", second);
        j.assertBuildStatusSuccess(second);

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertEquals("the successful linked rerun must be recorded on the incident",
                "rerun-x#2", reloaded.getResolvedByRunId());
        assertEquals("the status must stay OPEN - resolution is a human decision",
                IncidentStatus.OPEN, reloaded.getStatus());
    }

    /** T-11-03: with incidentResults=[FAILURE] an UNSTABLE completion opens nothing. */
    @Test
    public void t_11_03_unstableOutsideConfiguredResultsOpensNoIncident() throws Exception {
        cfg.setIncidentResults(Arrays.asList("FAILURE"));
        cfg.save();

        FreeStyleProject unstable = j.createFreeStyleProject("unstable-x");
        unstable.getBuildersList().add(new UnstableBuilder());
        j.assertBuildStatus(Result.UNSTABLE, unstable.scheduleBuild2(0));
        j.waitUntilNoActivity();
        assertNull("UNSTABLE is outside incidentResults=[FAILURE], no incident may open",
                incidentForRun("unstable-x#1"));

        // positive control so the negative assertion cannot pass vacuously
        FreeStyleProject failing = j.createFreeStyleProject("fail-x");
        failing.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        assertNotNull("FAILURE stays inside the configured results and must open an incident",
                incidentForRun("fail-x#1"));
    }

    /** T-11-04: acknowledge and resolve each record user, time and comment in transitions. */
    @Test
    public void t_11_04_transitionsRecordUserTimeAndComment() throws Exception {
        Incident incident = openIncident("ack-x");

        try (ACLContext ignored = as("u1")) {
            IncidentService.get().acknowledge(incident.getId(), "taking a look");
        }
        try (ACLContext ignored = as("u2")) {
            IncidentService.get().resolve(incident.getId(), "root cause fixed");
        }

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertEquals(IncidentStatus.RESOLVED, reloaded.getStatus());

        IncidentTransition acknowledged = transitionTo(reloaded, IncidentStatus.ACKNOWLEDGED);
        assertNotNull("the ACKNOWLEDGED transition must be recorded", acknowledged);
        assertEquals("u1", acknowledged.getBy());
        assertEquals("taking a look", acknowledged.getComment());
        assertNotNull("the transition must carry a timestamp", acknowledged.getAt());

        IncidentTransition resolved = transitionTo(reloaded, IncidentStatus.RESOLVED);
        assertNotNull("the RESOLVED transition must be recorded", resolved);
        assertEquals("u2", resolved.getBy());
        assertEquals("root cause fixed", resolved.getComment());
        assertNotNull(resolved.getAt());
    }

    /** T-11-05: no reverse transition from RESOLVED, but comments are still allowed there. */
    @Test
    public void t_11_05_reverseTransitionRejectedButCommentsAllowedOnResolved() throws Exception {
        Incident incident = openIncident("resolve-x");
        try (ACLContext ignored = as("u1")) {
            IncidentService.get().acknowledge(incident.getId(), "on it");
            IncidentService.get().resolve(incident.getId(), "done");
        }
        assertEquals(IncidentStatus.RESOLVED,
                IncidentService.get().load(incident.getId()).getStatus());

        assertRefused("RESOLVED -> ACKNOWLEDGED is a reverse transition and must be rejected",
                () -> {
                    try (ACLContext ignored = as("u1")) {
                        IncidentService.get().acknowledge(incident.getId(), "reopening?");
                    }
                });
        assertEquals("the rejected reverse transition must not change the state",
                IncidentStatus.RESOLVED, IncidentService.get().load(incident.getId()).getStatus());

        try (ACLContext ignored = as("u2")) {
            IncidentService.get().addComment(incident.getId(), "post-mortem attached");
        }
        Incident commented = IncidentService.get().load(incident.getId());
        assertEquals("a comment on RESOLVED must not change the state",
                IncidentStatus.RESOLVED, commented.getStatus());
        List<IncidentTransition> transitions = commented.getTransitions();
        IncidentTransition last = transitions.get(transitions.size() - 1);
        assertEquals("the comment must be recorded on the incident history",
                "post-mortem attached", last.getComment());
        assertEquals("u2", last.getBy());
    }

    /** T-11-06: the rerun request is prefilled with the original parameters and linked both ways. */
    @Test
    public void t_11_06_rerunRequestPrefilledAndLinked() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("link-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.addProperty(new BatchControlJobProperty(true));
        job.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0,
                new TimerTrigger.TimerTriggerCause(),
                new ParametersAction(new StringParameterValue("DATE", "2026-09-01"))));
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("link-x#1");
        assertNotNull(incident);
        assertEquals("the incident must keep the original build parameters",
                "2026-09-01", incident.getParameters().get("DATE"));

        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        assertEquals(RequestStatus.PENDING, rerun.getStatus());
        assertEquals("the rerun request must be prefilled with the original parameters",
                "2026-09-01", rerun.getParameters().get("DATE"));
        assertEquals("the rerun request must link back to the incident",
                incident.getId(), rerun.getIncidentId());
        assertEquals("u1", rerun.getRequester());

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue("the incident must list the rerun request id",
                reloaded.getRerunRequestIds().contains(rerun.getId()));
    }

    /** T-11-07: with ABORTED added to incidentResults an aborted build opens an incident. */
    @Test
    public void t_11_07_abortedOpensIncidentWhenConfigured() throws Exception {
        cfg.setIncidentResults(Arrays.asList("FAILURE", "UNSTABLE", "ABORTED"));
        cfg.save();

        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "abort-inc");
        pipeline.setDefinition(new CpsFlowDefinition("sleep 60", true));
        QueueTaskFuture<WorkflowRun> future = pipeline.scheduleBuild2(0);
        assertNotNull(future);
        WorkflowRun run = future.waitForStart();
        long deadline = System.currentTimeMillis() + 10_000;
        while (run.getExecutor() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNotNull("the running build must expose its executor", run.getExecutor());
        run.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("u1"));
        j.waitForCompletion(run);
        j.assertBuildStatus(Result.ABORTED, run);
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("abort-inc#1");
        assertNotNull("ABORTED is inside the configured results and must open an incident",
                incident);
        assertEquals("ABORTED", incident.getResult());
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
    }

    /**
     * T-RT-13 (D-19): a sensitive parameter value echoed into the console is masked
     * in the incident logTail; the plaintext never appears there.
     */
    @Test
    public void t_rt_13_secretParameterValueIsMaskedInLogTail() throws Exception {
        final String secretValue = "S3CR3T-TOKEN-X7K9Q2";

        FreeStyleProject job = j.createFreeStyleProject("leak-x");
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("TOKEN",
                        Secret.fromString("placeholder"), "service token")));
        job.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                    BuildListener listener) throws InterruptedException, IOException {
                // password parameter values reach the build environment in plain text
                listener.getLogger().println("token is "
                        + build.getEnvironment(listener).get("TOKEN"));
                return false; // fail the build so an incident opens
            }
        });
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, null,
                new ParametersAction(new PasswordParameterValue("TOKEN", secretValue))));
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("leak-x#1");
        assertNotNull("the FAILURE must have opened an incident", incident);
        List<String> logTail = incident.getLogTail();
        assertNotNull(logTail);

        String echoedLine = null;
        for (String line : logTail) {
            assertFalse("no logTail line may carry the sensitive parameter value in plain text: "
                    + line, line.contains(secretValue));
            if (line.contains("token is")) {
                echoedLine = line;
            }
        }
        assertNotNull("the echoed console line must be part of the excerpt", echoedLine);
        assertTrue("the sensitive value must be masked as ******** (D-19), was: " + echoedLine,
                echoedLine.contains("********"));
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** Fails one build of a fresh job and returns its OPEN incident. */
    private Incident openIncident(String jobName) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(jobName);
        job.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        Incident incident = incidentForRun(jobName + "#1");
        assertNotNull("test fixture: the failed build must have opened an incident", incident);
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        return incident;
    }

    private Incident incidentForRun(String runId) {
        return IncidentService.get().list(YearMonth.now()).stream()
                .filter(incident -> runId.equals(incident.getRunId()))
                .findFirst().orElse(null);
    }

    private static IncidentTransition transitionTo(Incident incident, IncidentStatus status) {
        List<IncidentTransition> transitions = incident.getTransitions();
        for (int i = transitions.size() - 1; i >= 0; i--) {
            if (transitions.get(i).getStatus() == status) {
                return transitions.get(i);
            }
        }
        return null;
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
