package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt B, R2-05 (matrix rows T-05-142, T-05-143, note 298): once the queued run of an approved
 * request is cancelled, the request page no longer says the run starts shortly; it says the queued
 * run was cancelled and will not start.
 *
 * <p>Basis: SPEC 5 ("when the approved run's queue item is cancelled (the request stays APPROVED and
 * is never submitted again)", D-74), D-55 (a request approved before the job was disabled), SPEC 6
 * usability (no silent failure) and the frozen bug-hunt contract: once the approved run's queue item
 * is cancelled (a Queue cancel, or the job disabled per D-55), the request page does not contain
 * "starts shortly" and contains "cancelled". Reproduced on a real Jenkins: the page kept saying the
 * run starts shortly.
 *
 * <p>The job is restricted to the label {@code bh-absent}, which no node carries, so the approved run
 * waits in the queue. Users: {@code u1} requester, {@code a1} approver. The phrases are compared
 * without regard to letter case on the page's normalised text.
 *
 * <p>Written from docs/SPEC.md items 5 and 6, DECISIONS D-55 and D-74 and the bug-hunt B contract
 * only (no src/main knowledge).
 */
@WithJenkins
public class ApprovedRunCancelledNoticeTest {

    private static final String ABSENT = "bh-absent";
    private static final String STARTS_SHORTLY = "starts shortly";
    private static final String CANCELLED = "cancelled";

    private JenkinsRule j;
    private FreeStyleProject job;
    private String id;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        j.jenkins.setLabelString("");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(List.of("a1"));
        cfg.save();

        job = j.createFreeStyleProject("queued-x");
        job.setAssignedLabel(j.jenkins.getLabel(ABSENT));
        setBatchControl(job, new BatchControlJobProperty(true));
        id = submitRunOk(j, "u1", job, "nightly batch", "a1");
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "fixture: a1 approves");
        assertTrue(waitFor(() -> queued().size() == 1, 10_000), "fixture: the approved run must wait in the queue, queued " + queued());
    }

    /**
     * T-05-142 (R2-05, Queue cancel): the approved run waiting in the queue is cancelled through the
     * queue (as an administrator would from the build queue). The request stays APPROVED (SPEC 5) and
     * its page, as u1 sees it, does not say "starts shortly" and does say "cancelled". Guard first:
     * while the run waits, the page says "starts shortly" and not "cancelled".
     */
    @Test
    public void t_05_142_queueCancelledApprovedRunIsNotShownAsStartingShortly() throws Exception {
        assertWaitingPage();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Queue.Item item : queued()) {
                assertTrue(j.jenkins.getQueue().cancel(item), "fixture: the queue item must be cancelled");
            }
        }
        assertTrue(waitFor(() -> queued().isEmpty(), 5_000), "fixture: the queue holds no item of the job, left " + queued());
        assertCancelledPage("after the queue item was cancelled");
    }

    /**
     * T-05-143 (R2-05, job disabled, D-55): the job is disabled while its approved run waits, which
     * removes the queued run. The request stays APPROVED and its page does not say "starts shortly"
     * and does say "cancelled". Guard first as in T-05-142.
     */
    @Test
    public void t_05_143_disabledJobApprovedRunIsNotShownAsStartingShortly() throws Exception {
        assertWaitingPage();
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            job.disable();
        }
        assertTrue(waitFor(() -> queued().isEmpty(), 5_000), "premise: disabling the job removes its queued run, left " + queued());
        assertCancelledPage("after the job was disabled");
    }

    // ---------------------------------------------------------------- helpers

    private void assertWaitingPage() throws Exception {
        String text = pageText();
        assertTrue(text.contains(STARTS_SHORTLY), "guard: while the approved run waits in the queue the request page says it"
                + " starts shortly: " + UsabilityFixtures.excerpt(text));
        assertFalse(text.contains(CANCELLED), "guard: while the approved run waits the request page does not say cancelled: "
                + UsabilityFixtures.excerpt(text));
    }

    private void assertCancelledPage(String when) throws Exception {
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus(),
                "premise: the request stays APPROVED " + when + " (SPEC 5)");
        String text = pageText();
        assertAll("the request page " + when,
                () -> assertFalse(text.contains(STARTS_SHORTLY), "the request page must not say the run starts shortly " + when
                        + ": " + UsabilityFixtures.excerpt(text)),
                () -> assertTrue(text.contains(CANCELLED), "the request page must say the queued run was cancelled " + when
                        + ": " + UsabilityFixtures.excerpt(text)));
        j.jenkins.setLabelString(ABSENT);
        j.jenkins.getQueue().scheduleMaintenance();
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "guard: the cancelled run never starts " + when);
    }

    private String pageText() throws Exception {
        return UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/").asNormalizedText()
                .toLowerCase(Locale.ROOT);
    }

    private List<Queue.Item> queued() {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Arrays.stream(j.jenkins.getQueue().getItems()).filter(i -> i.task == job).collect(Collectors.toList());
        }
    }

    /** Polls {@code condition} every 100 ms for up to {@code millis}; true as soon as it holds. */
    private static boolean waitFor(BooleanSupplier condition, long millis) throws InterruptedException {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(100);
        }
    }
}
