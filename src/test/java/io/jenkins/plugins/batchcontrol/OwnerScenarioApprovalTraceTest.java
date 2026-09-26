package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner scenario S-2 — "is the run history tied back to the approval?".
 * Matrix row T-OS-04: with the requester and the approver being two different accounts,
 * an approved run must be identifiable as executed by that request from both directions,
 * and a run that had no approval (a timer firing on the same job) must be distinguishable
 * from it.
 *
 * Accounts: requester {@code u1}, designated approver {@code a1}, administrator {@code admin}
 * (reads the build page). The positive links alone are already covered by T-05-05 (Cause and
 * build Action) and T-10-04 (record/request cross-reference); this row adds the two-account
 * assertion on the rendered build page and the negative discrimination against an
 * unapproved run.
 *
 * SPEC basis: item 5 ("실행된 빌드에는 요청 ID, 요청자, 결재자가 Cause와 빌드 Action으로
 * 표시된다") and item 10 (causeType classification, APPROVED_REQUEST links to the request).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class OwnerScenarioApprovalTraceTest {

    private static final String REQUESTER = "u1";
    private static final String APPROVER = "a1";
    private static final String ADMIN = "admin";

    private JenkinsRule j;

    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(APPROVER));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList(APPROVER));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
    }

    /**
     * T-OS-04 (owner scenario S-2): requester u1 and approver a1 are two different accounts.
     * The executed build must carry both identities and the request id (Cause, build Action
     * and the rendered build page), the run record must point at the request and the request
     * at the run, and the unapproved timer run on the same job must carry none of it.
     */
    @Test
    public void t_os_04_approvedRunIsTiedToRequestRequesterAndApprover() throws Exception {
        RunRequest request;
        try (ACLContext ignored = as(REQUESTER)) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(),
                    "month-end batch, closing figures", APPROVER);
        }
        assertEquals(REQUESTER, request.getRequester(), "the request must be owned by the requester account");
        assertEquals(APPROVER, request.getApprover(), "the request must be routed to the designated approver account");

        try (ACLContext ignored = as(APPROVER)) {
            RunRequestService.get().approve(request.getId(), "parameters reviewed, go ahead");
        }
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "the approval must have executed exactly one build");

        // ---- the run identifies the request, the requester and the approver
        FreeStyleBuild approvedBuild = job.getBuildByNumber(1);
        assertNotNull(approvedBuild);
        ApprovedCause cause = approvedBuild.getCause(ApprovedCause.class);
        assertNotNull(cause, "the approved run must carry the plugin's ApprovedCause");
        assertEquals(request.getId(), cause.getRequestId(), "the cause must name the request");
        assertEquals(REQUESTER, cause.getRequester(), "the cause must name the requester account");
        assertEquals(APPROVER, cause.getApprover(), "the cause must name the approver account (a different account)");

        ApprovedRunAction action = approvedBuild.getAction(ApprovedRunAction.class);
        assertNotNull(action, "the approved run must carry the approved-run build Action");
        assertEquals(request.getId(), action.getRequestId(), "the build Action must be bound to the same request");

        String buildPage = j.createWebClient().login(ADMIN)
                .getPage(approvedBuild).getWebResponse().getContentAsString();
        assertTrue(buildPage.contains(request.getId()), "the build page must show the request id");
        assertTrue(buildPage.contains(REQUESTER), "the build page must show the requester account");
        assertTrue(buildPage.contains(APPROVER), "the build page must show the approver account");

        // ---- the run record and the request cross-reference each other
        RunRecord approvedRecord = record("batch-x#1");
        assertNotNull(approvedRecord, "the approved run must be recorded");
        assertEquals(CauseType.APPROVED_REQUEST, approvedRecord.getCauseType());
        assertEquals(request.getId(), approvedRecord.getRunRequestId(), "the record must point back at the approval request");
        assertEquals("SUCCESS", approvedRecord.getResult());

        RunRequest reloaded = RunRequestService.get().load(request.getId());
        assertEquals("batch-x#1", reloaded.getExecutedRunId(), "the request must point at the run it executed");

        // ---- a run without any approval (timer firing, passes by default) is distinguishable
        Future<FreeStyleBuild> timerRun = job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause());
        assertNotNull(timerRun, "a timer cause must pass by default even on an approval-required job");
        j.assertBuildStatusSuccess(timerRun);
        j.waitUntilNoActivity();

        FreeStyleBuild unapprovedBuild = job.getBuildByNumber(2);
        assertNotNull(unapprovedBuild);
        assertNull(unapprovedBuild.getCause(ApprovedCause.class), "a run that had no approval must not carry an ApprovedCause");
        assertNull(unapprovedBuild.getAction(ApprovedRunAction.class), "a run that had no approval must not carry the approved-run Action");

        RunRecord unapprovedRecord = record("batch-x#2");
        assertNotNull(unapprovedRecord, "the timer run must be recorded too");
        assertEquals(CauseType.TIMER, unapprovedRecord.getCauseType(), "the timer run must be classified as TIMER, not APPROVED_REQUEST");
        assertNull(unapprovedRecord.getRunRequestId(), "a run without an approval must not reference any request");

        assertEquals("batch-x#1", RunRequestService.get().load(request.getId()).getExecutedRunId(), "the request must still point at its own run only");
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private RunRecord record(String runId) {
        return FileStore.get().listRunRecords(YearMonth.now()).stream()
                .filter(rec -> runId.equals(rec.getRunId()))
                .findFirst().orElse(null);
    }
}
