package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.RequiredResourcesProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, acceptance line "with queue plugins installed (lockable-resources,
 * throttle-concurrents) the gate still refuses unapproved manual runs and an approved run still
 * starts" (#36) — lockable-resources, whose {@link RequiredResourcesProperty} makes the queue hold
 * a Freestyle build until its resource is free. Rows T-06-36 .. T-06-37.
 */
@WithJenkins
public class PluginInteractionLockableResourcesTest {

    private static final String RESOURCE = "batch-db";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
        assertTrue(LockableResourcesManager.get().createResource(RESOURCE), "fixture: the lockable resource must be created");
        assertNotNull(LockableResourcesManager.get().fromName(RESOURCE), "fixture: the lockable resource must exist");
        job = j.createFreeStyleProject("lock-x");
        RequiredResourcesProperty lock = new RequiredResourcesProperty(RESOURCE, null, null, null);
        job.addProperty(lock);
        assertSame(lock, job.getProperty(RequiredResourcesProperty.class), "fixture: the lock property must be the one the job reads back");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /** T-06-36: an unapproved manual run of a job requiring a lockable resource is refused. */
    @Test
    public void t_06_36_unapprovedRunIsRefused() throws Exception {
        post(j, "u1", job.getUrl() + "build");
        assertBlocked(j, job, 1, 0);
    }

    /** T-06-37: an approved run of the same job starts (takes the resource) and is queued exactly once. */
    @Test
    public void t_06_37_approvedRunStarts() throws Exception {
        requestAndApprove(job);
        FreeStyleBuild build = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatusSuccess(build);
    }
}
