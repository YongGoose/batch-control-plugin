package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.plugins.parameterizedtrigger.AbstractBuildParameters;
import hudson.plugins.parameterizedtrigger.BlockableBuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.BuildTrigger;
import hudson.plugins.parameterizedtrigger.BuildTriggerConfig;
import hudson.plugins.parameterizedtrigger.PredefinedBuildParameters;
import hudson.plugins.parameterizedtrigger.ResultCondition;
import hudson.plugins.parameterizedtrigger.TriggerBuilder;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.Collections;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (#36) — parameterized-trigger. Runs started by parameterized-trigger carry an
 * {@code UpstreamCause}, so they follow the existing {@code blockUpstream} semantics (SPEC item 6,
 * D-16): pass by default, blocked when {@code blockUpstream=true} and the upstream job is not on
 * {@code allowedUpstreamJobs}. Rows T-06-27 .. T-06-30.
 *
 * <p>"Manual trigger path" = a user starts the (uncontrolled) upstream job by hand and its
 * {@link TriggerBuilder} build step triggers the approval-required job. "Upstream path" = the
 * post-build {@link BuildTrigger} of an upstream run.
 */
@WithJenkins
public class PluginInteractionParameterizedTriggerTest {

    private JenkinsRule j;

    private FreeStyleProject target;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        target = j.createFreeStyleProject("pt-x");
    }

    /**
     * T-06-27: u1 starts upstream Y by hand; Y's parameterized-trigger build step targets X
     * (blockUpstream=true, empty allow list). X does not reach the queue.
     */
    @Test
    public void t_06_27_manualTriggerBuildStepBlockedWhenBlockUpstream() throws Exception {
        blockUpstream(target);
        FreeStyleProject upstream = upstreamWithBuildStep("pt-y");

        post(j, "u1", upstream.getUrl() + "build");
        j.waitUntilNoActivity();
        assertUpstreamRanByHand(upstream);

        assertBlocked(j, target, 1, 0);
    }

    /**
     * T-06-28: the post-build parameterized trigger of an upstream run targets X
     * (blockUpstream=true). X does not reach the queue.
     */
    @Test
    public void t_06_28_postBuildTriggerBlockedWhenBlockUpstream() throws Exception {
        blockUpstream(target);
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject("pt-post"));
        upstream.getPublishersList().add(new BuildTrigger(new BuildTriggerConfig(
                "pt-x", ResultCondition.ALWAYS, true, parameters())));

        BatchControlFixtures.activateAsAdmin(upstream); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(upstream);
        j.waitUntilNoActivity();

        assertBlocked(j, target, 1, 0);
    }

    /**
     * T-06-29 (false-positive guard of T-06-27/28): with blockUpstream left at its default the same
     * manual trigger path runs X exactly once, with an UpstreamCause — so the trigger really fires
     * and the two rows above measure the gate.
     */
    @Test
    public void t_06_29_triggerPassesByDefault() throws Exception {
        setBatchControl(target, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(target); // SPEC item 6a: the upstream door needs an activation (note 91)
        FreeStyleProject upstream = upstreamWithBuildStep("pt-y");

        post(j, "u1", upstream.getUrl() + "build");
        j.waitUntilNoActivity();
        assertUpstreamRanByHand(upstream);

        assertEquals(1, target.getBuilds().size(), "an upstream trigger must pass by default (blockUpstream=false), exactly once");
        assertNotNull(target.getBuildByNumber(1).getCause(Cause.UpstreamCause.class), "the triggered run must carry an UpstreamCause");
    }

    /**
     * T-06-30: with parameterized-trigger configured on an upstream job, an approved request of X
     * is still queued exactly once.
     */
    @Test
    public void t_06_30_approvedRunQueuedExactlyOnce() throws Exception {
        blockUpstream(target);
        upstreamWithBuildStep("pt-y");
        requestAndApprove(target);
        j.assertBuildStatusSuccess(assertApprovedRunQueuedExactlyOnce(j, target));
    }

    // ---------------------------------------------------------------- helpers

    private void blockUpstream(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockUpstream(true);
        assertSame(property, setBatchControl(job, property));
        assertTrue(job.getProperty(BatchControlJobProperty.class).isBlockUpstream(), "fixture: blockUpstream must be on");
        BatchControlFixtures.activate(job); // SPEC item 6a: blockUpstream must be the only reason (note 91)
    }

    private FreeStyleProject upstreamWithBuildStep(String name) throws Exception {
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject(name));
        upstream.getBuildersList().add(new TriggerBuilder(new BlockableBuildTriggerConfig(
                "pt-x", null, parameters())));
        return upstream;
    }

    private static List<AbstractBuildParameters> parameters() {
        return Collections.singletonList(new PredefinedBuildParameters("BATCH_DATE=2026-09-28"));
    }

    private static void assertUpstreamRanByHand(FreeStyleProject upstream) {
        assertEquals(1, upstream.getBuilds().size(), "fixture: the upstream job must have run once");
        Cause.UserIdCause cause = upstream.getBuildByNumber(1).getCause(Cause.UserIdCause.class);
        assertNotNull(cause, "fixture: the upstream run must have been started by a user");
        assertEquals("u1", cause.getUserId());
    }
}
