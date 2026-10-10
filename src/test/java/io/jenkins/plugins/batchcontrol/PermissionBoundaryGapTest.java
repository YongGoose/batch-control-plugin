package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Permission boundaries the earlier rows covered only in part (coverage inventory section 3):
 * SPEC item 2 (#31: "a user who holds none of the Batch Control permissions ... {@code /batch-control/}
 * and every URL beneath it answer 404"; "the per-job request action ... is absent, not merely
 * refused, for a user who may not use it ... its URL and every URL beneath it answer 404"),
 * item 3 (D-29/D-37: only a designated approver decides, "a listed approver or an administrator
 * cannot decide instead"), item 7 ("cancelling is possible only for the requester or a Manage
 * holder") and section 6 (every state change is a POST with a permission check). Coverage
 * inventory G-M10, G-L10, G-L11 and G-L12; matrix rows T-SEC-77, T-02-124, T-02-125, T-03-27 and
 * T-07-11 (note 269).
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request), {@code a1}
 * approver (Item/Read, Approve), {@code m1} (Item/Read, BatchControl/Manage only), {@code v1}
 * (Item/Read, ViewHistory only), {@code g2}
 * (Item/Read, RequestGrant), {@code nobc} (Item/Read, Item/Build, no Batch Control permission),
 * anonymous (Overall/Read and Item/Read granted to anonymous), {@code admin}. Run control and
 * change control on; the approval-required job {@code batch-x}.
 *
 * <p>Written from docs/SPEC.md items 2, 3, 7 and 8 and section 6 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class PermissionBoundaryGapTest {

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("v1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("g2")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ).everywhere().to("anonymous"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    /**
     * T-SEC-77 (G-M10): anonymous, granted Overall/Read and Item/Read, holds no Batch Control
     * permission. Every Batch Control GET (the root and its sections, a request's detail page, the
     * job's Request Run page and dialog fragment) answers 404, and so does every state-changing POST
     * with a crumb (a run request submission, an approval, a grant request, an incident
     * acknowledgement); nothing is stored or changed, and the job page links neither the root nor
     * the job's Request Run page. Guard: u1 opens the root and the Request Run page (200).
     */
    @Test
    public void t_sec_77_anonymousWithReadAccessFindsNoBatchControlSurface() throws Exception {
        String requestId = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "a1");
        Incident incident = openIncident();
        Set<String> runsBefore = ApproverFormFixtures.runRequestIds();
        Set<String> grantsBefore = ApproverFormFixtures.grantRequestIds();

        for (String path : new String[] {"batch-control/", "batch-control/requests/", "batch-control/activations/",
                "batch-control/grants/", "batch-control/history/", "batch-control/dashboard/", "batch-control/changes/",
                "batch-control/incidents/", "batch-control/requests/" + requestId + "/", job.getUrl() + "batch-control/",
                job.getUrl() + "batch-control/dialog"}) {
            assertEquals(404, ApproverFormFixtures.get(j, null, path).getStatusCode(), "anonymous GET " + path + " must answer 404");
        }
        List<NameValuePair> submit = new ArrayList<>();
        submit.add(new NameValuePair("reason", "anonymous run"));
        submit.addAll(ApproverFormFixtures.approverPairs("a1"));
        assertEquals(404, ApproverFormFixtures.post(j, null, job.getUrl() + "batch-control/submit", submit).getStatusCode(),
                "an anonymous run request submission must answer 404");
        assertEquals(404, ApproverFormFixtures.decideRun(j, null, requestId, "approve", "anonymous").getStatusCode(),
                "an anonymous approval must answer 404");
        assertEquals(404, ApproverFormFixtures.submitGrant(j, null, "batch-x", List.of("CONFIGURE"), 30, "anonymous", null, "a1")
                .getStatusCode(), "an anonymous grant request must answer 404");
        assertEquals(404, ApproverFormFixtures.post(j, null, "batch-control/incidents/" + incident.getId() + "/acknowledge",
                List.of(new NameValuePair("comment", "anonymous"))).getStatusCode(), "an anonymous acknowledgement must answer 404");

        assertEquals(runsBefore, ApproverFormFixtures.runRequestIds(), "no run request may be created");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(requestId).getStatus(), "the request must stay PENDING");
        assertEquals(grantsBefore, ApproverFormFixtures.grantRequestIds(), "no grant request may be created");
        assertEquals(IncidentStatus.OPEN, IncidentService.get().load(incident.getId()).getStatus(), "the incident must stay OPEN");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing may run");

        HtmlPage page = UsabilityFixtures.htmlPage(j, null, job.getUrl());
        assertEquals(200, page.getWebResponse().getStatusCode(), "premise: anonymous reads the job page");
        List<String> hrefs = UsabilityFixtures.resolvedHrefs(page);
        for (String target : new String[] {"batch-control", job.getUrl() + "batch-control"}) {
            assertFalse(hrefs.contains(UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), target).toExternalForm())),
                    "the anonymous job page must not link " + target);
        }
        assertEquals(200, ApproverFormFixtures.get(j, "u1", "batch-control/").getStatusCode(), "guard: u1 opens the root");
        assertEquals(200, ApproverFormFixtures.get(j, "u1", job.getUrl() + "batch-control/").getStatusCode(),
                "guard: u1 opens the Request Run page");
    }

    /**
     * T-02-125 (G-L10): a1 (Approve only) and v1 (ViewHistory only) hold a Batch Control permission
     * but not Request (premise, through the job's ACL): the job's Request Run page, its dialog
     * fragment and its submit endpoint answer 404 to them (absent, not refused), nothing is stored,
     * and their job page does not link the Request Run page. Guard: u1 opens it (200) and is offered
     * the link. (BatchControl/Manage implies BatchControl/Request, so a Manage holder is a requester
     * and is not part of this row; note 269.)
     */
    @Test
    public void t_02_125_approverAndViewerFindNoRequestRunAction() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        String requestRun = UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), job.getUrl() + "batch-control").toExternalForm());
        for (String user : new String[] {"a1", "v1"}) {
            assertFalse(job.getACL().hasPermission2(User.getById(user, true).impersonate2(), BatchControlPermissions.REQUEST),
                    "premise: " + user + " holds no Request on the job");
            assertEquals(404, ApproverFormFixtures.get(j, user, job.getUrl() + "batch-control/").getStatusCode(),
                    user + " must get 404 on the Request Run page");
            assertEquals(404, ApproverFormFixtures.get(j, user, job.getUrl() + "batch-control/dialog").getStatusCode(),
                    user + " must get 404 on the dialog fragment");
            assertEquals(404, ApproverFormFixtures.submitRun(j, user, job, "not mine to request", "a1").getStatusCode(),
                    user + " must get 404 on the submit endpoint");
            assertFalse(UsabilityFixtures.resolvedHrefs(UsabilityFixtures.htmlPage(j, user, job.getUrl())).contains(requestRun),
                    user + "'s job page must not link the Request Run page");
        }
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertEquals(200, ApproverFormFixtures.get(j, "u1", job.getUrl() + "batch-control/").getStatusCode(),
                "guard: u1 opens the Request Run page");
        assertTrue(UsabilityFixtures.resolvedHrefs(UsabilityFixtures.htmlPage(j, "u1", job.getUrl())).contains(requestRun),
                "guard: u1's job page links the Request Run page");
    }

    /**
     * T-02-124 (G-L11): nobc (no Batch Control permission) gets 404 for {@code batch-control/changes/},
     * {@code batch-control/grants/new}, the revoke POST of g2's active window and the approve POST of
     * u1's pending activation request; the window stays active and the activation request PENDING.
     * Guard: a1 approves the activation request and m1 revokes the window through the same
     * endpoints.
     */
    @Test
    @Tag("core")
    public void t_02_124_userWithoutBatchControlPermissionGets404OnTheRemainingEndpoints() throws Exception {
        Grant grant = openWindow("g2", "batch-x");
        String activation = ActivationFixtures.submitActivationOk(j, "u1", job, "ACTIVATE", "go live", "a1");

        assertEquals(404, ApproverFormFixtures.get(j, "nobc", "batch-control/changes/").getStatusCode(), "changes/ must answer 404");
        assertEquals(404, ApproverFormFixtures.get(j, "nobc", "batch-control/grants/new").getStatusCode(), "grants/new must answer 404");
        assertEquals(404, ApproverFormFixtures.post(j, "nobc", "batch-control/grants/active/" + grant.getId() + "/revoke", List.of())
                .getStatusCode(), "the revoke POST must answer 404");
        assertEquals(404, ActivationFixtures.decideActivation(j, "nobc", activation, "approve", "not mine").getStatusCode(),
                "the activation approve POST must answer 404");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> grant.getId().equals(g.getId())), "the window must stay active");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(activation).getStatus(), "the activation request must stay PENDING");

        assertSuccess(ActivationFixtures.decideActivation(j, "a1", activation, "approve", "ok"), "guard: a1 approves the activation");
        assertTrue(ActivationService.get().isActivated(job), "guard: the job is activated");
        assertSuccess(ApproverFormFixtures.post(j, "m1", "batch-control/grants/active/" + grant.getId() + "/revoke", List.of()),
                "guard: m1 revokes the window");
        assertTrue(GrantService.get().listActive().stream().noneMatch(g -> grant.getId().equals(g.getId())), "guard: the window is revoked");
    }

    /**
     * T-03-27 (G-L12): u1's PENDING run request designates a1. m1 (BatchControl/Manage, not a
     * designated approver, no Approve) POSTs approve and reject: both 4xx, the request stays
     * PENDING with no decider, nothing runs. Guard: a1 approves and the build runs once.
     */
    @Test
    @Tag("core")
    public void t_03_27_managerCannotDecideAPendingRunRequest() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "a1");
        assertClientError(ApproverFormFixtures.decideRun(j, "m1", id, "approve", "manager approves"), "m1's approval");
        assertClientError(ApproverFormFixtures.decideRun(j, "m1", id, "reject", "manager rejects"), "m1's rejection");
        RunRequest pending = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, pending.getStatus(), "the request must stay PENDING");
        assertNull(pending.getDecidedBy(), "nobody has decided the request");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing may run");

        assertSuccess(ApproverFormFixtures.decideRun(j, "a1", id, "approve", "ok"), "guard: a1's approval");
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "guard: the approved request runs once");
        assertEquals("a1", RunRequestService.get().load(id).getDecidedBy());
    }

    /**
     * T-07-11 (G-L12): u1's PENDING run request designates a1. a1 (the designated approver, neither
     * the requester nor a Manage holder) POSTs cancel: 4xx, the request stays PENDING. Guard: u1
     * cancels it (CANCELLED).
     */
    @Test
    @Tag("core")
    public void t_07_11_designatedApproverCannotCancelSomeoneElsesRequest() throws Exception {
        String id = ApproverFormFixtures.submitRunOk(j, "u1", job, "month-end batch", "a1");
        assertClientError(ApproverFormFixtures.post(j, "a1", "batch-control/requests/" + id + "/cancel", List.of()),
                "a1's cancel of u1's request");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "the request must stay PENDING");

        assertSuccess(ApproverFormFixtures.post(j, "u1", "batch-control/requests/" + id + "/cancel", List.of()), "guard: u1's cancel");
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(id).getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private Incident openIncident() throws Exception {
        FreeStyleProject failing = uncontrolled(j.createFreeStyleProject("fails"));
        failing.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(failing); // D-46: the cause-less fixture build needs an activation
        j.assertBuildStatus(Result.FAILURE, failing.scheduleBuild2(0));
        j.waitUntilNoActivity();
        return RerunFallbackFixtures.incidentFor("fails#1");
    }

    private static Grant openWindow(String user, String fullName) {
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    List.of(GrantAction.CONFIGURE), 30, "maintenance of " + fullName, "a1");
        }
        Grant grant;
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant, "fixture: the approval must open a window");
        return grant;
    }
}
