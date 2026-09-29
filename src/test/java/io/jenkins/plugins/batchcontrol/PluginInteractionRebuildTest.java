package io.jenkins.plugins.batchcontrol;

import com.sonyericsson.rebuild.RebuildAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * SPEC item 6 (#36) — rebuild plugin. The rebuild action re-queues a finished build with the
 * original build's causes and parameters. Rows T-06-24 .. T-06-26.
 *
 * <p>D-23: the approved-run marker is consumed by one queue entry; reuse by requeue or rebuild is
 * blocked. So a rebuild of an approved, executed run must not queue a second run.
 */
@WithJenkins
public class PluginInteractionRebuildTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-24: a rebuild of a build that ran before the job became approval-required does not
     * reach the queue.
     */
    @Test
    public void t_06_24_rebuildOfUnapprovedBuildIsBlocked() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rb-x"));
        BatchControlFixtures.activateAsAdmin(job); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(job);
        setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleBuild first = job.getBuildByNumber(1);
        assertNotNull(first.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action on build #1");

        post(j, "u1", first.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
    }

    /**
     * T-06-25: a rebuild of an approved, executed run does not queue a second run (D-23 single
     * consumption; the approved run is queued exactly once).
     */
    @Test
    public void t_06_25_rebuildOfApprovedRunIsBlocked() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("rb-approved");
        setBatchControl(job, new BatchControlJobProperty(true));
        requestAndApprove(job);
        FreeStyleBuild approved = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        assertNotNull(approved.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action on the approved build");

        post(j, "u1", approved.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
    }

    /**
     * T-06-26 (false-positive guard): on an uncontrolled job the same rebuild request does queue a
     * second build, so T-06-24/25 measure the gate and not a broken rebuild call.
     */
    @Test
    public void t_06_26_rebuildRunsOnUncontrolledJob() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rb-free"));
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, ActivationFixtures.userCause("u1"))); // a human run (note 100)

        // D-47 (security-14 S-14-01): a Rebuild click is a person acting now (a live UserIdCause
        // from this very POST), unlike an automatic naginator retry (T-06-54) whose causes are
        // only inherited from the build it retries — so this job needs no activation here.
        post(j, "u1", job.getBuildByNumber(1).getUrl() + "rebuild/");
        j.waitUntilNoActivity();

        assertEquals(2, job.getBuilds().size(), "fixture: a rebuild of an uncontrolled job must queue a second build");
        assertNotNull(job.getBuildByNumber(2), "build #2 must exist");
    }
}
