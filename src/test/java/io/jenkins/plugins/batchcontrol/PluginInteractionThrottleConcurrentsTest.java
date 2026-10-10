package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.plugins.throttleconcurrents.ThrottleMatrixProjectOptions;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (#36) — throttle-concurrents, a {@code QueueTaskDispatcher} that holds builds in
 * the queue beyond a per-project concurrency limit. Rows T-06-38 .. T-06-39.
 */
@WithJenkins
@Tag("core")
public class PluginInteractionThrottleConcurrentsTest {

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        job = j.createFreeStyleProject("throttle-x");
        ThrottleJobProperty throttle = new ThrottleJobProperty(1, 1, Collections.<String>emptyList(),
                true, "project", false, "", ThrottleMatrixProjectOptions.DEFAULT);
        job.addProperty(throttle);
        assertSame(throttle, job.getProperty(ThrottleJobProperty.class), "fixture: the throttle property must be the one the job reads back");
        assertTrue(throttle.getThrottleEnabled(), "fixture: throttling must be enabled");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /** T-06-38: an unapproved manual run of a throttled job is refused. */
    @Test
    public void t_06_38_unapprovedRunIsRefused() throws Exception {
        post(j, "u1", job.getUrl() + "build");
        assertBlocked(j, job, 1, 0);
    }

    /** T-06-39: an approved run of the throttled job starts and is queued exactly once. */
    @Test
    public void t_06_39_approvedRunStarts() throws Exception {
        requestAndApprove(job);
        FreeStyleBuild build = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatusSuccess(build);
    }
}
