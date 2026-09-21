package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Phase 4 security-fix regressions, derived from docs/reports/security-01.md and
 * docs/DECISIONS.md P-09 (visibility model). Matrix rows T-SEC-08 .. T-SEC-14.
 *
 * - S-01 / P-09: run-request and grant-request visibility filtering (silent list filter,
 *   404 on non-visible detail URLs, active-grant table own-only unless MANAGE).
 * - S-06: incident rerun requires Item/Read on the incident's job.
 * - S-07: the per-job request form requires BatchControl/Request.
 * - S-03: empty-string scope names are rejected (no root-scope grants).
 * - S-11: the wrapper strategy refuses to nest itself as delegate.
 * - S-05: the configure-without-grant monitor is consistent across calls and its cached
 *   result is invalidated by a change-control toggle.
 *
 * Written from the security report, DECISIONS P-09 and the coordinator contract only
 * (no src/main knowledge).
 */
public class SecurityRegressionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-SEC-08 (S-01/P-09, runs): a Request holder without Item/Read on the target job
     * neither lists nor opens another user's request (404, same as nonexistent); the
     * designated approver, a MANAGE holder and a Request holder WITH Item/Read all see it.
     */
    @Test
    public void s_01_runRequestVisibilityFollowsP09() throws Exception {
        FreeStyleProject jobA = j.createFreeStyleProject("secret-job");
        jobA.addProperty(new BatchControlJobProperty(true));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, BatchControlPermissions.REQUEST).everywhere().to("u1", "u2", "u3")
                .grant(Item.READ).onItems(jobA).to("u1", "u3") // u2 deliberately has NO Item/Read
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, BatchControlPermissions.MANAGE).everywhere().to("m1"));

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("P", "p09-confidential-value");
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(jobA, parameters,
                    "p09-confidential-reason", "a1");
        }
        String id = request.getId();

        // u2: Request holder, not requester/approver, no Item/Read -> filtered + 404 (P-09)
        JenkinsRule.WebClient u2 = webClient("u2");
        WebResponse u2List = get(u2, "batch-control/requests/");
        assertEquals(200, u2List.getStatusCode());
        assertFalse("the list must silently filter a request whose job u2 cannot read (S-01)",
                u2List.getContentAsString().contains(id));
        assertFalse("no reason text of a non-visible request may leak into u2's list (S-01)",
                u2List.getContentAsString().contains("p09-confidential-reason"));
        assertEquals("a non-visible detail URL must answer 404, same as nonexistent (P-09)",
                404, get(u2, "batch-control/requests/" + id + "/").getStatusCode());

        // u3: Request holder WITH Item/Read on the job -> visible (P-09)
        JenkinsRule.WebClient u3 = webClient("u3");
        assertTrue("Item/Read on the target job must make the request visible (P-09)",
                get(u3, "batch-control/requests/").getContentAsString().contains(id));
        assertEquals(200, get(u3, "batch-control/requests/" + id + "/").getStatusCode());

        // designated approver -> visible even without Item/Read (P-09)
        JenkinsRule.WebClient a1 = webClient("a1");
        assertTrue("the designated approver must see the request (P-09)",
                get(a1, "batch-control/requests/").getContentAsString().contains(id));
        assertEquals(200, get(a1, "batch-control/requests/" + id + "/").getStatusCode());

        // MANAGE holder -> visible (P-09)
        assertEquals(200, get(webClient("m1"),
                "batch-control/requests/" + id + "/").getStatusCode());
    }

    /**
     * T-SEC-09 (S-01/P-09, grants): a non-MANAGE RequestGrant holder sees only their own or
     * assigned grant requests (foreign detail is 404) and only their own active grants;
     * MANAGE sees everything. Item/Read is irrelevant to grant visibility.
     */
    @Test
    public void s_01_grantVisibilityIsOwnAssignedOrManageOnly() throws Exception {
        j.createFreeStyleProject("batch-x");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("g1", "g2", "g3")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();

        GrantRequest r1 = grantRequestAs("g1");
        GrantRequest r3 = grantRequestAs("g3");
        Grant gr1 = approveGrant(r1.getId());
        Grant gr3 = approveGrant(r3.getId());

        // g2: RequestGrant holder with Item/Read everywhere, but neither requester nor
        // approver of anything -> sees nothing of others (P-09: Item/Read is irrelevant here)
        JenkinsRule.WebClient g2 = webClient("g2");
        String g2Page = get(g2, "batch-control/grants/").getContentAsString();
        assertEquals(200, get(g2, "batch-control/grants/").getStatusCode());
        for (String foreign : new String[] {r1.getId(), r3.getId(), gr1.getId(), gr3.getId()}) {
            assertFalse("g2 must not see the foreign id " + foreign + " (S-01)",
                    g2Page.contains(foreign));
        }
        assertEquals("a foreign grant-request detail URL must answer 404 (P-09)",
                404, get(g2, "batch-control/grants/" + r1.getId() + "/").getStatusCode());

        // g1: own request and own active grant only
        String g1Page = get(webClient("g1"), "batch-control/grants/").getContentAsString();
        assertTrue("g1 must see their own grant request", g1Page.contains(r1.getId()));
        assertFalse("g1 must not see g3's grant request (S-01)", g1Page.contains(r3.getId()));
        assertFalse("the active-grant table must show own grants only for non-MANAGE (P-09)",
                g1Page.contains(gr3.getId()));

        // the designated approver sees both requests (assigned queue)
        String a1Page = get(webClient("a1"), "batch-control/grants/").getContentAsString();
        assertTrue("the designated approver must see g1's request", a1Page.contains(r1.getId()));
        assertTrue("the designated approver must see g3's request", a1Page.contains(r3.getId()));

        // MANAGE sees everything, active grants included
        String m1Page = get(webClient("m1"), "batch-control/grants/").getContentAsString();
        for (String anyId : new String[] {r1.getId(), r3.getId(), gr1.getId(), gr3.getId()}) {
            assertTrue("MANAGE must see " + anyId, m1Page.contains(anyId));
        }
    }

    /** T-SEC-10 (S-06): incident rerun without Item/Read on the incident's job is 403. */
    @Test
    public void s_06_rerunWithoutItemReadOnJobIs403() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("inc-job");
        job.getBuildersList().add(new FailureBuilder());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // vr may view incidents and create requests, but cannot read inc-job (S-06)
                .grant(Jenkins.READ, BatchControlPermissions.VIEW_HISTORY,
                        BatchControlPermissions.REQUEST).everywhere().to("vr")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        Incident incident = IncidentService.get().list(YearMonth.now()).stream()
                .filter(i -> "inc-job#1".equals(i.getRunId()))
                .findFirst().orElse(null);
        assertNotNull("fixture: the FAILURE must have opened an incident", incident);
        int requestsBefore = RunRequestService.get().list().size();

        JenkinsRule.WebClient vr = webClient("vr");
        WebRequest rerun = new WebRequest(
                wcCrumbed(vr, "batch-control/incidents/" + incident.getId() + "/rerun"),
                HttpMethod.POST);
        rerun.setRequestParameters(Collections.singletonList(new NameValuePair("approver", "a1")));
        assertEquals("rerun without Item/Read on the incident's job must be 403 (S-06)",
                403, vr.getPage(rerun).getWebResponse().getStatusCode());

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue("no rerun request may have been linked to the incident",
                reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty());
        assertEquals("no run request may have been created by the rejected rerun",
                requestsBefore, RunRequestService.get().list().size());
    }

    /** T-SEC-11 (S-07): the per-job request form requires BatchControl/Request. */
    @Test
    public void s_07_requestFormRequiresRequestPermission() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ).everywhere().to("ro") // Item/Read only
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1"));

        assertEquals("an Item/Read-only user must get 403 on the request form (S-07)",
                403, get(webClient("ro"), "job/batch-x/batch-control/").getStatusCode());
        assertEquals("a Request holder must still reach the form",
                200, get(webClient("u1"), "job/batch-x/batch-control/").getStatusCode());
    }

    /** T-SEC-12 (S-03): an empty scope full name is rejected for both JOB and FOLDER types. */
    @Test
    public void s_03_emptyScopeNameIsRejected() throws Exception {
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();

        for (GrantScope.Type type : new GrantScope.Type[] {
                GrantScope.Type.FOLDER, GrantScope.Type.JOB}) {
            assertRejectedAsInvalid("an empty " + type + " scope name must be rejected "
                    + "(S-03: root-scope grants are not supported)", () -> {
                        try (ACLContext ignored = as("g1")) {
                            GrantRequestService.get().create(new GrantScope(type, ""),
                                    Arrays.asList(GrantAction.CONFIGURE), 30,
                                    "instance-wide grab", "a1");
                        }
                    });
        }
        assertTrue("no grant request may be stored after the rejected creations",
                GrantRequestService.get().list().isEmpty());
    }

    /** T-SEC-13 (S-11): the wrapper strategy refuses another wrapper as its delegate. */
    @Test
    public void s_11_selfNestingWrapperStrategyIsRejected() {
        BatchControlAuthorizationStrategy inner =
                new BatchControlAuthorizationStrategy(new MockAuthorizationStrategy());
        assertThrows("nesting the wrapper inside itself must be rejected at construction (S-11)",
                IllegalArgumentException.class,
                () -> new BatchControlAuthorizationStrategy(inner));
    }

    /**
     * T-SEC-14 (S-05): the configure-without-grant monitor answers consistently on
     * back-to-back calls (cached) and the cache is invalidated promptly by a
     * change-control toggle.
     */
    @Test
    public void s_05_monitorActivationConsistentAndInvalidatedOnToggle() throws Exception {
        AdministrativeMonitor monitor =
                AdministrativeMonitor.all().get(ConfigureWithoutGrantMonitor.class);
        assertNotNull(monitor);

        cfg.setChangeControlEnabled(true);
        cfg.save();
        GlobalMatrixAuthorizationStrategy withDirectConfigure = new GlobalMatrixAuthorizationStrategy();
        withDirectConfigure.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        withDirectConfigure.add(Jenkins.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.CONFIGURE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(
                new BatchControlAuthorizationStrategy(withDirectConfigure));

        boolean first = monitor.isActivated();
        boolean second = monitor.isActivated();
        assertTrue("a non-admin with direct Item/Configure must activate the monitor", first);
        assertEquals("back-to-back isActivated() calls must agree (S-05 cache)", first, second);

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse("the cached activation must be invalidated by the change-control toggle "
                + "(S-05)", monitor.isActivated());

        cfg.setChangeControlEnabled(true);
        cfg.save();
        assertTrue("re-enabling change control must promptly re-activate the monitor (S-05)",
                monitor.isActivated());
    }

    // ---------------------------------------------------------------- helpers

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private WebResponse get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
    }

    private URL wcCrumbed(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.createCrumbedUrl(relative);
    }

    private GrantRequest grantRequestAs(String userId) {
        try (ACLContext ignored = as(userId)) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance window", "a1");
        }
    }

    private Grant approveGrant(String requestId) {
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(requestId, "ok");
        }
    }

    private static void assertRejectedAsInvalid(String message, ThrowingRunnable action) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException | hudson.model.Failure expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got "
                    + other, other);
        }
        assertTrue(message, rejected);
    }
}
