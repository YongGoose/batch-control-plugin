package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HTTP surface of the grant screens. Matrix rows T-08-13 (grant request creation without
 * RequestGrant is 403) and the T-SEC-06 remainder (GET on revoke is rejected), plus the
 * fixed endpoint contract for grants: GET batch-control/grants/ list (REQUEST_GRANT or
 * APPROVE or MANAGE), POST grants/&lt;id&gt;/approve|reject|cancel, POST
 * grants/active/&lt;grantId&gt;/revoke (MANAGE only).
 *
 * Written from docs/SPEC.md, docs/TEST-MATRIX.md and the S3 endpoint contract only.
 */
public class GrantWebTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FreeStyleProject job;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy delegate = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1")
                .grant(Jenkins.READ, Item.READ).everywhere().to("u0");
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(delegate));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
    }

    /** T-08-13: a user without BatchControl/RequestGrant gets 403 on the grant request creation POST. */
    @Test
    public void t_08_13_createGrantRequestWithoutPermissionIs403() throws Exception {
        JenkinsRule.WebClient wc = webClient().login("u0");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/grants/create"), HttpMethod.POST));
        assertEquals("a user without RequestGrant must get 403 on grant request creation",
                403, page.getWebResponse().getStatusCode());
        assertTrue("no grant request may be stored after the rejected POST",
                GrantRequestService.get().list().isEmpty());
    }

    /** T-SEC-06 (remainder): GET on the revoke endpoint is rejected; the grant stays active. */
    @Test
    public void t_sec_06_getRevokeIsRejected() throws Exception {
        Grant grant = activeGrant();

        JenkinsRule.WebClient wc = webClient().login("m1");
        Page page = wc.getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/grants/active/" + grant.getId() + "/revoke"),
                HttpMethod.GET));
        int code = page.getWebResponse().getStatusCode();
        assertTrue("GET must never revoke; expected 405/rejected but got " + code, code >= 400);

        assertTrue("the grant must still be active after the rejected GET",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        assertTrue(GrantService.get().listActive().stream()
                .anyMatch(g -> g.getId().equals(grant.getId())));
    }

    /** Revoke is MANAGE-only: a POST by a non-Manage user is 403 and changes nothing. */
    @Test
    public void revokePostWithoutManageIs403() throws Exception {
        Grant grant = activeGrant();

        for (String userId : new String[] {"u1", "a1", "u0"}) {
            JenkinsRule.WebClient wc = webClient().login(userId);
            Page page = wc.getPage(new WebRequest(
                    wc.createCrumbedUrl("batch-control/grants/active/" + grant.getId() + "/revoke"),
                    HttpMethod.POST));
            assertEquals(userId + " must not be able to revoke (MANAGE only)",
                    403, page.getWebResponse().getStatusCode());
        }
        assertTrue("the grant must still be active",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
    }

    /** T-08-05 (web layer): a Manage holder's revoke POST works and leaves the GRANT_REVOKE record. */
    @Test
    public void t_08_05_revokePostByManageHolderWorks() throws Exception {
        Grant grant = activeGrant();

        JenkinsRule.WebClient wc = webClient().login("m1");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/grants/active/" + grant.getId() + "/revoke"),
                HttpMethod.POST));
        assertTrue("the Manage holder's revoke POST must succeed, got HTTP "
                + page.getWebResponse().getStatusCode(),
                page.getWebResponse().getStatusCode() < 400);

        assertFalse("the grant must be inactive immediately after the revoke",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        assertTrue("revocation must leave a ChangeRecord(GRANT_REVOKE)",
                FileStore.get().listChangeRecords(YearMonth.now()).stream()
                        .anyMatch(rec -> rec.getType() == ChangeType.GRANT_REVOKE
                                && "m1".equals(rec.getUser())));
    }

    /** The grants screen requires one of RequestGrant/Approve/Manage (StaplerProxy gate). */
    @Test
    public void grantsListPermissionGate() throws Exception {
        activeGrant(); // some content to list

        for (String allowed : new String[] {"u1", "a1", "m1", "admin"}) {
            Page page = webClient().login(allowed).getPage(new WebRequest(
                    new URL(j.getURL(), "batch-control/grants/"), HttpMethod.GET));
            assertEquals(allowed + " must be able to open the grants screen",
                    200, page.getWebResponse().getStatusCode());
        }
        Page denied = webClient().login("u0").getPage(new WebRequest(
                new URL(j.getURL(), "batch-control/grants/"), HttpMethod.GET));
        assertEquals("a user with none of RequestGrant/Approve/Manage must get 403",
                403, denied.getWebResponse().getStatusCode());
    }

    /** The approve endpoint of the contract works: POST by the designated approver creates the grant. */
    @Test
    public void approvePostCreatesActiveGrant() throws Exception {
        GrantRequest request = pendingRequest();

        JenkinsRule.WebClient wc = webClient().login("a1");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/grants/" + request.getId() + "/approve"),
                HttpMethod.POST));
        assertTrue("the designated approver's POST must succeed, got HTTP "
                + page.getWebResponse().getStatusCode(),
                page.getWebResponse().getStatusCode() < 400);

        assertEquals(RequestStatus.APPROVED,
                GrantRequestService.get().load(request.getId()).getStatus());
        assertTrue("approval must make the grant effective immediately",
                GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
    }

    /** The cancel endpoint of the contract works: the requester cancels their PENDING grant request. */
    @Test
    public void cancelPostByRequesterWorks() throws Exception {
        GrantRequest request = pendingRequest();

        JenkinsRule.WebClient wc = webClient().login("u1");
        Page page = wc.getPage(new WebRequest(
                wc.createCrumbedUrl("batch-control/grants/" + request.getId() + "/cancel"),
                HttpMethod.POST));
        assertTrue("the requester's cancel POST must succeed, got HTTP "
                + page.getWebResponse().getStatusCode(),
                page.getWebResponse().getStatusCode() < 400);

        assertEquals(RequestStatus.CANCELLED,
                GrantRequestService.get().load(request.getId()).getStatus());
        assertFalse(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient() {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private GrantRequest pendingRequest() {
        try (ACLContext ignored = as("u1")) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "batch-x"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "a1");
        }
    }

    private Grant activeGrant() {
        GrantRequest request = pendingRequest();
        try (ACLContext ignored = as("a1")) {
            return GrantRequestService.get().approve(request.getId(), "ok");
        }
    }
}
