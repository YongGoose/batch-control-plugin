package io.jenkins.plugins.batchcontrol;

import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.GroupDetails;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 security-fix regressions, derived from docs/reports/security-01.md and
 * docs/DECISIONS.md P-09 (visibility model) and P-10 (no root-scope grants).
 * Matrix rows T-SEC-08 .. T-SEC-15, T-SEC-17, T-SEC-18.
 *
 * - S-01 / P-09: run-request and grant-request visibility filtering (silent list filter,
 *   404 on non-visible detail URLs, active-grant table own-only unless MANAGE).
 * - S-06: incident rerun requires Item/Read on the incident's job.
 * - S-07: the per-job request form requires BatchControl/Request.
 * - S-03: empty-string scope names are rejected (no root-scope grants).
 * - S-11: the wrapper strategy refuses to nest itself as delegate.
 * - S-05: the configure-without-grant monitor is consistent across calls, its scan result is
 *   really cached (measured with a lookup-counting security realm) and the cache is
 *   invalidated by a change-control toggle.
 * - S-13 / P-10: a stored grant request with an empty scope name cannot be approved, and
 *   approval re-validates the scope as the approver.
 *
 * Written from the security report, DECISIONS P-09 and the coordinator contract only
 * (no src/main knowledge).
 */
@WithJenkins
public class SecurityRegressionTest {

    private JenkinsRule j;

    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
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
        assertFalse(u2List.getContentAsString().contains(id), "the list must silently filter a request whose job u2 cannot read (S-01)");
        assertFalse(u2List.getContentAsString().contains("p09-confidential-reason"), "no reason text of a non-visible request may leak into u2's list (S-01)");
        assertEquals(404, get(u2, "batch-control/requests/" + id + "/").getStatusCode(), "a non-visible detail URL must answer 404, same as nonexistent (P-09)");

        // u3: Request holder WITH Item/Read on the job -> visible (P-09)
        JenkinsRule.WebClient u3 = webClient("u3");
        assertTrue(get(u3, "batch-control/requests/").getContentAsString().contains(id), "Item/Read on the target job must make the request visible (P-09)");
        assertEquals(200, get(u3, "batch-control/requests/" + id + "/").getStatusCode());

        // designated approver -> visible even without Item/Read (P-09)
        JenkinsRule.WebClient a1 = webClient("a1");
        assertTrue(get(a1, "batch-control/requests/").getContentAsString().contains(id), "the designated approver must see the request (P-09)");
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
            assertFalse(g2Page.contains(foreign), "g2 must not see the foreign id " + foreign + " (S-01)");
        }
        assertEquals(404, get(g2, "batch-control/grants/" + r1.getId() + "/").getStatusCode(), "a foreign grant-request detail URL must answer 404 (P-09)");

        // g1: own request and own active grant only
        String g1Page = get(webClient("g1"), "batch-control/grants/").getContentAsString();
        assertTrue(g1Page.contains(r1.getId()), "g1 must see their own grant request");
        assertFalse(g1Page.contains(r3.getId()), "g1 must not see g3's grant request (S-01)");
        assertFalse(g1Page.contains(gr3.getId()), "the active-grant table must show own grants only for non-MANAGE (P-09)");

        // the designated approver sees both requests (assigned queue)
        String a1Page = get(webClient("a1"), "batch-control/grants/").getContentAsString();
        assertTrue(a1Page.contains(r1.getId()), "the designated approver must see g1's request");
        assertTrue(a1Page.contains(r3.getId()), "the designated approver must see g3's request");

        // MANAGE sees everything, active grants included
        String m1Page = get(webClient("m1"), "batch-control/grants/").getContentAsString();
        for (String anyId : new String[] {r1.getId(), r3.getId(), gr1.getId(), gr3.getId()}) {
            assertTrue(m1Page.contains(anyId), "MANAGE must see " + anyId);
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
        assertNotNull(incident, "fixture: the FAILURE must have opened an incident");
        int requestsBefore = RunRequestService.get().list().size();

        JenkinsRule.WebClient vr = webClient("vr");
        WebRequest rerun = new WebRequest(
                wcCrumbed(vr, "batch-control/incidents/" + incident.getId() + "/rerun"),
                HttpMethod.POST);
        rerun.setRequestParameters(Collections.singletonList(new NameValuePair("approver", "a1")));
        assertEquals(403, vr.getPage(rerun).getWebResponse().getStatusCode(), "rerun without Item/Read on the incident's job must be 403 (S-06)");

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(), "no rerun request may have been linked to the incident");
        assertEquals(requestsBefore, RunRequestService.get().list().size(), "no run request may have been created by the rejected rerun");
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

        assertEquals(403, get(webClient("ro"), "job/batch-x/batch-control/").getStatusCode(), "an Item/Read-only user must get 403 on the request form (S-07)");
        assertEquals(200, get(webClient("u1"), "job/batch-x/batch-control/").getStatusCode(), "a Request holder must still reach the form");
    }

    /**
     * T-SEC-15 (SPEC 2 + 5, §6): the per-job submit POST itself requires BatchControl/Request.
     * The sibling T-SEC-11 covers only the GET form; a user without Request must not be able to
     * create a run request by POSTing straight at the submit endpoint, bypassing the UI form
     * they cannot even load. "Every state change is POST + permission check" (SPEC §6).
     */
    @Test
    public void t_sec_15_submitRunRequestWithoutRequestPermissionIs403() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("batch-x");
        job.addProperty(new BatchControlJobProperty(true));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                // u1 can read Jenkins and the job, but holds NO BatchControl/Request
                .grant(Jenkins.READ, Item.READ).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));

        int requestsBefore = RunRequestService.get().list().size();
        int nextBuildNumberBefore = job.getNextBuildNumber();

        // a complete, otherwise-valid submission: the job takes no parameters, so reason and
        // approver are the whole form. Only the missing permission may stop it.
        JenkinsRule.WebClient u1 = webClient("u1");
        WebRequest submit = new WebRequest(
                wcCrumbed(u1, "job/batch-x/batch-control/submit"), HttpMethod.POST);
        submit.setRequestParameters(Arrays.asList(
                new NameValuePair("reason", "direct submit without the Request permission"),
                new NameValuePair("approver", "a1")));

        assertEquals(403, u1.getPage(submit).getWebResponse().getStatusCode(), "a POST to the per-job submit endpoint without BatchControl/Request must be 403");

        assertEquals(requestsBefore, RunRequestService.get().list().size(), "the rejected submit must not have created a run request");
        // the denial must not degrade into a silent no-op that still runs the job
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may have been started by the rejected submit");
        assertEquals(nextBuildNumberBefore, job.getNextBuildNumber(), "the job's next build number must be unchanged");
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
        assertTrue(GrantRequestService.get().list().isEmpty(), "no grant request may be stored after the rejected creations");
    }

    /** T-SEC-13 (S-11): the wrapper strategy refuses another wrapper as its delegate. */
    @Test
    public void s_11_selfNestingWrapperStrategyIsRejected() {
        BatchControlAuthorizationStrategy inner =
                new BatchControlAuthorizationStrategy(new MockAuthorizationStrategy());
        assertThrows(IllegalArgumentException.class, () -> new BatchControlAuthorizationStrategy(inner), "nesting the wrapper inside itself must be rejected at construction (S-11)");
    }

    /**
     * T-SEC-14 (S-05): the configure-without-grant monitor answers consistently on
     * back-to-back calls, its expensive realm scan is actually CACHED (a second call performs
     * no further security-realm lookup — this is the page-load DoS that S-05 was about), and
     * the cache is invalidated promptly by a change-control toggle, after which the scan runs
     * again and still answers correctly.
     *
     * <p>The caching dimension is measured with a security realm that counts every
     * {@code loadUserByUsername2} call, so a build in which the cache were removed fails here
     * instead of passing on the (uncached but still consistent) verdict alone.
     */
    @Test
    public void s_05_monitorActivationConsistentAndInvalidatedOnToggle() throws Exception {
        CountingSecurityRealm realm = new CountingSecurityRealm();
        j.jenkins.setSecurityRealm(realm);

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

        // 1. first render: the scan runs and consults the security realm.
        int beforeFirst = realm.lookups.get();
        boolean first = monitor.isActivated();
        int afterFirst = realm.lookups.get();
        assertTrue(first, "a non-admin with direct Item/Configure must activate the monitor");
        assertTrue(afterFirst > beforeFirst, "fixture: the activation scan must consult the security realm at least once, "
                + "otherwise this test cannot measure the S-05 cache (observed "
                + (afterFirst - beforeFirst) + " lookups)");

        // 2. second render, nothing changed: same verdict AND no new realm lookup (cache hit).
        boolean second = monitor.isActivated();
        assertEquals(first, second, "back-to-back isActivated() calls must agree (S-05)");
        assertEquals(afterFirst, realm.lookups.get(), "the second isActivated() must be served from the cache: a /manage render "
                + "must not repeat the security-realm lookups (S-05 — this is the LDAP page-load "
                + "DoS the fix removed)");

        // 3. turning change control off must invalidate, and answer false.
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "the cached activation must be invalidated by the change-control toggle "
                + "(S-05)");

        // 4. turning it back on must invalidate again: the next call re-scans (new lookups)
        //    and the verdict is still correct.
        cfg.setChangeControlEnabled(true);
        cfg.save();
        int beforeReactivation = realm.lookups.get();
        assertTrue(monitor.isActivated(), "re-enabling change control must promptly re-activate the monitor (S-05)");
        assertTrue(realm.lookups.get() > beforeReactivation, "after the toggle the cache must have been invalidated, so the realm scan must "
                + "run again instead of answering from the stale snapshot (S-05)");

        // 5. and the freshly recomputed result is cached again.
        int afterReactivation = realm.lookups.get();
        assertTrue(monitor.isActivated());
        assertEquals(afterReactivation, realm.lookups.get(), "the recomputed result must be cached again (S-05)");
    }

    /**
     * T-SEC-17 (SPEC item 8 / DECISIONS P-10, security-02 S-13c): a grant request that carries an
     * empty scope full name — the shape a pre-P-10 build could persist, or a hand-edited store
     * file could contain — must NOT be approvable into a live grant. Creation of such a request is
     * already refused (T-SEC-12); this row pins the second gate, at approval time, for the
     * requests that creation never saw.
     */
    @Test
    public void s_13_storedEmptyScopeGrantRequestCannotBeApproved() throws Exception {
        j.createFreeStyleProject("batch-x");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("g1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();

        for (GrantScope.Type type : new GrantScope.Type[] {
                GrantScope.Type.FOLDER, GrantScope.Type.JOB}) {
            // written straight to the store, bypassing the service-side creation guard
            GrantRequest stored = GrantRequest.create(new GrantScope(type, ""),
                    Arrays.asList(GrantAction.CREATE, GrantAction.CONFIGURE, GrantAction.DELETE),
                    30, "persisted before the empty-scope rule existed", "g1", "a1");
            FileStore.get().saveGrantRequest(stored);
            assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(stored.getId()).getStatus(), "fixture: the request must really be in the store as PENDING");

            assertRejectedAsInvalid("approving a stored " + type + " request whose scope name is "
                    + "empty must be refused (P-10: no instance-wide grants)", () -> {
                        try (ACLContext ignored = as("a1")) {
                            GrantRequestService.get().approve(stored.getId(), "looks fine to me");
                        }
                    });

            assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(stored.getId()).getStatus(), "the refused approval must leave the request PENDING");
            assertTrue(GrantService.get().listActive().isEmpty(), "no active grant may exist after the refused approval");
            for (String anyJob : new String[] {"batch-x", "team/other"}) {
                for (hudson.security.Permission action : new hudson.security.Permission[] {
                        Item.CREATE, Item.CONFIGURE, Item.DELETE}) {
                    assertFalse(GrantService.get().hasActiveGrant("g1", anyJob, action), "a " + type + ":\"\" request must never confer " + action.getId()
                            + " on " + anyJob);
                }
            }
        }
    }

    /**
     * T-SEC-18 (SPEC item 8, side effect recorded in DECISIONS P-10): approval re-validates the
     * scope with a CALLER-scoped lookup, so an approver who cannot see the scope target cannot
     * approve a change window on it — the approval decision may not be made blind.
     */
    @Test
    public void s_13_approvalRequiresTheApproverToSeeTheScopeTarget() throws Exception {
        j.createFreeStyleProject("batch-x");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("g1")
                // a1 is the designated approver but deliberately holds NO Item/Read anywhere
                .grant(Jenkins.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg.setChangeControlEnabled(true);
        cfg.save();

        GrantRequest request = grantRequestAs("g1");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus());

        assertRejectedAsInvalid("an approver who cannot see the scope target must not be able to "
                + "approve a grant on it (P-10: approval re-validates the scope as the caller)",
                () -> {
                    try (ACLContext ignored = as("a1")) {
                        GrantRequestService.get().approve(request.getId(), "rubber stamp");
                    }
                });

        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(request.getId()).getStatus(), "the refused approval must leave the request PENDING");
        assertFalse(GrantService.get().hasActiveGrant("g1", "batch-x", Item.CONFIGURE), "no grant may have been created by the refused approval");
        assertTrue(GrantService.get().listActive().isEmpty(), "no active grant may exist at all");
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

    /**
     * A {@link JenkinsRule#createDummySecurityRealm()}-equivalent realm that counts every
     * user lookup. Used by T-SEC-14 to prove that the administrative monitor caches its scan
     * instead of re-querying the realm (potentially once per configured sid) on every
     * /manage render — the S-05 page-load DoS against LDAP-backed instances.
     */
    private static final class CountingSecurityRealm extends AbstractPasswordBasedSecurityRealm {

        private final AtomicInteger lookups = new AtomicInteger();

        @Override
        protected UserDetails authenticate2(String username, String password) {
            if (!username.equals(password)) {
                throw new BadCredentialsException(username);
            }
            return loadUserByUsername2(username);
        }

        @Override
        public UserDetails loadUserByUsername2(String username) {
            lookups.incrementAndGet();
            return new org.springframework.security.core.userdetails.User(username, "",
                    true, true, true, true,
                    Collections.singletonList(AUTHENTICATED_AUTHORITY2));
        }

        @Override
        public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) {
            throw new UsernameNotFoundException(groupname);
        }
    }

    private static void assertRejectedAsInvalid(String message, Executable action) {
        boolean rejected = false;
        try {
            action.execute();
        } catch (IllegalArgumentException | hudson.model.Failure expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got "
                    + other, other);
        }
        assertTrue(rejected, message);
    }
}
