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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Owner scenario S-6 — "when the designated approver is away, can the request be handed to
 * another approver?". Matrix row T-OS-10.
 *
 * The hand-over itself is the requester-driven approver change and is already covered:
 * T-03-04 (the change is recorded with from/to/by/at), T-03-05 (no change after the decision)
 * and T-RT-07 (multi-hop changes audited, superseded approvers refused, only the final
 * approver decides). What no row covered is the boundary this scenario depends on: an approver
 * who is on the global approver list and holds BatchControl/Approve but was never designated on
 * this request must not be able to decide it. That is the current contract, and it is what makes
 * the hand-over meaningful.
 *
 * A pre-configured delegation line (a stand-in approver set up by an administrator in advance)
 * is SPEC item 15 (second release, "다단계 결재선"), so no test assumes it — see matrix note 33.
 *
 * Accounts: requester {@code u1}, designated approver {@code a1}, alternate approver {@code a2}
 * (the stand-in the requester hands over to), third approver {@code a3} (on the list, never
 * designated on this request), administrator {@code admin}.
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class OwnerScenarioApproverDelegationTest {

    private static final String REQUESTER = "u1";
    private static final String DESIGNATED_APPROVER = "a1";
    private static final String STAND_IN_APPROVER = "a2";
    private static final String UNRELATED_APPROVER = "a3";
    private static final String ADMIN = "admin";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(DESIGNATED_APPROVER, STAND_IN_APPROVER, UNRELATED_APPROVER));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList(
                DESIGNATED_APPROVER, STAND_IN_APPROVER, UNRELATED_APPROVER));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-OS-10 (owner scenario S-6): only the request's designated approver may decide it.
     * a3 and a2 hold BatchControl/Approve and are on the global approver list, but were not
     * designated on this request, so neither may approve or reject it. The fixture control at
     * the end is the hand-over itself: once the requester designates a2, a2's approval works —
     * so the refusals above are about designation, not about a broken account.
     */
    @Test
    public void t_os_10_onlyTheDesignatedApproverMayDecideTheRequest() throws Exception {
        RunRequest request;
        try (ACLContext ignored = as(REQUESTER)) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(),
                    "month-end batch, a1 is on call", DESIGNATED_APPROVER);
        }
        assertEquals(DESIGNATED_APPROVER, request.getApprover());

        assertRefused("a3 is on the approver list but was never designated on this request",
                () -> approveAs(UNRELATED_APPROVER, request.getId(), "helping out"));
        assertRefused("a3 must not be able to reject it either",
                () -> rejectAs(UNRELATED_APPROVER, request.getId(), "not my call, but rejecting"));
        assertRefused("a2 must not decide before the requester designates them",
                () -> approveAs(STAND_IN_APPROVER, request.getId(), "a1 is away, taking over"));

        RunRequest afterRefusals = RunRequestService.get().load(request.getId());
        assertEquals("the request must stay PENDING after every undesignated attempt",
                RequestStatus.PENDING, afterRefusals.getStatus());
        assertEquals("the designated approver must be unchanged",
                DESIGNATED_APPROVER, afterRefusals.getApprover());
        assertTrue("no approver change may be recorded from a refused decision",
                afterRefusals.getApproverChanges() == null
                        || afterRefusals.getApproverChanges().isEmpty());
        assertEquals("the queue must be empty", 0, j.jenkins.getQueue().getItems().length);
        j.waitUntilNoActivity();
        assertTrue("no build may run from an undesignated approver's decision",
                job.getBuilds().isEmpty());
        assertEquals(1, job.getNextBuildNumber());

        // fixture control: the hand-over the owner asked about does work (SPEC item 3)
        try (ACLContext ignored = as(REQUESTER)) {
            RunRequestService.get().changeApprover(request.getId(), STAND_IN_APPROVER);
        }
        List<RunRequest.ApproverChange> changes =
                RunRequestService.get().load(request.getId()).getApproverChanges();
        assertNotNull(changes);
        assertEquals("the hand-over must be recorded once", 1, changes.size());
        assertEquals(DESIGNATED_APPROVER, changes.get(0).getFrom());
        assertEquals(STAND_IN_APPROVER, changes.get(0).getTo());
        assertEquals(REQUESTER, changes.get(0).getBy());

        approveAs(STAND_IN_APPROVER, request.getId(), "covering for a1");
        RequestStatus decided = RunRequestService.get().load(request.getId()).getStatus();
        assertTrue("the newly designated approver's decision must go through",
                decided == RequestStatus.APPROVED || decided == RequestStatus.EXECUTED);
        j.waitUntilNoActivity();
        assertEquals("the approved run must execute exactly once", 1, job.getBuilds().size());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
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
