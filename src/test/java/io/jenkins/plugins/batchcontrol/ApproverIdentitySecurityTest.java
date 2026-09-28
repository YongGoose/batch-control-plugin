package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.changeRunApprovers;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression rows for security-08 S-11 (= #23) and S-12 (SPEC item 3): the job's own approver
 * list ({@code jobApprovers}) in force at decision time applies to the deciding approver, and it
 * applies to a designation change even when the requester has since lost {@code Item/Read} on
 * the job; user ids are compared with Jenkins' configured user id strategy (the dummy realm's
 * default is case-insensitive), not with plain string equality. Matrix rows T-SEC-46 .. T-SEC-50.
 *
 * <p>Written from docs/SPEC.md, docs/reports/security-08.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class ApproverIdentitySecurityTest {

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlJobProperty property;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(authorization(true));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3", "alice", "boss"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        property = setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * MockAuthorizationStrategy matches sids literally, so both spellings of the case-variant
     * users are granted; what is under test is the plugin's own id comparison.
     */
    private static MockAuthorizationStrategy authorization(boolean u1ReadsItems) {
        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin", "BOSS", "boss")
                .grant(Jenkins.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.APPROVE).everywhere().to("ALICE", "alice");
        if (u1ReadsItems) {
            strategy.grant(Item.READ, Item.BUILD).everywhere().to("u1");
        }
        return strategy;
    }

    /**
     * T-SEC-46 (S-11, #23): after u1 lost Item/Read on the job and the job's approver list was
     * narrowed to [a1], u1 cannot route the request to a3 (outside jobApprovers): refused,
     * nothing changed. Routing to a1 (inside) still works, so the refusal is the jobApprovers
     * check and not the lost Item/Read.
     */
    @Test
    public void t_sec_46_changeApproverAppliesJobApproversWithoutItemRead() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a2");
        property.setJobApprovers(Arrays.asList("a1"));
        job.save();
        j.jenkins.setAuthorizationStrategy(authorization(false));

        assertClientError(changeRunApprovers(j, "u1", id, "a3"), "routing to a3, who is outside jobApprovers");
        RunRequest unchanged = RunRequestService.get().load(id);
        assertEquals(Arrays.asList("a2"), unchanged.getApprovers(), "the refused change must leave the set as it was");
        assertTrue(unchanged.getApproverChanges() == null || unchanged.getApproverChanges().isEmpty(),
                "a refused change must not be recorded");

        assertSuccess(changeRunApprovers(j, "u1", id, "a1"), "routing to a1, who is inside jobApprovers");
        assertEquals(Arrays.asList("a1"), RunRequestService.get().load(id).getApprovers());
    }

    /**
     * T-SEC-47 (S-11, #23): jobApprovers narrowed after the designation applies at decision time:
     * a2, designated when the job had no list, can no longer approve once the list is [a1]; a1 can.
     */
    @Test
    public void t_sec_47_jobApproversNarrowedAfterDesignationApplyAtDecision() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        property.setJobApprovers(Arrays.asList("a1"));
        job.save();

        assertClientError(decideRun(j, "a2", id, "approve", "ok"), "approval by a2, who is outside jobApprovers now");
        RunRequest pending = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, pending.getStatus(), "the refused approval must leave the request PENDING");
        assertNull(pending.getDecidedBy());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may run from the refused approval");
        assertEquals(1, job.getNextBuildNumber(), "the next build number must be unchanged");

        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "approval by a1, who is inside jobApprovers");
        j.waitUntilNoActivity();
        RunRequest executed = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, executed.getStatus());
        assertEquals("a1", executed.getDecidedBy());
        assertEquals(1, job.getBuilds().size());
    }

    /**
     * T-SEC-48 (S-12): under the case-insensitive id strategy, ALICE (not an administrator) cannot
     * designate {@code alice} or {@code Alice} (herself), at submission or by a later change. A
     * designation of a1 is accepted (control).
     */
    @Test
    public void t_sec_48_caseVariantSelfDesignationIsRefused() throws Exception {
        assertTrue(j.jenkins.getSecurityRealm().getUserIdStrategy().equals("ALICE", "alice"),
                "premise: the configured user id strategy is case-insensitive");
        Set<String> before = runRequestIds();
        assertClientError(submitRun(j, "ALICE", job, "month-end batch", "alice"), "ALICE designating alice");
        assertClientError(submitRun(j, "ALICE", job, "month-end batch", "a1", "Alice"), "ALICE designating [a1, Alice]");
        assertEquals(before, runRequestIds(), "no request may be stored after a refused self-designation");

        String id = submitRunOk(j, "ALICE", job, "month-end batch", "a1");
        assertClientError(changeRunApprovers(j, "ALICE", id, "alice"), "ALICE changing the set to alice");
        assertEquals(Arrays.asList("a1"), RunRequestService.get().load(id).getApprovers(), "the set must be unchanged");
    }

    /**
     * T-SEC-49 (S-12): an administrator BOSS designates {@code boss} under the admin exception; once
     * allowAdminSelfApproval is false, {@code boss} (the same user) cannot approve: refused,
     * PENDING, no build.
     */
    @Test
    public void t_sec_49_caseVariantSelfApprovalIsRefusedWithoutAdminException() throws Exception {
        assertTrue(j.jenkins.getSecurityRealm().getUserIdStrategy().equals("BOSS", "boss"),
                "premise: the configured user id strategy is case-insensitive");
        String id = submitRunOk(j, "BOSS", job, "month-end batch", "boss");
        cfg.setAllowAdminSelfApproval(false);
        cfg.save();

        assertClientError(decideRun(j, "boss", id, "approve", "ok"), "boss approving BOSS's own request");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may run from the refused self-approval");
    }

    /**
     * T-SEC-50 (S-12, twin of T-SEC-49): with the admin exception on, the same approval succeeds and
     * is recorded as a self-approval ({@code selfApproved=true}), because {@code boss} and
     * {@code BOSS} are the same user.
     */
    @Test
    public void t_sec_50_caseVariantAdminSelfApprovalIsRecordedAsSelfApproved() throws Exception {
        assertTrue(cfg.isAllowAdminSelfApproval(), "premise: the admin exception is on by default");
        String id = submitRunOk(j, "BOSS", job, "month-end batch", "boss");

        assertSuccess(decideRun(j, "boss", id, "approve", "ok"), "boss approving BOSS's request under the exception");
        j.waitUntilNoActivity();
        RunRequest executed = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, executed.getStatus());
        assertTrue(executed.isSelfApproved(), "the case-variant self-approval must be recorded as selfApproved=true");
        assertFalse(job.getBuilds().isEmpty());
    }
}
