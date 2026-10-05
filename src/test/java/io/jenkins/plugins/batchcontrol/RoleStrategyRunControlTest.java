package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run control under the Batch Control role strategy (D-35a), whose rows so far used
 * MockAuthorizationStrategy or matrix-auth: SPEC item 2 (D-38b: "{@code BatchControl/Request} is
 * checked on the job for every job-specific use ... A user who holds Request only on some jobs or
 * folders reaches the Batch Control root page and the run requests section once they have requests
 * of their own, and the activations section whenever they reach the root page ..., and after
 * submitting lands on their request's page (never a 404 or 403)") and SPEC item 6 (manual runs are
 * refused at the queue; an approved request runs exactly once). Coverage inventory G-M9 and G-L13;
 * matrix rows T-05-109 and T-06-106 (note 269).
 *
 * <p>Roles: global {@code admin} (Administer), {@code reader} (Overall/Read, Item/Read: r1, u1,
 * a1), {@code requester} (Item/Build, BatchControl/Request: u1), {@code approver} (Approve: a1);
 * item role {@code ops-requests} on {@code ops/.*} with BatchControl/Request for r1 only.
 *
 * <p>Written from docs/SPEC.md items 2 and 6, docs/DECISIONS.md D-35a and D-38b and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategyRunControlTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-05-109 (G-M9): r1 holds BatchControl/Request only through the item role on {@code ops/.*}
     * (premise: on {@code ops/in-x}, not on {@code out-x}, not globally). r1's submission for the
     * approval-required {@code ops/in-x} creates one request and lands on its detail page (200);
     * r1 then opens the root, the run requests section (listing the request) and the activations
     * section (200 each). For {@code out-x} the Request Run page and the submit endpoint answer 404
     * and nothing is stored.
     */
    @Test
    public void t_05_109_itemScopedRequestUnderTheRoleStrategy() throws Exception {
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        FreeStyleProject inX = ops.createProject(FreeStyleProject.class, "in-x");
        setBatchControl(inX, new BatchControlJobProperty(true));
        FreeStyleProject outX = j.createFreeStyleProject("out-x");
        setBatchControl(outX, new BatchControlJobProperty(true));
        assertTrue(can("r1", inX, BatchControlPermissions.REQUEST), "premise: r1 holds Request on ops/in-x through the item role");
        assertFalse(can("r1", outX, BatchControlPermissions.REQUEST), "premise: r1 holds no Request on out-x");
        assertFalse(j.jenkins.getACL().hasPermission2(User.getById("r1", true).impersonate2(), BatchControlPermissions.REQUEST),
                "premise: r1 holds no global Request");

        WebResponse submitted = ApproverFormFixtures.submitRun(j, "r1", inX, "month-end batch", "a1");
        ApproverFormFixtures.assertSuccess(submitted, "r1's submission for ops/in-x");
        List<String> mine = RunRequestService.get().list().stream().filter(r -> "r1".equals(r.getRequester()))
                .map(r -> r.getId()).toList();
        assertEquals(1, mine.size(), "r1's submission must store exactly one request, got " + mine);
        String id = mine.get(0);
        String location = submitted.getResponseHeaderValue("Location");
        assertNotNull(location, "the submission must lead somewhere (HTTP " + submitted.getStatusCode() + ")");
        String landing = new URL(submitted.getWebRequest().getUrl(), location).toExternalForm();
        assertEquals(UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), "batch-control/requests/" + id).toExternalForm()),
                UsabilityFixtures.stripQueryAndSlash(landing), "r1 must land on the request's detail page");
        assertEquals(200, ApproverFormFixtures.get(j, "r1", "batch-control/requests/" + id + "/").getStatusCode(),
                "r1 must open the detail page");
        assertEquals(200, ApproverFormFixtures.get(j, "r1", "batch-control/").getStatusCode(), "r1 must reach the root page");
        WebResponse section = ApproverFormFixtures.get(j, "r1", "batch-control/requests/");
        assertEquals(200, section.getStatusCode(), "r1 must open the run requests section");
        assertTrue(section.getContentAsString().contains(id), "the section must list r1's request");
        assertEquals(200, ApproverFormFixtures.get(j, "r1", "batch-control/activations/").getStatusCode(),
                "r1 must open the activations section (D-38c)");

        int before = RunRequestService.get().list().size();
        assertEquals(404, ApproverFormFixtures.get(j, "r1", outX.getUrl() + "batch-control/").getStatusCode(),
                "the Request Run page of out-x must be absent for r1");
        assertEquals(404, ApproverFormFixtures.submitRun(j, "r1", outX, "outside my role", "a1").getStatusCode(),
                "the submit endpoint of out-x must be absent for r1");
        assertEquals(before, RunRequestService.get().list().size(), "nothing may be stored for out-x");
    }

    /**
     * T-06-106 (G-L13): under the role strategy, u1 (Item/Build, Request) POSTs {@code build} on
     * the approval-required job {@code gate-x}: nothing is queued, no build number is consumed, no
     * build exists. u1's request, approved by a1, then runs exactly once as build #1 carrying the
     * approval cause, and the request is EXECUTED.
     */
    @Test
    public void t_06_106_runGateAndApprovalUnderTheRoleStrategy() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gate-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        assertTrue(can("u1", job, Item.BUILD), "premise: u1 holds Item/Build through the role");

        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        wc.getPage(new WebRequest(wc.createCrumbedUrl(job.getUrl() + "build?delay=0sec"), HttpMethod.POST));
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the manual build must not be queued");
        assertEquals(1, job.getNextBuildNumber(), "no build number may be consumed");
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");

        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "a1");
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.decideRun(j, "a1", id, "approve", "ok"), "a1's approval");
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "the approved request must run exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build);
        assertEquals(id, build.getCause(ApprovedCause.class).getRequestId(), "the build carries the approval cause");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""),
                set(PermissionEntry.user("r1"), PermissionEntry.user("u1"), PermissionEntry.user("a1")));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(Item.BUILD, BatchControlPermissions.REQUEST), ""),
                set(PermissionEntry.user("u1")));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(BatchControlPermissions.APPROVE), ""), set(PermissionEntry.user("a1")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("ops-requests", Pattern.compile("ops/.*"), Set.of(BatchControlPermissions.REQUEST), ""),
                set(PermissionEntry.user("r1")));
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
