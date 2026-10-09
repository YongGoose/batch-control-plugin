package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
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
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt B, R2-04 (matrix row T-07-12, note 297): when a rename invalidates an approved request
 * whose run waits in the queue, the queued item is cancelled whoever renamed the job, also when the
 * renaming user can no longer read the job afterwards.
 *
 * <p>Basis: SPEC 7 ("PENDING/APPROVED 요청의 대상 잡이 rename 또는 move되면 요청은 상태 INVALIDATED로
 * 종료되고 이력에 남는다", R-6, D-21; SPEC 4절 PENDING | APPROVED -> INVALIDATED) and RT-03 (a run
 * the approver did not review never starts), and the frozen bug-hunt contract: the queued item is
 * cancelled regardless of who renamed (queue read and cancel as SYSTEM after core's own permission
 * check): request INVALIDATED, queue empty, no build runs even after an executor becomes available.
 * Reproduced on a real Jenkins with the Batch Control role strategy: after a rename to a name outside
 * the renaming user's item-role pattern the queued item stayed and later ran.
 *
 * <p>Batch Control role strategy (D-35a): global roles give admin Administer, u1 Overall/Read and
 * BatchControl/Request, a1 Overall/Read, Item/Read and Approve, u2 Overall/Read only; the item role
 * {@code team-a} on {@code team-a-.*} gives u1 and u2 Item/Read, Configure and Build. Run control on,
 * change control off (a window would also confer Item/Read and hide the effect; the reproduction did
 * the same). The jobs are restricted to the label {@code bh-absent}, which no node carries, so the
 * approved run waits in the queue; the built-in node receives the label afterwards.
 *
 * <p>Written from docs/SPEC.md items 4절 and 7, DECISIONS D-21 and D-35a, red-team RT-03 and the
 * bug-hunt B contract only (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategyRenameInvalidationTest {

    private static final String ABSENT = "bh-absent";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");
        j.jenkins.setLabelString("");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(false);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-07-12 (R2-04): u1's request on {@code team-a-nightly}, approved by a1, waits in the queue.
     * u2 (Configure only through the item role {@code team-a-.*}) renames the job to
     * {@code nightly-old}, after which u2 cannot read it. The request is INVALIDATED and no queue item
     * of the job is left; once the built-in node carries the label, still no build runs and the next
     * build number is unchanged. Guard first: the same scenario on {@code team-a-admin} renamed by the
     * administrator to {@code admin-old} ends INVALIDATED with the queue item cancelled.
     */
    @Test
    public void t_07_12_renameByUserWhoLosesReadCancelsQueuedApprovedRun() throws Exception {
        FreeStyleProject adminJob = queuedApprovedRun("team-a-admin");
        String adminRequest = lastRequestId;
        assertSuccess(rename("admin", adminJob, "admin-old"), "guard: the administrator renames team-a-admin");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(adminRequest).getStatus(),
                "guard: the administrator's rename invalidates the approved request (D-21)");
        assertTrue(waitFor(() -> queued(adminJob).isEmpty(), 5_000),
                "guard: the administrator's rename cancels the queued run, left " + queued(adminJob));

        FreeStyleProject job = queuedApprovedRun("team-a-nightly");
        String request = lastRequestId;
        int nextBuild = job.getNextBuildNumber();
        assertSuccess(rename("u2", job, "nightly-old"), "u2 renames team-a-nightly (Configure through the item role)");
        assertEquals("nightly-old", job.getFullName(), "premise: the job is renamed");
        assertFalse(job.getACL().hasPermission2(token("u2"), Item.READ),
                "premise: u2 cannot read the job under its new name (outside the item role pattern)");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(request).getStatus(),
                "the rename invalidates the approved request (D-21)");
        assertTrue(waitFor(() -> queued(job).isEmpty(), 5_000), "the queued run of the invalidated request must be cancelled"
                + " although u2, who renamed the job, cannot read it any more; left in the queue: " + queued(job));

        j.jenkins.setLabelString(ABSENT);
        j.jenkins.getQueue().scheduleMaintenance();
        assertFalse(waitFor(() -> !job.getBuilds().isEmpty(), 3_000),
                "no build may run from the invalidated approval once an executor carries the label");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may run from the invalidated approval");
        assertEquals(nextBuild, job.getNextBuildNumber(), "the next build number must be unchanged");
    }

    // ---------------------------------------------------------------- helpers

    private String lastRequestId;

    /** An approval-required job restricted to {@link #ABSENT}, with u1's request approved by a1 and its run queued (asserted). */
    private FreeStyleProject queuedApprovedRun(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.setAssignedLabel(j.jenkins.getLabel(ABSENT));
        setBatchControl(job, new BatchControlJobProperty(true));
        lastRequestId = submitRunOk(j, "u1", job, "nightly batch of " + name, "a1");
        assertSuccess(decideRun(j, "a1", lastRequestId, "approve", "ok"), "fixture: a1 approves the request on " + name);
        assertTrue(waitFor(() -> queued(job).size() == 1, 10_000),
                "fixture: the approved run of " + name + " must wait in the queue, queued " + queued(job));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(lastRequestId).getStatus(),
                "fixture: the request stays APPROVED while its run waits");
        assertTrue(job.getBuilds().isEmpty(), "fixture: nothing has run yet");
        return job;
    }

    /** The queue items of {@code job}, read as SYSTEM so that no viewer filter applies. */
    private List<Queue.Item> queued(FreeStyleProject job) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Arrays.stream(j.jenkins.getQueue().getItems()).filter(i -> i.task == job).collect(Collectors.toList());
        }
    }

    private org.htmlunit.WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
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

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("bc-requester", Pattern.compile(".*"), Set.of(Jenkins.READ, BatchControlPermissions.REQUEST), ""),
                set(PermissionEntry.user("u1")));
        global.put(new Role("bc-approver", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE), ""),
                set(PermissionEntry.user("a1")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ), ""), set(PermissionEntry.user("u2")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("team-a", Pattern.compile("team-a-.*"), Set.of(Item.READ, Item.CONFIGURE, Item.BUILD), ""),
                set(PermissionEntry.user("u1"), PermissionEntry.user("u2")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }
}
