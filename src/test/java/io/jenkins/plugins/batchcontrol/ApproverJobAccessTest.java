package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (#26): whether an approver may approve is decided by the approval policy alone.
 * An approved request is submitted even when the approver holds only {@code Item/Discover} or
 * no permission at all on the job. Matrix rows T-05-14 .. T-05-17 (note 71).
 *
 * <p>Actors: {@code u1} requester (Read, Build, Request); {@code ad} approver with
 * {@code Item/Discover} on the job and no {@code Item/Read}; {@code an} approver with no
 * {@code Item/*} permission; {@code a2} an approver in the global list who holds Item/Read but
 * is not designated (the policy's own refusal, the negative twin).
 *
 * Written from docs/SPEC.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ApproverJobAccessTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        job = j.createFreeStyleProject("batch-x");

        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                // approvers: BatchControl/Approve everywhere, but no Item/Read
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to("ad", "an")
                .grant(Item.DISCOVER).onItems(job).to("ad")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a2"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("ad", "an", "a2"));
        cfg.save();
        setBatchControl(job, new BatchControlJobProperty(true));

        // premises, asserted rather than assumed
        assertTrue(can("ad", Item.DISCOVER), "fixture: ad must hold Item/Discover on batch-x");
        assertFalse(can("ad", Item.READ), "fixture: ad must NOT hold Item/Read on batch-x");
        assertFalse(can("an", Item.DISCOVER), "fixture: an must NOT hold Item/Discover on batch-x");
        assertFalse(can("an", Item.READ), "fixture: an must NOT hold Item/Read on batch-x");
        assertTrue(can("a2", Item.READ), "fixture: a2 must hold Item/Read on batch-x");
    }

    /** T-05-14: a Discover-only designated approver approves over HTTP -> success, EXECUTED, exactly one build. */
    @Test
    public void t_05_14_discoverOnlyApproverApprovalRunsOnce() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "ad");

        WebResponse response = ApproverFormFixtures.decideRun(j, "ad", id, "approve", "ok");

        ApproverFormFixtures.assertSuccess(response, "the approval by a Discover-only designated approver (#26)");
        assertRanOnce(id);
    }

    /** T-05-15: a designated approver with no permission on the job approves over HTTP -> success, EXECUTED, one build. */
    @Test
    public void t_05_15_approverWithoutJobPermissionApprovalRunsOnce() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "an");

        WebResponse response = ApproverFormFixtures.decideRun(j, "an", id, "approve", "ok");

        ApproverFormFixtures.assertSuccess(response, "the approval by a designated approver without any job permission (#26)");
        assertRanOnce(id);
    }

    /** T-05-16: the same through the service API as the Discover-only approver -> no exception, EXECUTED, one build. */
    @Test
    public void t_05_16_serviceApproveByDiscoverOnlyApproverRunsOnce() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "ad");

        try (ACLContext ignored = ACL.as2(User.getById("ad", true).impersonate2())) {
            RunRequestService.get().approve(id, "ok"); // must not throw AccessDeniedException (#26)
        }
        assertRanOnce(id);
    }

    /**
     * T-05-17 (negative twin): the policy still decides. a2 is in the global list and can read the
     * job, but is not designated on this request -> 4xx, PENDING, blocking triple.
     */
    @Test
    public void t_05_17_nonDesignatedApproverIsStillRefusedByPolicy() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "ad");
        int nextBuildNumber = job.getNextBuildNumber();

        WebResponse response = ApproverFormFixtures.decideRun(j, "a2", id, "approve", "ok");

        ApproverFormFixtures.assertClientError(response, "the approval by a non-designated approver");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "a refused approval must leave the request PENDING");
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must be empty");
        assertEquals(nextBuildNumber, job.getNextBuildNumber(), "no build number may have been consumed");
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");
    }

    // ---------------------------------------------------------------- helpers

    private boolean can(String userId, Permission permission) {
        return job.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private void assertRanOnce(String id) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "the approved request must have been submitted and run exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build);
        j.assertBuildStatusSuccess(build);
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus(), "the request must move to EXECUTED, not stay APPROVED (#26)");

        // exactly once: nothing further is queued or run afterwards
        j.waitUntilNoActivity();
        assertEquals(0, j.jenkins.getQueue().getItems().length, "nothing further may be queued");
        assertEquals(2, job.getNextBuildNumber(), "exactly one build number must have been consumed");
    }
}
