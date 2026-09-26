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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.UnstableBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 11 (automatic incident registration and handling) plus the D-19 logTail
 * masking criterion. Matrix rows T-11-01 .. T-11-07 and T-RT-13.
 *
 * Incidents are asserted through the IncidentService public API only.
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 2/5 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class IncidentTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
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
        assertNotNull(incident, "a FAILURE must open an incident regardless of its cause (cron included)");
        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertEquals("cron-fail", incident.getJobFullName());
        assertEquals("FAILURE", incident.getResult());
        List<String> logTail = incident.getLogTail();
        assertNotNull(logTail, "the incident must carry a console log excerpt");
        assertFalse(logTail.isEmpty(), "the logTail excerpt must not be empty for a real build");
        assertTrue(logTail.size() <= 100, "the logTail excerpt is capped at 100 lines");
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
        assertNotNull(second, "the approved rerun must have executed");
        j.assertBuildStatusSuccess(second);

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertEquals("rerun-x#2", reloaded.getResolvedByRunId(), "the successful linked rerun must be recorded on the incident");
        assertEquals(IncidentStatus.OPEN, reloaded.getStatus(), "the status must stay OPEN - resolution is a human decision");
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
        assertNull(incidentForRun("unstable-x#1"), "UNSTABLE is outside incidentResults=[FAILURE], no incident may open");

        // positive control so the negative assertion cannot pass vacuously
        FreeStyleProject failing = j.createFreeStyleProject("fail-x");
        failing.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        assertNotNull(incidentForRun("fail-x#1"), "FAILURE stays inside the configured results and must open an incident");
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
        assertNotNull(acknowledged, "the ACKNOWLEDGED transition must be recorded");
        assertEquals("u1", acknowledged.getBy());
        assertEquals("taking a look", acknowledged.getComment());
        assertNotNull(acknowledged.getAt(), "the transition must carry a timestamp");

        IncidentTransition resolved = transitionTo(reloaded, IncidentStatus.RESOLVED);
        assertNotNull(resolved, "the RESOLVED transition must be recorded");
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
        assertEquals(IncidentStatus.RESOLVED, IncidentService.get().load(incident.getId()).getStatus(), "the rejected reverse transition must not change the state");

        try (ACLContext ignored = as("u2")) {
            IncidentService.get().addComment(incident.getId(), "post-mortem attached");
        }
        Incident commented = IncidentService.get().load(incident.getId());
        assertEquals(IncidentStatus.RESOLVED, commented.getStatus(), "a comment on RESOLVED must not change the state");
        List<IncidentTransition> transitions = commented.getTransitions();
        IncidentTransition last = transitions.get(transitions.size() - 1);
        assertEquals("post-mortem attached", last.getComment(), "the comment must be recorded on the incident history");
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
        assertEquals("2026-09-01", incident.getParameters().get("DATE"), "the incident must keep the original build parameters");

        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        assertEquals(RequestStatus.PENDING, rerun.getStatus());
        assertEquals("2026-09-01", rerun.getParameters().get("DATE"), "the rerun request must be prefilled with the original parameters");
        assertEquals(incident.getId(), rerun.getIncidentId(), "the rerun request must link back to the incident");
        assertEquals("u1", rerun.getRequester());

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds().contains(rerun.getId()), "the incident must list the rerun request id");
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
        assertNotNull(run.getExecutor(), "the running build must expose its executor");
        run.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("u1"));
        j.waitForCompletion(run);
        j.assertBuildStatus(Result.ABORTED, run);
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("abort-inc#1");
        assertNotNull(incident, "ABORTED is inside the configured results and must open an incident");
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
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (hudson.model.Cause) null,
                new ParametersAction(new PasswordParameterValue("TOKEN", secretValue))));
        j.waitUntilNoActivity();

        Incident incident = incidentForRun("leak-x#1");
        assertNotNull(incident, "the FAILURE must have opened an incident");
        List<String> logTail = incident.getLogTail();
        assertNotNull(logTail);

        String echoedLine = null;
        for (String line : logTail) {
            assertFalse(line.contains(secretValue), "no logTail line may carry the sensitive parameter value in plain text: "
                    + line);
            if (line.contains("token is")) {
                echoedLine = line;
            }
        }
        assertNotNull(echoedLine, "the echoed console line must be part of the excerpt");
        assertTrue(echoedLine.contains("********"), "the sensitive value must be masked as ******** (D-19), was: " + echoedLine);
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
        assertNotNull(incident, "test fixture: the failed build must have opened an incident");
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

    private static void assertRefused(String message, Executable action) {
        boolean refused = false;
        try {
            action.execute();
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(refused, message);
    }
}
