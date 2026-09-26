package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner scenario S-1 — "what happens when a request is rejected".
 * Matrix rows T-OS-01 (rejection is terminal, the reason is stored and no build ever runs),
 * T-OS-02 (a rejected request can never be revived by anyone) and
 * T-OS-03 (the requester can read the rejection reason; the screen offers no approve path).
 *
 * Several distinct accounts are configured, as the owner asked, and every assertion names
 * the actor: requester {@code u1}, designated approver {@code a1}, alternate approver
 * {@code a2} (on the approver list but never designated on this request), MANAGE holder
 * {@code m1}, administrator {@code admin} and permission-less outsider {@code u9}.
 *
 * SPEC basis: item 5 ("반려 시에는 사유가 필수"), item 4 (append-only history) and the
 * state machine in section 4, where REJECTED has no outgoing transition.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class OwnerScenarioRejectionTest {

    private static final String REQUESTER = "u1";
    private static final String APPROVER = "a1";
    private static final String ALTERNATE_APPROVER = "a2";
    private static final String MANAGER = "m1";
    private static final String ADMIN = "admin";
    private static final String OUTSIDER = "u9";

    private static final String REASON = "month-end batch, closing figures";
    private static final String REJECTION_REASON = "rejected: month-end freeze, resubmit after 2026-10-02";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(APPROVER, ALTERNATE_APPROVER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE)
                        .everywhere().to(MANAGER)
                .grant(Jenkins.READ, Item.READ).everywhere().to(OUTSIDER));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList(APPROVER, ALTERNATE_APPROVER, ADMIN));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-OS-01 (owner scenario S-1): the designated approver a1 rejects u1's request.
     * The request ends REJECTED with the mandatory reason and decision time stored, no
     * build is ever queued or started, and the decision stays readable in the history.
     */
    @Test
    public void t_os_01_rejectionIsTerminalAndNoBuildEverRuns() throws Exception {
        RunRequest request = createAs(REQUESTER, APPROVER);
        assertEquals(RequestStatus.PENDING, request.getStatus());

        rejectAs(APPROVER, request.getId(), REJECTION_REASON);

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("the rejected request must end in REJECTED",
                RequestStatus.REJECTED, reloaded.getStatus());
        assertEquals("the mandatory rejection reason must be stored verbatim",
                REJECTION_REASON, reloaded.getDecisionComment());
        assertNotNull("the rejection must carry a decision timestamp", reloaded.getDecidedAt());
        assertEquals("the deciding approver must stay recorded on the request",
                APPROVER, reloaded.getApprover());
        assertEquals("the requester must stay recorded on the request",
                REQUESTER, reloaded.getRequester());
        assertNull("a rejected request must never carry an executed run",
                reloaded.getExecutedRunId());

        assertNoBuildEverRan();

        // the decision survives in the (append-only) request history, not just in memory
        assertTrue("the rejected request must remain listed in the request history",
                RunRequestService.get().list().stream()
                        .anyMatch(r -> request.getId().equals(r.getId())
                                && r.getStatus() == RequestStatus.REJECTED));
    }

    /**
     * T-OS-02 (owner scenario S-1): a rejected request cannot be revived. Neither the
     * designated approver a1, nor another approver a2 from the list, nor the administrator
     * can approve it afterwards, and the requester cannot re-route it to a new approver.
     */
    @Test
    public void t_os_02_rejectedRequestCanNeverBeApprovedAgain() throws Exception {
        RunRequest request = createAs(REQUESTER, APPROVER);
        rejectAs(APPROVER, request.getId(), REJECTION_REASON);
        assertEquals(RequestStatus.REJECTED,
                RunRequestService.get().load(request.getId()).getStatus());

        assertRefused("the designated approver a1 must not be able to approve after rejecting",
                () -> approveAs(APPROVER, request.getId(), "changed my mind"));
        assertRefused("another approver a2 must not be able to approve a rejected request",
                () -> approveAs(ALTERNATE_APPROVER, request.getId(), "approving on a1's behalf"));
        assertRefused("not even the administrator may revive a rejected request",
                () -> approveAs(ADMIN, request.getId(), "escalated by phone"));
        assertRefused("the MANAGE holder m1 must not be able to approve a rejected request",
                () -> approveAs(MANAGER, request.getId(), "unblocking operations"));
        assertRefused("a user without BatchControl/Approve must not be able to approve it either",
                () -> approveAs(OUTSIDER, request.getId(), "just clicking around"));
        assertRefused("a rejected request must not be rejected a second time either",
                () -> rejectAs(APPROVER, request.getId(), "still no"));
        assertRefused("the requester must not be able to re-route a rejected request to a new approver",
                () -> changeApproverAs(REQUESTER, request.getId(), ALTERNATE_APPROVER));

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("the status must stay REJECTED through every revival attempt",
                RequestStatus.REJECTED, reloaded.getStatus());
        assertEquals("the original rejection reason must not be overwritten",
                REJECTION_REASON, reloaded.getDecisionComment());
        assertEquals("the recorded approver must not change",
                APPROVER, reloaded.getApprover());
        assertNoBuildEverRan();
    }

    /**
     * T-OS-03 (owner scenario S-1): the requester u1 can read why the request was rejected,
     * and the screen offers no way to resume it (no approve action for this request).
     * The literal "final" wording is not pinned by SPEC, so the terminal state is asserted
     * as the rendered REJECTED status plus the absence of an approve path (matrix note 34).
     */
    @Test
    public void t_os_03_requesterSeesReasonAndScreenOffersNoApprovePath() throws Exception {
        RunRequest request = createAs(REQUESTER, APPROVER);
        rejectAs(APPROVER, request.getId(), REJECTION_REASON);

        HtmlPage detail = detailPageAs(REQUESTER, request.getId());
        String html = detail.getWebResponse().getContentAsString();

        assertTrue("the requester must see the rejection reason on the request detail screen",
                html.contains(REJECTION_REASON));
        assertTrue("the requester must see that the request is REJECTED",
                html.contains("REJECTED"));
        assertFalse("a rejected request must not offer an approve action any more",
                html.contains(request.getId() + "/approve"));

        // the deciding approver sees the same terminal record
        String approverView = detailPageAs(APPROVER, request.getId())
                .getWebResponse().getContentAsString();
        assertTrue("the approver's view must show the stored rejection reason too",
                approverView.contains(REJECTION_REASON));
        assertTrue(approverView.contains("REJECTED"));
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createAs(String userId, String approver) {
        Map<String, String> parameters = new LinkedHashMap<>();
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get().create(job, parameters, REASON, approver);
        }
    }

    private void approveAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, comment);
        }
    }

    private void rejectAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().reject(requestId, comment);
        }
    }

    private void changeApproverAs(String userId, String requestId, String newApprover) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().changeApprover(requestId, newApprover);
        }
    }

    private HtmlPage detailPageAs(String userId, String requestId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient()
                .withThrowExceptionOnFailingStatusCode(false)
                .login(userId);
        HtmlPage page = wc.getPage(new URL(j.getURL(), "batch-control/requests/" + requestId + "/"));
        assertEquals(userId + " must be able to read this request's detail screen",
                200, page.getWebResponse().getStatusCode());
        return page;
    }

    /** Matrix blocking baseline: empty queue, unmoved build number, no build at all. */
    private void assertNoBuildEverRan() throws Exception {
        assertEquals("the queue must be empty", 0, j.jenkins.getQueue().getItems().length);
        j.waitUntilNoActivity();
        assertEquals("nextBuildNumber must not move for a rejected request",
                1, job.getNextBuildNumber());
        assertTrue("a rejected request must never start a build", job.getBuilds().isEmpty());
    }

    /** Authorization/state refusals: the exact runtime exception type is not pinned by SPEC. */
    private static void assertRefused(String message, ThrowingRunnable action) {
        boolean refused = false;
        try {
            action.run();
        } catch (RuntimeException expected) { // IllegalArgumentException, hudson.model.Failure, ...
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(message, refused);
    }
}
