package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ActivationState;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.activationIds;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.getAs;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.recordsFor;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6a (#15, D-39), the ACTIVATION request: requested per job with
 * {@code BatchControl/Request} + {@code Item/Read}, a reason and one or more designated approvers
 * (D-37), decided like a run request (designated approver only, self-approval rules), approval
 * activates and records ACTIVATED, rejection changes nothing; HOLD is the symmetric request that
 * needs approval and records HELD; approving one request invalidates the job's other pending ones
 * (security-13). Matrix rows T-06a-16..26, T-06a-51.
 *
 * <p>Every row fixes a job whose switches are already cleared, so that the timer outcome is
 * decided by activation alone and the behavioural half of each row (timer refused / admitted)
 * measures the request's effect.
 *
 * <p>Written from docs/SPEC.md items 3, 5, 6a and 7, docs/DECISIONS.md D-29/D-37/D-39 and
 * docs/ARCHITECTURE.md "Activation store" only (no src/main knowledge).
 */
@WithJenkins
public class ActivationRequestTest {

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u3")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE, BatchControlPermissions.REQUEST)
                        .everywhere().to("a3")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer")
                .grant(Jenkins.READ, BatchControlPermissions.REQUEST).everywhere().to("blind")
                .grant(Jenkins.READ, Item.READ).everywhere().to("lost"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin", "lost"));
        cfg.save();

        job = j.createFreeStyleProject("act-x");
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);
        assertFalse(isActivated(job), "premise: a job created after the first start is not activated");
    }

    /**
     * T-06a-16 (P0): the whole ACTIVATE lane through the frozen web contract. The form answers
     * 200; the submission stores a PENDING request under {@code activation-requests/<id>.xml}
     * that does not yet activate anything; the designated approver's approval activates the job,
     * records who decided, writes exactly one ACTIVATED record, and the timer then runs.
     */
    @Test
    public void t_06a_16_designatedApproverApprovesAndTheJobIsActivated() throws Exception {
        WebResponse form = get(j, "u1", job.getUrl() + "batch-control/activation");
        assertEquals(200, form.getStatusCode(), "the requester must be able to open the activation form");
        assertTrue(form.getContentAsString().contains("name=\"reason\""), "the form must ask for a reason");

        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "nightly close goes live", "a1");
        ActivationRequest pending = ActivationService.get().load(id);
        assertEquals("act-x", pending.getJobFullName());
        assertEquals(ActivationRequest.Action.ACTIVATE, pending.getAction());
        assertEquals("nightly close goes live", pending.getReason());
        assertEquals("u1", pending.getRequester());
        assertEquals(List.of("a1"), pending.getApprovers());
        assertEquals(RequestStatus.PENDING, pending.getStatus());
        assertNull(pending.getDecidedBy());
        assertTrue(new File(j.jenkins.getRootDir(), "batch-control/activation-requests/" + id + ".xml").isFile(),
                "the request must be stored as batch-control/activation-requests/<id>.xml (ARCHITECTURE)");

        assertFalse(isActivated(job), "a PENDING request must not activate the job");
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "still refused while PENDING");
        assertBlocked(j, job, 1, 0);

        assertSuccess(decideActivation(j, "a1", id, "approve", "reviewed"), "a1's approval");
        ActivationRequest approved = ActivationService.get().load(id);
        assertEquals(RequestStatus.APPROVED, approved.getStatus());
        assertEquals("a1", approved.getDecidedBy());
        assertTrue(isActivated(job), "approval must mark the job activated");
        ActivationState state = ActivationService.get().getState(job);
        assertNotNull(state);
        assertTrue(state.isActivated());
        assertEquals("a1", state.getActivatedBy(), "activatedBy names the approver");
        assertEquals(id, state.getRequestId(), "the state names the request that authorised it");

        List<ChangeRecord> activated = recordsFor(ChangeType.ACTIVATED, "act-x");
        assertEquals(1, activated.size(), "exactly one ACTIVATED record: " + activated);

        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
    }

    /**
     * T-06a-17 (P0): rejection changes nothing. An empty rejection comment is refused (item 5);
     * a rejection with a reason closes the request REJECTED, the job stays not activated, no
     * ACTIVATED record exists and the timer is still refused.
     */
    @Test
    public void t_06a_17_rejectionChangesNothing() throws Exception {
        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");

        assertClientError(decideActivation(j, "a1", id, "reject", ""), "a rejection without a reason");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus());

        assertSuccess(decideActivation(j, "a1", id, "reject", "missing runbook"), "a1's rejection");
        ActivationRequest rejected = ActivationService.get().load(id);
        assertEquals(RequestStatus.REJECTED, rejected.getStatus());
        assertEquals("a1", rejected.getDecidedBy());
        assertFalse(isActivated(job), "a rejection must not activate the job");
        assertTrue(recordsFor(ChangeType.ACTIVATED, "act-x").isEmpty(), "no ACTIVATED record after a rejection");
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer is still refused");
        assertBlocked(j, job, 1, 0);

        assertClientError(decideActivation(j, "a1", id, "approve", "changed my mind"), "an approval of a closed request");
        assertFalse(isActivated(job), "a closed request cannot be approved afterwards");
    }

    /**
     * T-06a-18 (P0): self-approval ban (items 2/3, D-37). a3, a listed approver who may also
     * request, cannot designate themselves — alone or within a set — and nothing is stored. The
     * administrator exception (allowAdminSelfApproval, default true) holds: the administrator may
     * designate and approve themselves.
     */
    @Test
    public void t_06a_18_selfApprovalIsRefusedExceptForTheAdministrator() throws Exception {
        assertClientError(submitActivation(j, "a3", job, "ACTIVATE", "self", "a3"), "a3 designating a3");
        assertClientError(submitActivation(j, "a3", job, "ACTIVATE", "self in a set", "a1", "a3"),
                "a3 designating a set that contains a3");
        assertTrue(activationIds().isEmpty(), "a refused submission must store nothing");

        String id = submitActivationOk(j, "admin", job, "ACTIVATE", "admin brings it live", "admin");
        assertSuccess(decideActivation(j, "admin", id, "approve", "self-approved"), "the administrator's self-approval");
        assertTrue(isActivated(job), "the administrator exception must let the self-approval activate the job");
    }

    /**
     * T-06a-19 (P0): only a designated approver decides (D-29 applied to the set, D-37). With
     * {a1} designated, a listed approver outside the set (a2) and the administrator are refused
     * and the request stays PENDING; any member of a larger set may decide.
     */
    @Test
    public void t_06a_19_onlyADesignatedApproverMayDecide() throws Exception {
        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");

        assertClientError(decideActivation(j, "a2", id, "approve", "stepping in"), "approval by a2 (not designated)");
        assertClientError(decideActivation(j, "admin", id, "approve", "stepping in"), "approval by the administrator (not designated)");
        assertClientError(decideActivation(j, "a2", id, "reject", "stepping in"), "rejection by a2 (not designated)");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus());
        assertFalse(isActivated(job), "a refused decision must not activate the job");

        String setId = submitActivationOk(j, "u1", job, "ACTIVATE", "go live, either of you", "a1", "a2");
        assertSuccess(decideActivation(j, "a2", setId, "approve", "ok"), "approval by a2, a member of the set");
        assertEquals("a2", ActivationService.get().load(setId).getDecidedBy());
        assertTrue(isActivated(job));
    }

    /**
     * T-06a-20 (P0): approver eligibility (item 3). Designating a user who is not on the approver
     * list is refused; a listed approver who does not hold Approve at decision time ("lost") is
     * refused when deciding.
     */
    @Test
    public void t_06a_20_approverMustBeListedAndHoldApprove() throws Exception {
        assertClientError(submitActivation(j, "u1", job, "ACTIVATE", "go live", "u3"), "designating u3 (not listed)");
        assertTrue(activationIds().isEmpty(), "nothing may be stored");

        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "lost");
        assertClientError(decideActivation(j, "lost", id, "approve", "ok"), "approval by a listed user without Approve");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus());
        assertFalse(isActivated(job));
    }

    /**
     * T-06a-21 (P0, permissions): requesting needs BatchControl/Request plus Item/Read. A user
     * without Request ("viewer") gets 404 on the per-job form and submission (SPEC 2, #31: the
     * per-job action is absent), a user without Item/Read on the job ("blind") gets 404, an
     * approver cannot request without Request (403/404); nothing is stored in any case.
     */
    @Test
    public void t_06a_21_requestingNeedsRequestAndItemRead() throws Exception {
        assertEquals(404, get(j, "viewer", job.getUrl() + "batch-control/activation").getStatusCode(),
                "the activation form must be absent for a user without Request");
        assertEquals(404, submitActivation(j, "viewer", job, "ACTIVATE", "x", "a1").getStatusCode(),
                "the submission must be absent for a user without Request");
        int blind = submitActivation(j, "blind", job, "ACTIVATE", "x", "a1").getStatusCode();
        assertEquals(404, blind, "a user who cannot read the job must not reach its activation URL");
        int approver = submitActivation(j, "a1", job, "ACTIVATE", "x", "a2").getStatusCode();
        assertTrue(approver == 403 || approver == 404, "an approver without Request must be refused, got " + approver);
        assertTrue(activationIds().isEmpty(), "no refused submission may store a request");

        assertSuccess(submitActivation(j, "u1", job, "ACTIVATE", "x", "a1"), "guard: u1 may request");
    }

    /**
     * T-06a-22 (P0, SPEC 6 security): state changes are POST only. A GET on the submission and on
     * the decision URLs changes nothing (HTTP 4xx, typically 405).
     */
    @Test
    public void t_06a_22_getOnStateChangingUrlsChangesNothing() throws Exception {
        int submit = getAs(j, "u1", job.getUrl() + "batch-control/activation/submit?action=ACTIVATE&reason=x&approvers=a1")
                .getStatusCode();
        assertTrue(submit >= 400 && submit < 500, "GET submit must be refused, got " + submit);
        assertTrue(activationIds().isEmpty(), "a GET must not store a request");

        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        for (String verb : new String[] {"approve", "reject", "cancel"}) {
            String who = verb.equals("cancel") ? "u1" : "a1";
            int code = getAs(j, who, "batch-control/activations/" + id + "/" + verb + "?comment=x").getStatusCode();
            assertTrue(code >= 400 && code < 500, "GET " + verb + " must be refused, got " + code);
        }
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus());
        assertFalse(isActivated(job));
    }

    /**
     * T-06a-23 (P0): HOLD is a request that needs approval. While the HOLD is PENDING the job
     * stays activated and runs; once approved the job is not activated, one HELD record exists,
     * the state names who put it on hold, and the timer is refused.
     */
    @Test
    public void t_06a_23_approvedHoldDeactivatesAndRecordsHeld() throws Exception {
        activate(job, "u1", "a1");
        String id = submitActivationOk(j, "u1", job, "HOLD", "vendor outage", "a2");
        assertEquals(ActivationRequest.Action.HOLD, ActivationService.get().load(id).getAction());
        assertTrue(isActivated(job), "a PENDING hold must not stop the job");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

        assertSuccess(decideActivation(j, "a2", id, "approve", "hold it"), "a2's approval of the hold");
        assertFalse(isActivated(job), "an approved hold marks the job not activated");
        ActivationState state = ActivationService.get().getState(job);
        assertNotNull(state);
        assertFalse(state.isActivated());
        assertEquals("a2", state.getDeactivatedBy());
        assertEquals(1, recordsFor(ChangeType.HELD, "act-x").size(), "exactly one HELD record");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "a held job's timer must be refused");
        assertBlocked(j, job, 2, 1);
    }

    /** T-06a-24 (P0, negative twin of 23): a rejected hold leaves the job activated and running. */
    @Test
    public void t_06a_24_rejectedHoldChangesNothing() throws Exception {
        activate(job, "u1", "a1");
        String id = submitActivationOk(j, "u1", job, "HOLD", "maybe hold", "a1");
        assertSuccess(decideActivation(j, "a1", id, "reject", "keep it running"), "a1's rejection");
        assertEquals(RequestStatus.REJECTED, ActivationService.get().load(id).getStatus());
        assertTrue(isActivated(job), "a rejected hold must leave the job activated");
        assertTrue(recordsFor(ChangeType.HELD, "act-x").isEmpty(), "no HELD record after a rejection");
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
    }

    /**
     * T-06a-25 (P1, validation): a request without a reason, without an approver, or with an
     * action outside ACTIVATE|HOLD is refused and stores nothing.
     */
    @Test
    public void t_06a_25_invalidSubmissionsAreRefused() throws Exception {
        assertClientError(submitActivation(j, "u1", job, "ACTIVATE", "", "a1"), "an empty reason");
        assertClientError(submitActivation(j, "u1", job, "ACTIVATE", "go live"), "no approver");
        assertClientError(submitActivation(j, "u1", job, "DELETE", "go live", "a1"), "an unknown action");
        assertTrue(activationIds().isEmpty(), "no invalid submission may store a request");
        assertSuccess(submitActivation(j, "u1", job, "ACTIVATE", "go live", "a1"), "guard: a valid submission");
    }

    /**
     * T-06a-26 (P1, item 7 applied): the requester may cancel a PENDING request; another user may
     * not; a cancelled request cannot be approved and activates nothing.
     */
    @Test
    public void t_06a_26_requesterCancelsPendingRequest() throws Exception {
        String id = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        assertClientError(decideActivation(j, "u3", id, "cancel", ""), "a cancel by u3 (not the requester)");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus());

        assertSuccess(decideActivation(j, "u1", id, "cancel", ""), "the requester's cancel");
        assertEquals(RequestStatus.CANCELLED, ActivationService.get().load(id).getStatus());
        assertClientError(decideActivation(j, "a1", id, "approve", "ok"), "an approval of a cancelled request");
        assertFalse(isActivated(job));
        assertTrue(recordsFor(ChangeType.ACTIVATED, "act-x").isEmpty());
    }

    /**
     * T-06a-51 (P0, S-13-07, SPEC 6a): approving one request invalidates the job's other pending
     * activation requests, so a stale ACTIVATE can never undo an approved HOLD. Two ACTIVATE
     * requests are pending (a1 and a2 designated); a1 approves the first, which invalidates the
     * second; a HOLD is then approved; the stale ACTIVATE's approval is refused and the job stays
     * held, its timer refused.
     */
    @Test
    public void t_06a_51_stalePendingActivateCannotUndoAnApprovedHold() throws Exception {
        String first = submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");
        String second = submitActivationOk(j, "u1", job, "ACTIVATE", "go live (again)", "a2");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(second).getStatus(), "premise: two pending ACTIVATE requests");

        assertSuccess(decideActivation(j, "a1", first, "approve", "ok"), "a1's approval of the first");
        assertTrue(isActivated(job), "premise: the first approval activates the job");
        assertEquals(RequestStatus.INVALIDATED, ActivationService.get().load(second).getStatus(),
                "approving one request must invalidate the job's other pending activation request");

        String hold = submitActivationOk(j, "u1", job, "HOLD", "vendor outage", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "hold it"), "a1's approval of the hold");
        assertFalse(isActivated(job), "premise: the approved hold stops the job");

        assertClientError(decideActivation(j, "a2", second, "approve", "late"), "the approval of the stale ACTIVATE");
        assertEquals(RequestStatus.INVALIDATED, ActivationService.get().load(second).getStatus());
        assertFalse(isActivated(job), "a stale ACTIVATE must never undo an approved HOLD");
        assertEquals(1, recordsFor(ChangeType.ACTIVATED, "act-x").size(), "only the first approval activated the job");
        assertEquals(1, recordsFor(ChangeType.HELD, "act-x").size());
        int next = job.getNextBuildNumber();
        int builds = job.getBuilds().size();
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the held job's timer must be refused");
        assertBlocked(j, job, next, builds);
    }
}
