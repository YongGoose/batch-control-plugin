package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.scm.NullSCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a as amended by D-46 (c) (security-13 S-13-03): computed folders (multibranch
 * projects, organization folders) carry the activation for their children. One created while
 * run control is on starts not activated, the ACTIVATE/HOLD request is made on it, and a
 * computed child passes an unattended cause only if its nearest computed-folder ancestor is
 * activated. D-45 applies to computed folders as to jobs. Matrix rows T-06a-46/47.
 *
 * <p>The request is filed through the job-level URL of the multibranch project
 * ({@code job/<mb>/batch-control/activation/submit}), the same contract as for a job (note 102).
 * The branch source is a {@link SingleSCMSource} over {@link NullSCM}, so branch builds may
 * FAIL (no Jenkinsfile); the rows count builds, not results.
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-45/D-46 and
 * docs/reports/security-13.md only (no src/main knowledge).
 */
@WithJenkins
public class ActivationComputedFolderTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06a-46 (P0, S-13-03): a multibranch project created under run control starts not
     * activated; its branch job's timer and SCM causes are refused (and no indexing-triggered
     * build runs) until an ACTIVATE request filed on the multibranch project is approved. Then
     * the branch builds from its timer and from an upstream cause without an activation of its
     * own; an approved HOLD on the multibranch project refuses the branch's timer again.
     */
    @Test
    public void t_06a_46_multibranchCreatedUnderRunControlGatesItsChildren() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "ctl-mb");
        assertFalse(isActivated(mb), "D-46: a computed folder created under run control starts not activated");
        ActivationState state = ActivationService.get().getState(mb);
        assertTrue(state == null || !state.isActivated(), "no activation state may say it is activated");

        WorkflowJob branch = index(mb);
        assertTrue(branch.getBuilds().isEmpty(),
                "D-46: an indexing-triggered build of a child of a non-activated computed folder must not run");
        int next = branch.getNextBuildNumber();
        assertNull(branch.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause())),
                "D-46: the child's timer cause must be refused while its computed folder is not activated");
        assertBlocked(j, branch, next, 0);
        assertNull(branch.scheduleBuild2(0, new CauseAction(new SCMTrigger.SCMTriggerCause("push"))),
                "D-46: the child's SCM cause must be refused while its computed folder is not activated");
        assertBlocked(j, branch, next, 0);

        String id = submitActivationOk(j, "u1", mb, "ACTIVATE", "put ctl-mb into service", "a1");
        assertEquals("ctl-mb", ActivationService.get().load(id).getJobFullName(),
                "the ACTIVATE request is made on the computed folder");
        assertNull(branch.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause())),
                "a PENDING request must not open the child's timer");
        assertBlocked(j, branch, next, 0);
        assertSuccess(decideActivation(j, "a1", id, "approve", "ok"), "a1's approval");
        assertTrue(isActivated(mb), "the approved ACTIVATE marks the computed folder activated");
        assertEquals(1, ActivationFixtures.recordsFor(ChangeType.ACTIVATED, "ctl-mb").size(),
                "exactly one ACTIVATED record for the computed folder");

        assertNotNull(branch.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause())),
                "a child of an activated computed folder must pass its timer");
        j.waitUntilNoActivity();
        assertNotNull(branch.getBuildByNumber(next), "the timer run must exist");
        assertNotNull(branch.scheduleBuild2(0, new CauseAction(new Cause.UpstreamCause(upstreamBuild()))),
                "a child of an activated computed folder must pass an upstream cause");
        j.waitUntilNoActivity();
        assertNotNull(branch.getBuildByNumber(next + 1), "the upstream run must exist");
        assertTrue(ActivationService.get().list().stream().noneMatch(r -> "ctl-mb/main".equals(r.getJobFullName())),
                "the child needs no activation request of its own");

        String hold = submitActivationOk(j, "u1", mb, "HOLD", "pause ctl-mb", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "a1's approval of the hold");
        assertFalse(isActivated(mb), "the approved HOLD marks the computed folder not activated");
        int afterHold = branch.getNextBuildNumber();
        int builds = branch.getBuilds().size();
        assertNull(branch.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause())),
                "a child of a held computed folder must be refused on its timer");
        assertBlocked(j, branch, afterHold, builds);
    }

    /**
     * T-06a-47 (P0, S-13-03 / D-45 for computed folders): a multibranch project created while
     * run control is off is activated at creation ({@code activatedBy = (uncontrolled)}), so
     * turning run control on later does not stop its children's timers.
     */
    @Test
    public void t_06a_47_multibranchCreatedWithRunControlOffIsActivatedAtCreation() throws Exception {
        cfg.setRunControlEnabled(false);
        cfg.save();
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "free-mb");
        assertTrue(isActivated(mb), "D-45/D-46: a computed folder created while run control is off is activated at creation");
        ActivationState state = ActivationService.get().getState(mb);
        assertNotNull(state, "the creation must be recorded as an activation state");
        assertEquals("(uncontrolled)", state.getActivatedBy(), "S-13-10: activatedBy = (uncontrolled)");

        cfg.setRunControlEnabled(true);
        cfg.save();
        WorkflowJob branch = index(mb);
        int next = branch.getNextBuildNumber();
        assertNotNull(branch.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause())),
                "the child's timer must pass once run control is on");
        j.waitUntilNoActivity();
        assertNotNull(branch.getBuildByNumber(next), "the timer run must exist");
        assertTrue(ActivationService.get().list().isEmpty(), "no activation request was needed");
    }

    // ---------------------------------------------------------------- helpers

    private WorkflowJob index(WorkflowMultiBranchProject mb) throws Exception {
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        Queue.Item indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "indexing must have created the branch child job");
        return branch;
    }

    /**
     * A finished build of an uncontrolled job started by a human cause (note 100). security-15
     * S-15-01: the submission, not only the {@code Cause}, must run while impersonating the
     * user, or the gate now (correctly) classifies it as unattended.
     */
    private FreeStyleBuild upstreamBuild() throws Exception {
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject("mb-up-" + System.nanoTime()));
        try (ACLContext ignored = ACL.as2(token("admin"))) {
            return j.assertBuildStatusSuccess(upstream.scheduleBuild2(0, new Cause.UserIdCause()));
        }
    }
}
