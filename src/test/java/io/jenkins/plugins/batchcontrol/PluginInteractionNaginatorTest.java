package io.jenkins.plugins.batchcontrol;

import com.chikli.hudson.plugin.naginator.FixedDelay;
import com.chikli.hudson.plugin.naginator.NaginatorPublisher;
import com.chikli.hudson.plugin.naginator.NaginatorRetryAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
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
 * SPEC item 6 (#36) — naginator. Naginator re-queues a failed build, either automatically
 * (post-build {@link NaginatorPublisher}) or by hand (the build's "Retry" action), copying the
 * failed build's causes and parameters. Rows T-06-33 .. T-06-35.
 *
 * <p>How the rows are derived: SPEC item 6 says "an approved run is queued exactly once", and
 * D-23 says the approval marker is consumed by one queue entry and that reuse by requeue or
 * rebuild is blocked. A naginator retry of an approved run is a second queue entry for the same
 * approval, so it is refused — the manual retry because it is also a manual run without an
 * approved request, the automatic one because it is a requeue of a consumed approval. The
 * automatic case is flagged for human confirmation in the matrix (note 55): an operator may
 * expect naginator to keep retrying a failed batch; SPEC as written does not allow it.
 */
@WithJenkins
public class PluginInteractionNaginatorTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-33: u1 presses naginator's Retry on a failed approved run; no second run is queued.
     */
    @Test
    public void t_06_33_manualRetryOfApprovedRunIsBlocked() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("nag-manual");
        job.getBuildersList().add(new FailureBuilder());
        setBatchControl(job, new BatchControlJobProperty(true));

        requestAndApprove(job);
        FreeStyleBuild failed = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatus(Result.FAILURE, failed);
        assertNotNull(failed.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry on the failed approved run");

        post(j, "u1", failed.getUrl() + "retry/");

        assertBlocked(j, job, 2, 1);
    }

    /**
     * T-06-34: an approved run that fails on a job with naginator's automatic retry configured is
     * not re-queued (the approved run is queued exactly once, D-23). Needs human confirmation —
     * matrix note 55.
     */
    @Test
    public void t_06_34_automaticRetryOfApprovedRunIsBlocked() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("nag-auto");
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(retryOnce());
        setBatchControl(job, new BatchControlJobProperty(true));

        requestAndApprove(job);
        j.waitUntilNoActivity();

        assertBlocked(j, job, 2, 1);
        j.assertBuildStatus(Result.FAILURE, job.getBuildByNumber(1));
    }

    /**
     * T-06-35 (false-positive guard of T-06-33/34): on an uncontrolled job the same automatic
     * retry does queue a second build, and so does a manual Retry — naginator is active and the
     * rows above measure the gate.
     */
    @Test
    public void t_06_35_retriesRunOnUncontrolledJob() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("nag-free"));
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(retryOnce());

        j.buildAndAssertStatus(Result.FAILURE, job);
        j.waitUntilNoActivity();
        assertEquals(2, job.getBuilds().size(), "fixture: naginator must retry a failed uncontrolled build automatically");

        post(j, "u1", job.getBuildByNumber(2).getUrl() + "retry/");
        j.waitUntilNoActivity();
        assertEquals(3, job.getBuilds().size(), "fixture: naginator's manual Retry must queue a build of an uncontrolled job");
    }

    /** Retry once, immediately, on any failure. */
    private static NaginatorPublisher retryOnce() {
        return new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0));
    }
}
