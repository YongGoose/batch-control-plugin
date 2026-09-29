package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Util;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39), the upgrade: jobs that exist when this version is first installed are
 * recorded as activated ({@code activatedBy = upgrade}, one ACTIVATED record each), exactly
 * once, keyed by a stored schema marker, so installing never stops an existing schedule; jobs
 * created afterwards start not activated. Matrix rows T-06a-32..37.
 *
 * <p>How a pre-upgrade JENKINS_HOME is produced black-box (note 95): the first session creates
 * the jobs the way the previous release would have held them, then removes
 * {@code batch-control/activations/} — the directory that holds both the per-job state and the
 * {@code .schema} marker, and that the previous release never had. The next session therefore
 * boots a home with existing jobs and no activation store, which is exactly an upgrade. The
 * first session's own boot saw no jobs, so it can have seeded nothing (asserted).
 *
 * <p>Written from docs/SPEC.md item 6a, docs/ARCHITECTURE.md "Activation store" and
 * docs/DESIGN-ACTIVATION-APPROVAL.md section 6 only (no src/main knowledge).
 */
public class ActivationSeedingTest {

    private static final String[] LEGACY = {"legacy-cron", "legacy-free", "legacy-f/inner"};

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /**
     * T-06a-32 (P0): the upgrade seeds every existing job exactly as SPEC says, and the
     * existing schedules keep running; a job created after the upgrade is not activated.
     */
    @Test
    public void t_06a_32_existingJobsAreSeededAsActivatedOnUpgrade() throws Throwable {
        session.then(r -> buildPreUpgradeHome(r, true));
        session.then(r -> {
            secure(r);
            assertTrue(schemaMarker(r).isFile(), "the upgrade must write the activations/.schema marker");
            assertSeeded(r);

            FreeStyleProject cron = r.jenkins.getItemByFullName("legacy-cron", FreeStyleProject.class);
            r.assertBuildStatusSuccess(cron.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            WorkflowJob inner = r.jenkins.getItemByFullName("legacy-f/inner", WorkflowJob.class);
            r.assertBuildStatusSuccess(inner.scheduleBuild2(0, new hudson.model.CauseAction(new TimerTrigger.TimerTriggerCause())));

            FreeStyleProject fresh = createClearedControlledJob(r, "post-upgrade");
            assertFalse(ActivationService.get().isActivated(fresh), "a job created after the upgrade starts not activated");
            assertNull(fresh.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            assertBlocked(r, fresh, 1, 0);
            assertTrue(recordsFor(ChangeType.ACTIVATED, "post-upgrade").isEmpty());
        });
    }

    /**
     * T-06a-33 (P0): exactly once. A further restart adds no ACTIVATED record and does not
     * activate the job created after the upgrade.
     */
    @Test
    public void t_06a_33_restartDoesNotReSeed() throws Throwable {
        session.then(r -> buildPreUpgradeHome(r, true));
        session.then(r -> {
            secure(r);
            assertSeeded(r);
            createClearedControlledJob(r, "post-upgrade");
        });
        session.then(r -> {
            secure(r);
            assertSeeded(r); // still exactly one ACTIVATED record per legacy job
            FreeStyleProject fresh = r.jenkins.getItemByFullName("post-upgrade", FreeStyleProject.class);
            assertFalse(ActivationService.get().isActivated(fresh), "a restart must not seed a job created after the upgrade");
            assertTrue(recordsFor(ChangeType.ACTIVATED, "post-upgrade").isEmpty());
            assertNull(fresh.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            assertBlocked(r, fresh, 1, 0);
        });
    }

    /**
     * T-06a-34 (P0): the seeding is keyed by the stored marker, not by "the store is empty". With
     * the marker kept and every state file removed, a restart seeds nothing again.
     */
    @Test
    public void t_06a_34_seedingIsKeyedByTheSchemaMarker() throws Throwable {
        session.then(r -> buildPreUpgradeHome(r, true));
        session.then(r -> {
            secure(r);
            assertSeeded(r);
            File[] states = activationsDir(r).listFiles((d, n) -> n.endsWith(".xml") && !n.startsWith("."));
            assertNotNull(states);
            assertEquals(LEGACY.length, states.length, "one state file per seeded job");
            for (File state : states) {
                assertTrue(state.delete(), "fixture: remove " + state);
            }
            assertTrue(schemaMarker(r).isFile(), "fixture: the marker stays");
        });
        session.then(r -> {
            secure(r);
            for (String name : LEGACY) {
                Job<?, ?> job = r.jenkins.getItemByFullName(name, Job.class);
                assertFalse(ActivationService.get().isActivated(job),
                        name + " must not be re-seeded while the schema marker exists");
                assertEquals(1, recordsFor(ChangeType.ACTIVATED, name).size(),
                        "no second ACTIVATED record for " + name);
            }
        });
    }

    /**
     * T-06a-35 (P0): a fresh install (first start with no jobs) writes the marker and seeds
     * nothing; a job created afterwards is not activated, also after a restart.
     */
    @Test
    public void t_06a_35_freshInstallSeedsNothing() throws Throwable {
        session.then(r -> {
            secure(r);
            assertTrue(schemaMarker(r).isFile(), "the first start must write the activations/.schema marker");
            assertTrue(ApproverFormFixtures.records(ChangeType.ACTIVATED).isEmpty(), "nothing to seed on a fresh install");
            FreeStyleProject job = createClearedControlledJob(r, "fresh-x");
            assertFalse(ActivationService.get().isActivated(job));
        });
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName("fresh-x", FreeStyleProject.class);
            assertFalse(ActivationService.get().isActivated(job), "a restart must not seed a job created after the first start");
            assertTrue(ApproverFormFixtures.records(ChangeType.ACTIVATED).isEmpty());
        });
    }

    /**
     * T-06a-36 (P0, restart recovery): an approved activation of a new job and an approved hold
     * of a seeded job both survive a restart, and the restart does not re-activate the held job.
     */
    @Test
    public void t_06a_36_approvedActivationAndHoldSurviveRestart() throws Throwable {
        session.then(r -> buildPreUpgradeHome(r, true));
        session.then(r -> {
            secure(r);
            assertSeeded(r);
            FreeStyleProject fresh = createClearedControlledJob(r, "post-upgrade");
            decide(fresh, ActivationRequest.Action.ACTIVATE);
            assertTrue(ActivationService.get().isActivated(fresh));
            FreeStyleProject cron = r.jenkins.getItemByFullName("legacy-cron", FreeStyleProject.class);
            decide(cron, ActivationRequest.Action.HOLD);
            assertFalse(ActivationService.get().isActivated(cron));
        });
        session.then(r -> {
            secure(r);
            FreeStyleProject fresh = r.jenkins.getItemByFullName("post-upgrade", FreeStyleProject.class);
            assertTrue(ActivationService.get().isActivated(fresh), "an approved activation must survive a restart");
            r.assertBuildStatusSuccess(fresh.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

            FreeStyleProject cron = r.jenkins.getItemByFullName("legacy-cron", FreeStyleProject.class);
            assertFalse(ActivationService.get().isActivated(cron), "an approved hold must survive a restart (no re-seed)");
            int next = cron.getNextBuildNumber();
            int builds = cron.getBuilds().size();
            assertNull(cron.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
            assertBlocked(r, cron, next, builds);
            assertEquals(1, recordsFor(ChangeType.ACTIVATED, "legacy-cron").size());
            assertEquals(1, recordsFor(ChangeType.HELD, "legacy-cron").size());
        });
    }

    /**
     * T-06a-37 (P0): the upgrade seeds whether or not run control is on. An instance upgraded
     * with run control off keeps its schedules when run control is switched on later.
     */
    @Test
    public void t_06a_37_upgradeWithRunControlOffStillSeeds() throws Throwable {
        session.then(r -> buildPreUpgradeHome(r, false));
        session.then(r -> {
            secure(r);
            assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "premise: run control off");
            assertSeeded(r);
            BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
            cfg.setRunControlEnabled(true);
            cfg.save();
            FreeStyleProject cron = r.jenkins.getItemByFullName("legacy-cron", FreeStyleProject.class);
            r.assertBuildStatusSuccess(cron.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void secure(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
    }

    /**
     * Session 1: the jobs a previous release held, created while run control is off so no
     * creation default applies, then made the way an operator had them — a controlled cron job
     * with its switches off, an uncontrolled job, and a controlled Pipeline inside a folder. Then
     * the activation store the previous release never had is removed.
     */
    private static void buildPreUpgradeHome(JenkinsRule r, boolean runControlOnAtEnd) throws Exception {
        secure(r);
        assertTrue(ApproverFormFixtures.records(ChangeType.ACTIVATED).isEmpty(),
                "premise: the first boot saw no jobs, so nothing was seeded");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject cron = r.createFreeStyleProject("legacy-cron");
        cron.addTrigger(new TimerTrigger("0 3 * * *"));
        setBatchControl(cron, cleared());
        r.createFreeStyleProject("legacy-free");
        Folder folder = r.jenkins.createProject(Folder.class, "legacy-f");
        WorkflowJob inner = folder.createProject(WorkflowJob.class, "inner");
        inner.setDefinition(new CpsFlowDefinition("echo 'nightly'", true));
        setBatchControl(inner, cleared());

        cfg.setRunControlEnabled(runControlOnAtEnd);
        cfg.save();

        Util.deleteRecursive(activationsDir(r));
        assertFalse(activationsDir(r).exists(), "fixture: the home now has no activation store (pre-upgrade)");
    }

    private static void assertSeeded(JenkinsRule r) {
        for (String name : LEGACY) {
            Job<?, ?> job = r.jenkins.getItemByFullName(name, Job.class);
            assertNotNull(job, name + " must survive the restart");
            assertTrue(ActivationService.get().isActivated(job), name + " existed before the upgrade and must be activated");
            ActivationState state = ActivationService.get().getState(job);
            assertNotNull(state, name + " must have an activation state");
            assertEquals("upgrade", state.getActivatedBy(), name + ": activatedBy must be 'upgrade'");
            List<?> records = recordsFor(ChangeType.ACTIVATED, name);
            assertEquals(1, records.size(), name + " must have exactly one ACTIVATED record: " + records);
        }
    }

    private static BatchControlJobProperty cleared() {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        return property;
    }

    private static FreeStyleProject createClearedControlledJob(JenkinsRule r, String name) throws Exception {
        FreeStyleProject job = r.createFreeStyleProject(name);
        setBatchControl(job, cleared());
        return job;
    }

    /** u1 requests, a1 approves (the approver list is set by session 1 and persists). */
    private static void decide(Job<?, ?> job, ActivationRequest.Action action) {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        if (!cfg.getApprovers().contains("a1")) {
            cfg.setApprovers(Arrays.asList("a1"));
        }
        ActivationRequest request;
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            request = ActivationService.get().create(job, action, "restart recovery " + action, List.of("a1"));
        }
        try (ACLContext ignored = ACL.as2(token("a1"))) {
            ActivationService.get().approve(request.getId(), "ok");
        }
    }

    private static File activationsDir(JenkinsRule r) {
        return new File(r.jenkins.getRootDir(), "batch-control/activations");
    }

    private static File schemaMarker(JenkinsRule r) {
        return new File(activationsDir(r), ".schema");
    }
}
