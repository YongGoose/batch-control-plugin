package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.store.BatchClock;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39), what people see: the job page shows whether the job is activated or
 * on hold and links to the activation request form; pending activation and hold requests appear
 * in the approval inbox; the history shows ACTIVATED/HELD records; activation requests notify
 * like run requests (D-36) and name the action (security-13). Matrix rows T-06a-38..41, T-06a-52.
 *
 * <p>Screen contract used (note 96): the state is recognised by wording, case-insensitively —
 * "not activated" or "on hold" for a job that is not in service, "activated" without either of
 * those for one that is — and the request link by its target {@code batch-control-activation/}.
 * Both directions are asserted on the same job so a notice that is always shown cannot pass.
 *
 * <p>Written from docs/SPEC.md items 6a and 13 and docs/DECISIONS.md D-36/D-39 only (no src/main
 * knowledge).
 */
@WithJenkins
public class ActivationScreenTest {

    private JenkinsRule j;

    private FreeStyleProject job;

    /** Records every notification for the rows of this class. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ).everywhere().to("reader"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3"));
        cfg.save();

        job = j.createFreeStyleProject("screen-x");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);
    }

    @AfterEach
    public void tearDown() {
        NotificationCapture.clear();
    }

    /**
     * T-06a-38 (P0): the job page states the activation state and links to the request form, in
     * all three states: never activated, activated, and put on hold.
     */
    @Test
    public void t_06a_38_jobPageShowsStateAndRequestLink() throws Exception {
        assertNotInService(jobPage(), "a job that was never activated");

        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", id, "approve", "ok"), "fixture: approval");
        String activated = jobPage();
        String lower = activated.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("activated"), "the activated job's page must say it is activated");
        assertFalse(lower.contains("not activated"), "the activated job's page must not say 'not activated'");
        assertFalse(lower.contains("on hold"), "the activated job's page must not say 'on hold'");
        assertTrue(activated.contains("batch-control-activation"), "the page must link to the activation form");

        String hold = submitActivationOk(j, "u1", job, "HOLD", "pause", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "fixture: hold approval");
        assertNotInService(jobPage(), "a job put on hold");
    }

    /**
     * T-06a-39 (P1): the approval inbox lists pending activation and hold requests to the
     * designated approver; a user without any Batch Control permission gets 404 (SPEC 2).
     */
    @Test
    public void t_06a_39_inboxListsPendingActivationAndHoldRequests() throws Exception {
        String activate = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        FreeStyleProject other = j.createFreeStyleProject("screen-y");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(other, cleared);
        BatchControlFixtures.activate(other, "u1", "a2");
        String hold = submitActivationOk(j, "u1", other, "HOLD", "pause", "a1");

        WebResponse inbox = get(j, "a1", "batch-control/activations/");
        assertEquals(200, inbox.getStatusCode());
        String body = inbox.getContentAsString();
        assertTrue(body.contains(activate) && body.contains("screen-x"), "the inbox must list the pending ACTIVATE request");
        assertTrue(body.contains(hold) && body.contains("screen-y"), "the inbox must list the pending HOLD request");

        assertEquals(404, get(j, "reader", "batch-control/activations/").getStatusCode(),
                "a user without Batch Control permissions must get 404");
    }

    /** T-06a-40 (P1): the history change list and changes.csv show ACTIVATED and HELD. */
    @Test
    public void t_06a_40_historyShowsActivatedAndHeldRecords() throws Exception {
        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", id, "approve", "ok"), "fixture: approval");
        String hold = submitActivationOk(j, "u1", job, "HOLD", "pause", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "fixture: hold approval");

        LocalDate today = LocalDate.now(BatchClock.clock());
        String period = "from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
        WebResponse history = get(j, "viewer", "batch-control/history/?kind=changes&" + period);
        assertEquals(200, history.getStatusCode());
        String page = history.getContentAsString();
        assertTrue(page.contains("ACTIVATED"), "the change history must show the ACTIVATED record");
        assertTrue(page.contains("HELD"), "the change history must show the HELD record");

        WebResponse csv = get(j, "viewer", "batch-control/history/changes.csv?" + period);
        assertEquals(200, csv.getStatusCode());
        boolean activatedRow = false;
        boolean heldRow = false;
        for (String line : csv.getContentAsString().split("\r?\n")) {
            activatedRow |= line.contains("ACTIVATED") && line.contains("screen-x");
            heldRow |= line.contains("HELD") && line.contains("screen-x");
        }
        assertTrue(activatedRow, "changes.csv must export the ACTIVATED record for the job");
        assertTrue(heldRow, "changes.csv must export the HELD record for the job");
    }

    /**
     * T-06a-41 (P1, D-36): an activation request notifies the designated approvers (kind
     * ACTIVATION, the job as subject, a link to the request); the decision notifies the
     * requester; a refused decision notifies nobody.
     */
    @Test
    public void t_06a_41_activationRequestsNotifyLikeRunRequests() throws Exception {
        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live tonight", "a1", "a2");
        List<NotificationCapture> created = NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        assertEquals(1, created.size(), "exactly one REQUEST_CREATED: " + created);
        NotificationCapture n = created.get(0);
        assertEquals("ACTIVATION", n.kind);
        assertEquals("screen-x", n.subject);
        assertEquals("u1", n.requester);
        assertEquals("go live tonight", n.reason);
        assertEquals(Set.of("a1", "a2"), new HashSet<>(n.recipients), "REQUEST_CREATED goes to the designated approvers");
        assertNotNull(n.url);
        assertTrue(n.url.contains("batch-control/activations/" + id), "the link must lead to the request, was " + n.url);

        assertClientError(decideActivation(j, "a3", id, "approve", "stepping in"), "approval by a3 outside the set");
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVED, id).isEmpty(),
                "a refused approval must not notify");

        assertSuccess(decideActivation(j, "a2", id, "approve", "ok"), "a2's approval");
        List<NotificationCapture> approved = NotificationCapture.await(NotificationEvent.APPROVED, id);
        assertEquals(1, approved.size());
        assertEquals(List.of("u1"), approved.get(0).recipients, "APPROVED goes to the requester");

        String hold = submitActivationOk(j, "u1", job, "HOLD", "pause", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "reject", "keep running"), "a1's rejection");
        List<NotificationCapture> rejected = NotificationCapture.await(NotificationEvent.REJECTED, hold);
        assertEquals(1, rejected.size());
        assertEquals(List.of("u1"), rejected.get(0).recipients, "REJECTED goes to the requester");
    }

    /**
     * T-06a-52 (P1, S-13-08, SPEC 6a "Notifications name the action"): the REQUEST_CREATED
     * notification of a HOLD request says HOLD, and that of an ACTIVATE request says ACTIVATE
     * (the twin, so a constant cannot pass). The reasons are chosen not to contain either word.
     */
    @Test
    public void t_06a_52_notificationsNameTheAction() throws Exception {
        String activate = submitActivationOk(j, "u1", job, "ACTIVATE", "new nightly", "a1");
        NotificationCapture created = NotificationCapture.await(NotificationEvent.REQUEST_CREATED, activate).get(0);
        assertEquals("ACTIVATE", created.action, "an ACTIVATE request notification must name ACTIVATE: " + created);
        assertSuccess(decideActivation(j, "a1", activate, "approve", "ok"), "a1's approval");

        String hold = submitActivationOk(j, "u1", job, "HOLD", "vendor outage", "a1");
        NotificationCapture held = NotificationCapture.await(NotificationEvent.REQUEST_CREATED, hold).get(0);
        assertEquals("HOLD", held.action, "a HOLD request notification must name HOLD: " + held);
        assertEquals("ACTIVATION", held.kind, "the kind stays ACTIVATION (T-06a-41)");
    }

    // ---------------------------------------------------------------- helpers

    private String jobPage() throws Exception {
        WebResponse page = get(j, "u1", job.getUrl());
        assertEquals(200, page.getStatusCode());
        return page.getContentAsString();
    }

    private static void assertNotInService(String page, String what) {
        String lower = page.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("not activated") || lower.contains("on hold"),
                "the page of " + what + " must say it is not activated or on hold");
        assertTrue(page.contains("batch-control-activation"), "the page of " + what + " must link to the activation form");
    }
}
