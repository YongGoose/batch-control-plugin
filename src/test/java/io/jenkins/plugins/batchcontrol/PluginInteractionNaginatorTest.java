package io.jenkins.plugins.batchcontrol;

import com.chikli.hudson.plugin.naginator.FixedDelay;
import com.chikli.hudson.plugin.naginator.NaginatorPublisher;
import com.chikli.hudson.plugin.naginator.NaginatorRetryAction;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 *
 * <p>D-47 (security-14 S-14-01): an automatic retry is unattended even though the build it
 * retries was started by a person — causes inherited from the retried build do not make the
 * retry human — so it needs the job activated (SPEC 6a) on every job, not only an
 * approval-required one; T-06-54 pins the refusal and its TRIGGER_BLOCKED record when the job
 * is not activated. A Rebuild click, by contrast, is a person acting now (see
 * {@link PluginInteractionRebuildTest#t_06_26_rebuildRunsOnUncontrolledJob}).
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
     * T-06-35 (false-positive guard of T-06-33/34): on an uncontrolled, ACTIVATED job the same
     * automatic retry does queue a second build, and so does a manual Retry — naginator is
     * active and the rows above measure the gate.
     *
     * <p>D-47 (security-14 S-14-01): the automatic retry is itself an unattended cause, so even
     * though this job carries no {@link BatchControlJobProperty} it must be activated first, or
     * the retry is refused (T-06-54 pins that side).
     */
    @Test
    public void t_06_35_retriesRunOnUncontrolledJob() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("nag-free"));
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(retryOnce());
        // D-47: an automatic retry is unattended regardless of who started the build it retries.
        BatchControlFixtures.activate(job);

        // a human first run (note 100): the retry is judged by the causes of the build it retries
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, ActivationFixtures.userCause("u1")));
        j.waitUntilNoActivity();
        assertEquals(2, job.getBuilds().size(), "fixture: naginator must retry a failed uncontrolled, activated job automatically");

        post(j, "u1", job.getBuildByNumber(2).getUrl() + "retry/");
        j.waitUntilNoActivity();
        assertEquals(3, job.getBuilds().size(), "fixture: naginator's manual Retry must queue a build of an uncontrolled job");
    }

    /**
     * T-06-54 (D-47, security-14 S-14-01 BLOCKER): the same automatic retry as T-06-35, but the
     * job is never activated. The retry is an unattended cause (D-47), so it is refused with the
     * matrix's blocking baseline and leaves a TRIGGER_BLOCKED record naming {@code activation}
     * as the blocking switch (note 101) — the marker-free path S-14-01 found bypassing this.
     */
    @Test
    public void t_06_54_automaticRetryWithoutActivationIsBlockedAndRecorded() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("nag-noact"));
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(retryOnce());
        // deliberately NOT activated (D-47): the automatic retry below must still be refused.

        // a human first run (note 100), same premise as T-06-35, on a job left not activated.
        // security-15 S-15-01: the submission, not only the Cause, must run while impersonating
        // u1, or the gate now (correctly) classifies it as unattended - which would refuse this
        // very first build too, since the job is uncontrolled and not activated.
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        j.waitUntilNoActivity();

        assertBlocked(j, job, 2, 1); // only the human build (#1) exists; no automatic retry followed it

        List<ChangeRecord> records = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, "nag-noact");
        assertEquals(1, records.size(), "the refused automatic retry must leave one TRIGGER_BLOCKED record: " + records);
        assertNotNull(records.get(0).getDetail(), "the record must carry a detail");
        assertTrue(records.get(0).getDetail().toLowerCase(Locale.ROOT).contains("activation"),
                "the record must name activation as what blocked the automatic retry (D-47): "
                        + records.get(0).getDetail());
    }

    /** Retry once, immediately, on any failure. */
    private static NaginatorPublisher retryOnce() {
        return new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0));
    }
}
