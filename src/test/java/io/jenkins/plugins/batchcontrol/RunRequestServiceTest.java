package io.jenkins.plugins.batchcontrol;

import hudson.model.Failure;
import hudson.model.FreeStyleBuild;
import hudson.model.Item;
import hudson.model.FreeStyleProject;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC items 2 (admin self-approval), 3 (approver designation) and 5 (run request and decision),
 * exercised at the {@code RunRequestService} level.
 * Matrix rows T-02-03, T-02-04, T-03-01..06, T-05-01, T-05-02, T-05-04, T-05-05, T-05-06
 * and T-RT-07 (multi-hop approver-change audit trail).
 * (T-05-03 lives in RunRequestWebTest because it is an HTTP-surface row.)
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class RunRequestServiceTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;
    private BatchControlJobProperty property;
    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "admin"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("DATE", "2000-01-01")));
        // run control is already on, so D-31 attached a property when the job was created:
        // install this one as the job's only one, otherwise the rows that tighten it later
        // (T-05-06 sets jobApprovers on it) would mutate a property nobody reads.
        property = setBatchControl(job, new BatchControlJobProperty(true));
    }

    // ---------------------------------------------------------------- SPEC 3

    /** T-03-01: designating an approver who is not in the global approver list rejects creation. */
    @Test
    public void t_03_01_approverNotInGlobalListIsRejected() throws Exception {
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        assertRejectedAsInvalid("an approver outside the global list must reject request creation",
                () -> createAs("u1", "u2", "month-end batch", params("DATE", "2026-09-01")));
        assertTrue("no request may be stored after a rejected creation",
                RunRequestService.get().list().isEmpty());
    }

    /** T-03-02: a non-admin requester cannot designate themselves as approver. */
    @Test
    public void t_03_02_selfDesignationRejectedForNonAdmin() throws Exception {
        // u1 is on the approver list, so the only reason to reject is self-designation
        cfg.setApprovers(Arrays.asList("a1", "u1"));
        cfg.save();

        assertRejectedAsInvalid("a non-admin requester must not be able to pick themselves as approver",
                () -> createAs("u1", "u1", "month-end batch", params("DATE", "2026-09-01")));
        assertTrue(RunRequestService.get().list().isEmpty());
    }

    /** T-03-03: an approver who lost the Approve permission after designation is refused at decision time. */
    @Test
    public void t_03_03_approverWhoLostApprovePermissionIsRefused() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));
        assertEquals(RequestStatus.PENDING, request.getStatus());

        // a1 loses BatchControl/Approve after the request was created
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ).everywhere().to("a1"));

        assertRefused("list membership alone is not enough; the approver must still hold Approve",
                () -> approveAs("a1", request.getId(), "trying anyway"));

        assertEquals("the request must stay PENDING after the refused decision",
                RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus());
        j.waitUntilNoActivity();
        assertTrue("no build may run from a refused decision", job.getBuilds().isEmpty());
    }

    /** T-03-04: the requester changes the approver before the decision; the change is recorded. */
    @Test
    public void t_03_04_changeApproverRecordsHistory() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));

        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().changeApprover(request.getId(), "a2");
        }

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("a2", reloaded.getApprover());
        List<RunRequest.ApproverChange> changes = reloaded.getApproverChanges();
        assertEquals("exactly one approver change must be recorded", 1, changes.size());
        assertEquals("a1", changes.get(0).getFrom());
        assertEquals("a2", changes.get(0).getTo());
        assertEquals("u1", changes.get(0).getBy());
        assertNotNull("the change must carry a timestamp", changes.get(0).getAt());
    }

    /** T-03-05: once the request is decided (APPROVED) the approver can no longer be changed. */
    @Test
    public void t_03_05_changeApproverAfterDecisionRejected() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));
        approveAs("a1", request.getId(), "ok");

        assertRefused("the approver can only be changed while the request is PENDING",
                () -> {
                    try (ACLContext ignored = as("u1")) {
                        RunRequestService.get().changeApprover(request.getId(), "a2");
                    }
                });
        assertEquals("the designated approver must stay unchanged",
                "a1", RunRequestService.get().load(request.getId()).getApprover());
    }

    /**
     * T-RT-07: multi-hop approver changes (a1 -> a2 -> a3) are fully audited in
     * approverChanges, superseded approvers can no longer decide, and only the final
     * approver's decision is valid.
     */
    @Test
    public void t_rt_07_multiHopApproverChangesAuditedAndOnlyFinalApproverDecides() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));

        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().changeApprover(request.getId(), "a2");
            RunRequestService.get().changeApprover(request.getId(), "a3");
        }

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("a3", reloaded.getApprover());
        List<RunRequest.ApproverChange> changes = reloaded.getApproverChanges();
        assertEquals("every hop must be audited, none may be collapsed or dropped", 2, changes.size());
        assertEquals("a1", changes.get(0).getFrom());
        assertEquals("a2", changes.get(0).getTo());
        assertEquals("u1", changes.get(0).getBy());
        assertNotNull(changes.get(0).getAt());
        assertEquals("a2", changes.get(1).getFrom());
        assertEquals("a3", changes.get(1).getTo());
        assertEquals("u1", changes.get(1).getBy());
        assertNotNull(changes.get(1).getAt());

        // superseded approvers must not be able to decide
        assertRefused("a1 was superseded and must not be able to approve",
                () -> approveAs("a1", request.getId(), "stale approver a1"));
        assertRefused("a2 was superseded and must not be able to approve",
                () -> approveAs("a2", request.getId(), "stale approver a2"));
        assertEquals("the refused decisions must leave the request PENDING",
                RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus());
        j.waitUntilNoActivity();
        assertTrue("no build may run from a superseded approver's decision", job.getBuilds().isEmpty());

        // only the final approver's decision is valid
        approveAs("a3", request.getId(), "ok");
        RequestStatus finalStatus = RunRequestService.get().load(request.getId()).getStatus();
        assertTrue("the final approver's decision must go through",
                finalStatus == RequestStatus.APPROVED || finalStatus == RequestStatus.EXECUTED);
        j.waitUntilNoActivity();
        assertEquals("the approved run must execute exactly once", 1, job.getBuilds().size());
    }

    /** T-03-06: an admin may designate themselves while allowAdminSelfApproval=true (default). */
    @Test
    public void t_03_06_adminMaySelfDesignateWhenAllowed() throws Exception {
        RunRequest request = createAs("admin", "admin", "urgent hotfix batch", params("DATE", "2026-09-01"));
        assertEquals("admin self-designation must succeed under allowAdminSelfApproval=true",
                RequestStatus.PENDING, request.getStatus());
        assertEquals("admin", request.getRequester());
        assertEquals("admin", request.getApprover());
    }

    // ---------------------------------------------------------------- SPEC 2 (carried over from S1)

    /** T-02-03: admin self-approval succeeds and the record carries selfApproved=true. */
    @Test
    public void t_02_03_adminSelfApprovalRecordsSelfApproved() throws Exception {
        RunRequest request = createAs("admin", "admin", "urgent hotfix batch", params("DATE", "2026-09-01"));
        approveAs("admin", request.getId(), "self approving as admin");

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertTrue("selfApproved=true must be recorded on an admin self-approval",
                reloaded.isSelfApproved());
        assertTrue("the request must be decided",
                reloaded.getStatus() == RequestStatus.APPROVED
                        || reloaded.getStatus() == RequestStatus.EXECUTED);

        j.waitUntilNoActivity();
        assertEquals("the approved run must execute", 1, job.getBuilds().size());
    }

    /** T-02-04: with allowAdminSelfApproval=false even the admin cannot approve their own request. */
    @Test
    public void t_02_04_selfApprovalRefusedWhenDisallowed() throws Exception {
        // the request is created while self-approval is still allowed ...
        RunRequest request = createAs("admin", "admin", "urgent hotfix batch", params("DATE", "2026-09-01"));

        // ... then the separation-of-duties policy is tightened
        cfg.setAllowAdminSelfApproval(false);
        cfg.save();

        assertRefused("separation of duties must apply to the admin when allowAdminSelfApproval=false",
                () -> approveAs("admin", request.getId(), "should not work"));

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("the request must stay PENDING", RequestStatus.PENDING, reloaded.getStatus());
        j.waitUntilNoActivity();
        assertTrue("no build may run", job.getBuilds().isEmpty());
    }

    // ---------------------------------------------------------------- SPEC 5

    /** T-05-01: the executed build's parameters exactly match the parameters stored at request time. */
    @Test
    public void t_05_01_executedBuildParametersMatchRequest() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));
        approveAs("a1", request.getId(), "checked the parameters");
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull("the approved request must have run the build", build);
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters);
        StringParameterValue date = (StringParameterValue) parameters.getParameter("DATE");
        assertNotNull(date);
        assertEquals("the build must run with exactly the parameters stored at request time",
                "2026-09-01", date.getValue());
    }

    /** T-05-02: an empty reason rejects request creation. */
    @Test
    public void t_05_02_emptyReasonRejected() throws Exception {
        assertRejectedAsInvalid("an empty reason must reject creation",
                () -> createAs("u1", "a1", "", params("DATE", "2026-09-01")));
        assertRejectedAsInvalid("a whitespace-only reason must reject creation",
                () -> createAs("u1", "a1", "   ", params("DATE", "2026-09-01")));
        assertTrue(RunRequestService.get().list().isEmpty());
    }

    /** T-05-04: rejecting with an empty comment is refused and the request stays PENDING. */
    @Test
    public void t_05_04_rejectWithEmptyCommentRefused() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));

        assertRejectedAsInvalid("a rejection without a comment must be refused",
                () -> {
                    try (ACLContext ignored = as("a1")) {
                        RunRequestService.get().reject(request.getId(), "");
                    }
                });

        assertEquals("the request must stay PENDING after the refused rejection",
                RequestStatus.PENDING, RunRequestService.get().load(request.getId()).getStatus());
    }

    /** T-05-05: the executed build carries request id, requester and approver as Cause and build Action. */
    @Test
    public void t_05_05_buildShowsCauseAndAction() throws Exception {
        RunRequest request = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));
        approveAs("a1", request.getId(), "ok");
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build);
        ApprovedCause cause = build.getCause(ApprovedCause.class);
        assertNotNull("the build must carry an ApprovedCause", cause);
        assertEquals(request.getId(), cause.getRequestId());
        assertEquals("u1", cause.getRequester());
        assertEquals("a1", cause.getApprover());
        assertNotNull("the build must carry the approved-run marker action",
                build.getAction(ApprovedRunAction.class));

        String buildPage = j.createWebClient().login("admin")
                .getPage(build).getWebResponse().getContentAsString();
        assertTrue("the request id must be visible on the build page",
                buildPage.contains(request.getId()));
    }

    /** T-05-06: a job-level approver restriction narrows the global list. */
    @Test
    public void t_05_06_jobLevelApproverRestrictionEnforced() throws Exception {
        property.setJobApprovers(Arrays.asList("a1"));

        assertRejectedAsInvalid("an approver outside the job-level restriction must reject creation",
                () -> createAs("u1", "a2", "month-end batch", params("DATE", "2026-09-01")));

        RunRequest allowed = createAs("u1", "a1", "month-end batch", params("DATE", "2026-09-01"));
        assertEquals("the approver inside the restriction must be accepted",
                RequestStatus.PENDING, allowed.getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRequest createAs(String userId, String approver, String reason, Map<String, String> parameters) {
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get().create(job, parameters, reason, approver);
        }
    }

    private RunRequest approveAs(String userId, String requestId, String comment) {
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get().approve(requestId, comment);
        }
    }

    /** SPEC validation failures surface as IllegalArgumentException or hudson.model.Failure. */
    private static void assertRejectedAsInvalid(String message, ThrowingRunnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | Failure expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got " + other, other);
        }
        assertTrue(message, rejected);
    }

    /** Authorization/state refusals: the exact runtime exception type is not pinned by SPEC. */
    private static void assertRefused(String message, ThrowingRunnable action) {
        boolean refused = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(message, refused);
    }
}
