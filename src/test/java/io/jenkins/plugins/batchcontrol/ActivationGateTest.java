package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.CRON_FREESTYLE_XML;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39, D-46), the queue gate: for an unattended cause on any non-computed
 * job while run control is on, the gate passes only if the job is activated <em>and</em> its
 * {@code blockTimer} / {@code blockUpstream} does not block the cause, whatever
 * {@code approvalRequired} says (D-46). Matrix rows T-06a-01..09, T-06a-42.
 *
 * <p>Reading used throughout: note 91's "run-controlled job" (approvalRequired=true) is
 * superseded by D-46 for the unattended gate — every non-computed job needs activation while
 * run control is on (T-06a-07, note 100). Activation is about the unattended causes only: it
 * neither approves a manual run (T-06a-04) nor is needed by an approved run request (T-06a-05).
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-39 and
 * docs/DESIGN-ACTIVATION-APPROVAL.md only (no src/main knowledge).
 */
@WithJenkins
public class ActivationGateTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE,
                        BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06a-01 (P0): the hole #15 closes. A job created under run control has its two switches
     * cleared by an administrator; it is still not activated, so neither its timer nor an
     * upstream job may start it.
     */
    @Test
    public void t_06a_01_clearedSwitchesAloneNeverLetANonActivatedJobRun() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-new");
        clearSwitches(job);
        assertFalse(isActivated(job), "a job created after the first start must not be activated");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "item 6a: clearing blockTimer must not let a non-activated job run on its timer");
        assertBlocked(j, job, 1, 0);

        assertNull(job.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-1"))),
                "item 6a: clearing blockUpstream must not let a non-activated job run from an upstream trigger");
        assertBlocked(j, job, 1, 0);

        assertFalse(isActivated(job), "a refused cause must not activate anything");
        assertTrue(ActivationFixtures.recordsFor(ChangeType.ACTIVATED, "gate-new").isEmpty(),
                "no ACTIVATED record may exist for a job nobody activated");
    }

    /**
     * T-06a-02 (P0, positive twin of 01): the same job after an approved activation runs from its
     * timer and from an upstream build step; approvalRequired is untouched.
     */
    @Test
    public void t_06a_02_activatedJobWithClearedSwitchesRunsFromTimerAndUpstream() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-live");
        clearSwitches(job);
        activate(job);

        Future<FreeStyleBuild> firing = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(firing, "an activated job with blockTimer off must pass a timer cause");
        j.assertBuildStatusSuccess(firing);

        WorkflowJob upstream = uncontrolled(j.createProject(WorkflowJob.class, "gate-caller"));
        upstream.setDefinition(new CpsFlowDefinition("build job: 'gate-live', wait: true", true));
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        assertEquals(2, job.getBuilds().size(), "the timer run and the upstream run must both have built");
        assertNotNull(job.getBuildByNumber(2).getCause(Cause.UpstreamCause.class), "build #2 must be the upstream-caused run");
        assertTrue(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(),
                "activation must not change approvalRequired");
    }

    /**
     * T-06a-03 (P0): the AND in the other direction. An activated job with blockTimer on is still
     * refused on its timer, and with blockUpstream on (no allow list) still refused on an
     * upstream cause — activation never overrides the job's own switch.
     */
    @Test
    public void t_06a_03_activatedJobIsStillBlockedByItsOwnSwitches() throws Exception {
        FreeStyleProject timerJob = j.createFreeStyleProject("gate-timer-on");
        BatchControlJobProperty timerLocked = new BatchControlJobProperty(true);
        timerLocked.setBlockTimer(true);
        timerLocked.setBlockUpstream(false);
        setBatchControl(timerJob, timerLocked);
        activate(timerJob);

        assertNull(timerJob.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "an activated job with blockTimer on must still refuse its timer");
        assertBlocked(j, timerJob, 1, 0);
        j.assertBuildStatusSuccess(timerJob.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-3a"))));

        FreeStyleProject upJob = j.createFreeStyleProject("gate-up-on");
        BatchControlJobProperty upLocked = new BatchControlJobProperty(true);
        upLocked.setBlockTimer(false);
        upLocked.setBlockUpstream(true);
        setBatchControl(upJob, upLocked);
        activate(upJob);

        assertNull(upJob.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-3b"))),
                "an activated job with blockUpstream on must still refuse an upstream cause");
        assertBlocked(j, upJob, 1, 0);
        j.assertBuildStatusSuccess(upJob.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
    }

    /**
     * T-06a-04 (P0): activation is not a run approval. A manual run of an activated
     * approval-required job is still refused, through the REST build POST and a user cause.
     */
    @Test
    public void t_06a_04_activationDoesNotApproveManualRuns() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-manual");
        clearSwitches(job);
        activate(job);

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        int code = wc.getPage(new WebRequest(wc.createCrumbedUrl(job.getUrl() + "build"), HttpMethod.POST))
                .getWebResponse().getStatusCode();
        assertTrue(code >= 400, "a manual build of an activated approval-required job must still be refused, got HTTP " + code);
        assertBlocked(j, job, 1, 0);

        boolean refused;
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            refused = job.scheduleBuild2(0, new Cause.UserIdCause()) == null;
        } catch (RuntimeException guidance) {
            refused = true; // SPEC 6: a human cause may be refused with a guidance Failure
        }
        assertTrue(refused, "a user-caused submission of an activated job must still be refused");
        assertBlocked(j, job, 1, 0);
    }

    /**
     * T-06a-05 (P0): activation governs unattended causes only. An approved run request of a job
     * that was never activated still executes exactly once.
     */
    @Test
    public void t_06a_05_approvedRunRequestRunsWithoutActivation() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-request");
        assertFalse(isActivated(job), "premise: the job is not activated");

        RunRequest request;
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(), "month-end run", "a1");
        }
        try (ACLContext ignored = ACL.as2(token("a1"))) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();

        assertEquals(1, job.getBuilds().size(), "the approved run must execute exactly once without an activation");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(request.getId()).getStatus());
        assertFalse(isActivated(job), "running an approved request must not activate the job");
    }

    /**
     * T-06a-06 (P0): with run control off nothing changes. A non-activated job whose switches are
     * even on passes a timer and an upstream cause, as before the feature existed.
     */
    @Test
    public void t_06a_06_runControlOffLetsTimerAndUpstreamPass() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-off");
        BatchControlJobProperty locked = new BatchControlJobProperty(true);
        locked.setBlockTimer(true);
        locked.setBlockUpstream(true);
        setBatchControl(job, locked);
        assertFalse(isActivated(job), "premise: the job is not activated");

        cfg.setRunControlEnabled(false);
        cfg.save();

        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-6"))));
        j.waitUntilNoActivity();
        assertEquals(2, job.getBuilds().size(), "with run control off the timer and the upstream run must both build");

        cfg.setRunControlEnabled(true);
        cfg.save();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "guard: once run control is back on the same timer cause must be refused again");
        assertBlocked(j, job, 3, 2);
    }

    /**
     * T-06a-07 (P0, amended by D-46 (a)): {@code approvalRequired} governs human-originated runs
     * only. A job created under run control whose property says {@code approvalRequired=false}
     * (switches off) — and a twin with the property removed — is still refused on its timer and
     * on an upstream cause until it is activated; after an approved activation both build.
     * (Before D-46 the row asserted that such a job needed no activation, note 91.)
     */
    @Test
    public void t_06a_07_jobWithoutApprovalRequiredStillNeedsActivation() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gate-free");
        BatchControlJobProperty free = new BatchControlJobProperty(false);
        free.setBlockTimer(false);
        free.setBlockUpstream(false);
        setBatchControl(job, free);
        FreeStyleProject bare = uncontrolled(j.createFreeStyleProject("gate-bare"));

        for (FreeStyleProject target : Arrays.asList(job, bare)) {
            assertFalse(isActivated(target), "premise: " + target.getName() + " is not activated");
            assertNull(target.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                    "D-46: the timer of the non-activated " + target.getName() + " must be refused whatever approvalRequired says");
            assertBlocked(j, target, 1, 0);
            assertNull(target.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-7-" + target.getName()))),
                    "D-46: an upstream cause of the non-activated " + target.getName() + " must be refused");
            assertBlocked(j, target, 1, 0);

            activate(target);
            j.assertBuildStatusSuccess(target.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            j.assertBuildStatusSuccess(target.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild("gate-up-7b-" + target.getName()))));
        }
    }

    /**
     * T-06a-08 (P0, D-45): a job created while run control is off counts as activated at
     * creation ({@code activatedBy = (uncontrolled)}, S-13-10). Turning run control on and then making the
     * job approval-required (switches off) does not stop its timer — no activation request is
     * needed and none exists.
     */
    @Test
    public void t_06a_08_jobCreatedWhileRunControlOffKeepsRunningOnceControlled() throws Exception {
        cfg.setRunControlEnabled(false);
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("gate-later");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertTrue(isActivated(job), "D-45: a job created while run control is off is activated at creation");
        ActivationState state = ActivationService.get().getState(job);
        assertNotNull(state, "D-45: the creation must be recorded as an activation state");
        assertEquals("(uncontrolled)", state.getActivatedBy(), "D-45 / S-13-10: activatedBy = (uncontrolled), a value no user id can take");

        cfg.setRunControlEnabled(true);
        cfg.save();
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

        BatchControlJobProperty controlled = new BatchControlJobProperty(true);
        controlled.setBlockTimer(false);
        controlled.setBlockUpstream(false);
        setBatchControl(job, controlled);
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        assertEquals(3, job.getBuilds().size(), "every timer run must have built");
        assertTrue(ActivationService.get().list().isEmpty(), "no activation request was needed");

        // guard: the job switch still applies to it (the AND, T-06a-03)
        controlled.setBlockTimer(true);
        job.save();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "blockTimer on must still refuse");
        assertBlocked(j, job, 4, 3);
    }

    /**
     * T-06a-42 (P0, D-45 counterpart): a job created while run control is on is not activated at
     * creation, has no activation state claiming otherwise, and — switches cleared — its timer is
     * refused until an activation is approved.
     */
    @Test
    public void t_06a_42_jobCreatedWhileRunControlOnIsNotActivated() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gate-on");
        BatchControlJobProperty controlled = new BatchControlJobProperty(true);
        controlled.setBlockTimer(false);
        controlled.setBlockUpstream(false);
        setBatchControl(job, controlled);
        assertFalse(isActivated(job), "a job created while run control is on starts not activated");
        ActivationState state = ActivationService.get().getState(job);
        assertTrue(state == null || !state.isActivated(), "no activation state may say it is activated");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "a controlled, non-activated job must not run on its timer");
        assertBlocked(j, job, 1, 0);

        activate(job);
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
    }

    /**
     * T-06a-09 (P1, #21 applied to item 6a): a timer refused because the job is not activated is
     * not silent in the audit trail — it writes a TRIGGER_BLOCKED record naming the job and TIMER.
     * The detail names {@code activation} as the blocking switch (note 101, superseding note 92 (b)).
     */
    @Test
    public void t_06a_09_refusalForMissingActivationIsRecorded() throws Exception {
        FreeStyleProject job = createUnderRunControl("gate-audit");
        clearSwitches(job);

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused");
        List<ChangeRecord> records = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, "gate-audit");
        assertEquals(1, records.size(), "one TRIGGER_BLOCKED record for the refused timer: " + records);
        assertNotNull(records.get(0).getDetail());
        assertTrue(records.get(0).getDetail().contains("TIMER"), "the record must name the cause kind: " + records.get(0).getDetail());
        assertTrue(records.get(0).getDetail().toLowerCase(java.util.Locale.ROOT).contains("activation"),
                "the record must name activation as what blocked the timer (note 101): " + records.get(0).getDetail());
    }

    // ---------------------------------------------------------------- helpers

    /** Creates the job through REST createItem as the administrator while run control is on. */
    private FreeStyleProject createUnderRunControl(String name) throws Exception {
        JenkinsRule.WebClient admin = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        URL url = new URL(admin.createCrumbedUrl("createItem").toExternalForm() + "&name=" + name);
        WebRequest create = new WebRequest(url, HttpMethod.POST);
        create.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        create.setRequestBody(CRON_FREESTYLE_XML);
        int code = admin.getPage(create).getWebResponse().getStatusCode();
        assertTrue(code < 400, "creating " + name + " must succeed, got HTTP " + code);
        FreeStyleProject created = j.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(created, name + " must exist");
        return created;
    }

    /** An administrator clears both switches (approvalRequired stays on); premise asserted. */
    private FreeStyleProject clearSwitches(FreeStyleProject job) throws Exception {
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        try (ACLContext ignored = ACL.as2(token("admin"))) {
            setBatchControl(job, cleared);
        }
        BatchControlJobProperty read = job.getProperty(BatchControlJobProperty.class);
        assertTrue(read.isApprovalRequired(), "premise: approvalRequired on");
        assertFalse(read.isBlockTimer(), "premise: blockTimer cleared");
        assertFalse(read.isBlockUpstream(), "premise: blockUpstream cleared");
        return job;
    }

    /** A finished build of an uncontrolled job, started by a human cause (note 100). */
    private FreeStyleBuild upstreamBuild(String name) throws Exception {
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject(name));
        return j.assertBuildStatusSuccess(upstream.scheduleBuild2(0, ActivationFixtures.userCause("admin")));
    }
}
