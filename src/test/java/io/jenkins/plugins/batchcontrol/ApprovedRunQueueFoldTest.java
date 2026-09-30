package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SPEC 4/5 (an approved request runs once, as its own build, and becomes EXECUTED with it) and
 * D-23 (the marker authorises exactly one queue entry), security-26 S-26-01: an approved
 * submission must not be folded into another queue item of the same job. Matrix row T-06-88
 * (note 174).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/reports/security-26.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ApprovedRunQueueFoldTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-88 (S-26-01): an activated, approval-required, parameterless job whose timer is allowed
     * has a timer build waiting in the queue (no executors). u1's request is approved while it
     * waits. The queue then holds two items of the job; once executors return, two builds run,
     * the request is EXECUTED with the build that carries its ApprovedCause, and the timer build
     * carries no ApprovedCause.
     */
    @Test
    public void t_06_88_approvedRunIsNotFoldedIntoAWaitingItem() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("fold-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);

        j.jenkins.setNumExecutors(0);
        assertNotNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: the timer build must be queued");
        assertEquals(1, items(job), "fixture: the timer build waits in the queue");

        RunRequest request = requestAndApprove(job);
        assertEquals(2, items(job), "the approved submission must be its own queue item, not folded into the waiting"
                + " timer build: " + Arrays.stream(j.jenkins.getQueue().getItems()).map(Queue.Item::toString)
                        .collect(Collectors.joining(", ")));

        j.jenkins.setNumExecutors(2);
        j.waitUntilNoActivity();
        assertEquals(2, job.getBuilds().size(), "two builds must have run");
        RunRequest after = RunRequestService.get().load(request.getId());
        assertEquals(RequestStatus.EXECUTED, after.getStatus(), "the request must become EXECUTED");
        FreeStyleBuild approved = null;
        FreeStyleBuild timer = null;
        for (FreeStyleBuild build : job.getBuilds()) {
            if (build.getCause(ApprovedCause.class) != null) {
                approved = build;
            } else {
                timer = build;
            }
        }
        assertNotNull(approved, "one build must carry the ApprovedCause");
        assertNotNull(timer, "one build must be the timer build without an ApprovedCause");
        assertNull(timer.getCause(ApprovedCause.class), "the timer build carries no ApprovedCause");
        assertEquals("fold-x#" + approved.getNumber(), after.getExecutedRunId(),
                "the request must name the build that carries its ApprovedCause");
    }

    private int items(FreeStyleProject job) {
        return (int) Arrays.stream(j.jenkins.getQueue().getItems()).filter(i -> i.task == job).count();
    }
}
