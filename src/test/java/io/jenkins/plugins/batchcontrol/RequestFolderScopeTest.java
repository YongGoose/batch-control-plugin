package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-38a and D-38b (SPEC item 2 "Request ... per job or folder", SPEC item 2 D-38b line):
 * {@code BatchControl/Request} can be granted on a folder through a project matrix, it is checked
 * on the job for every job-specific use (submitting, viewing, cancelling and re-designating a run
 * request, requesting an activation or hold), and a user who holds Request only on some folders
 * reaches the Batch Control root and the run requests and activations sections, sees only requests
 * they may see, and after submitting lands on their request's page, never a 404 or 403. Matrix
 * rows T-05-25 .. T-05-30 (notes 187, 195).
 *
 * <p>Actors: {@code fr} holds Overall/Read globally and Item/Read + BatchControl/Request only
 * through the folder property of {@code ops}; no Item/Build, no global Request. {@code u9} is a
 * global requester (the source of a request fr must not see), {@code none} holds Overall/Read and
 * Item/Read but no Batch Control permission, {@code a1} and {@code a2} are approvers.
 *
 * <p>Submissions in these rows follow redirects (unlike {@link ApproverFormFixtures#post}), because
 * where a submission lands is part of the contract (spec-review-S5 B-1 found the 404 that a
 * redirect-off fixture could not see).
 *
 * Written from docs/SPEC.md items 2, 5 and 6a, docs/DECISIONS.md D-38a/D-38b and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RequestFolderScopeTest {

    private JenkinsRule j;
    private FreeStyleProject inside;
    private FreeStyleProject insideActivated;
    private FreeStyleProject outside;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        ProjectMatrixAuthorizationStrategy strategy = new ProjectMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        strategy.add(Jenkins.READ, PermissionEntry.user("fr"));
        for (String userId : new String[] {"u9", "none", "a1", "a2"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u9"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a2"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();

        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        inside = ops.createProject(FreeStyleProject.class, "in-x");
        insideActivated = ops.createProject(FreeStyleProject.class, "in-y");
        outside = j.createFreeStyleProject("out-x");
        for (FreeStyleProject p : new FreeStyleProject[] {inside, insideActivated, outside}) {
            setBatchControl(p, new BatchControlJobProperty(true));
        }
        BatchControlFixtures.activateAsAdmin(insideActivated);

        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                        new HashMap<Permission, Set<String>>());
        property.add(Item.READ, PermissionEntry.user("fr"));
        property.add(BatchControlPermissions.REQUEST, PermissionEntry.user("fr"));
        ops.addProperty(property);

        assertTrue(BatchControlPermissions.REQUEST.isContainedBy(PermissionScope.ITEM),
                "premise (D-38a): BatchControl/Request must be assignable per item");
        assertTrue(can("fr", inside, BatchControlPermissions.REQUEST), "premise: the folder grant covers ops/in-x");
        assertTrue(can("fr", inside, Item.READ), "premise: fr reads ops/in-x");
        assertFalse(can("fr", outside, BatchControlPermissions.REQUEST), "premise: fr holds no Request on out-x");
        assertFalse(can("fr", outside, Item.READ), "premise: fr cannot see out-x");
        assertFalse(j.jenkins.getACL().hasPermission2(User.getById("fr", true).impersonate2(), BatchControlPermissions.REQUEST),
                "premise: fr holds no global Request");
        assertFalse(can("fr", inside, Item.BUILD), "premise: fr holds no Item/Build");
    }

    /**
     * T-05-25 (D-38a; redirect followed since note 195): fr submits a run request for
     * {@code ops/in-x}: stored, requester fr, and the submission lands on a 200 page (not a 404 or
     * 403). The same submission for {@code out-x} is refused (4xx) and stores nothing.
     */
    @Test
    public void t_05_25_requestGrantedOnAFolderCoversOnlyItsJobs() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page landed = submitFollowing("fr", inside);
        String id = newRequestId(before);
        assertEquals(200, landed.getWebResponse().getStatusCode(), "the submission must land on a page fr can open, got "
                + landed.getWebResponse().getStatusCode() + " at " + landed.getUrl());
        assertEquals("ops/in-x", RunRequestService.get().load(id).getJobFullName());
        assertEquals("fr", RunRequestService.get().load(id).getRequester());

        Set<String> afterFirst = ApproverFormFixtures.runRequestIds();
        WebResponse refused = ApproverFormFixtures.submitRun(j, "fr", outside, "month-end batch", "a1");
        ApproverFormFixtures.assertClientError(refused, "a run request for a job outside the folder grant");
        assertEquals(afterFirst, ApproverFormFixtures.runRequestIds(), "the refused request must store nothing");
        j.waitUntilNoActivity();
        assertTrue(outside.getBuilds().isEmpty() && inside.getBuilds().isEmpty(), "nothing may have been built");
    }

    /**
     * T-05-26 (D-38b): with redirects followed, fr's submission lands on
     * {@code batch-control/requests/<id>/} with 200, and the page shows the request.
     */
    @Test
    public void t_05_26_submissionLandsOnTheOwnRequestPage() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page landed = submitFollowing("fr", inside);
        String id = newRequestId(before);
        assertEquals(200, landed.getWebResponse().getStatusCode(), "the landing page must answer 200, got "
                + landed.getWebResponse().getStatusCode() + " at " + landed.getUrl());
        assertTrue(landed.getUrl().getPath().endsWith("batch-control/requests/" + id + "/")
                        || landed.getUrl().getPath().endsWith("batch-control/requests/" + id),
                "the submission must land on the request's page, landed on " + landed.getUrl());
        assertTrue(landed.getWebResponse().getContentAsString().contains(id), "the page must show the request " + id);

        WebResponse direct = ApproverFormFixtures.get(j, "fr", "batch-control/requests/" + id + "/");
        assertEquals(200, direct.getStatusCode(), "fr must be able to reopen the own request");
    }

    /**
     * T-05-27 (D-38b): fr re-designates the approvers of the own request (a1 to a2) and then
     * cancels it; both succeed and take effect. Guard: none (no Batch Control permission) can do
     * neither, and the request is unchanged by those attempts.
     */
    @Test
    public void t_05_27_folderRequesterCancelsAndRedesignatesTheOwnRequest() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        submitFollowing("fr", inside);
        String id = newRequestId(before);

        WebResponse noneChange = ApproverFormFixtures.changeRunApprovers(j, "none", id, "a2");
        assertTrue(noneChange.getStatusCode() >= 400, "guard: a stranger's re-designation is refused, got " + noneChange.getStatusCode());
        WebResponse noneCancel = ApproverFormFixtures.post(j, "none", "batch-control/requests/" + id + "/cancel", new ArrayList<>());
        assertTrue(noneCancel.getStatusCode() >= 400, "guard: a stranger's cancel is refused, got " + noneCancel.getStatusCode());
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "guard: still PENDING");

        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "fr", id, "a2"),
                "fr re-designating the own request");
        RunRequest changed = RunRequestService.get().load(id);
        assertEquals(new HashSet<>(List.of("a2")), new HashSet<>(changed.getApprovers()), "the new approver set must be stored");

        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.post(j, "fr", "batch-control/requests/" + id + "/cancel",
                new ArrayList<>()), "fr cancelling the own request");
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(id).getStatus(), "the request must be CANCELLED");
    }

    /**
     * T-05-28 (D-38b): after submitting, fr gets 200 at {@code batch-control/} and at the run
     * requests section {@code batch-control/requests/}, which lists fr's own request and not u9's
     * request for {@code out-x}, a job fr cannot see.
     */
    @Test
    public void t_05_28_folderRequesterReachesRootAndRequestsSection() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        String foreign = ApproverFormFixtures.submitRunOk(j, "u9", outside, "u9 on out-x", "a1");
        before.add(foreign);
        submitFollowing("fr", inside);
        String mine = newRequestId(before);

        assertEquals(200, ApproverFormFixtures.get(j, "fr", "batch-control/").getStatusCode(),
                "fr must reach the Batch Control root page");
        WebResponse section = ApproverFormFixtures.get(j, "fr", "batch-control/requests/");
        assertEquals(200, section.getStatusCode(), "fr must reach the run requests section");
        String html = section.getContentAsString();
        assertTrue(html.contains(mine), "the section must list fr's own request " + mine);
        assertFalse(html.contains(foreign), "the section must not list u9's request for a job fr cannot see");
        assertTrue(ApproverFormFixtures.get(j, "admin", "batch-control/requests/").getContentAsString().contains(foreign),
                "guard: the foreign request exists and the administrator sees it");
    }

    /**
     * T-05-29 (D-38b, SPEC 6a): fr requests activation of {@code ops/in-x} (not activated) and a
     * hold of {@code ops/in-y} (activated); both are stored, and fr reaches the activations
     * section. The same requests for {@code out-x} are refused and store nothing.
     */
    @Test
    public void t_05_29_folderRequesterRequestsActivationAndHoldInsideOnly() throws Exception {
        assertFalse(ActivationFixtures.isActivated(inside), "premise: ops/in-x is not activated");
        assertTrue(ActivationFixtures.isActivated(insideActivated), "premise: ops/in-y is activated");

        ActivationFixtures.submitActivationOk(j, "fr", inside, "ACTIVATE", "put into service", "a1");
        ActivationFixtures.submitActivationOk(j, "fr", insideActivated, "HOLD", "pause it", "a1");
        assertEquals(200, ApproverFormFixtures.get(j, "fr", "batch-control/activations/").getStatusCode(),
                "fr must reach the activations section");

        Set<String> before = ActivationFixtures.activationIds();
        for (String action : new String[] {"ACTIVATE", "HOLD"}) {
            WebResponse refused = ActivationFixtures.submitActivation(j, "fr", outside, action, "outside", "a1");
            ApproverFormFixtures.assertClientError(refused, "an " + action + " request for out-x by fr");
        }
        assertEquals(before, ActivationFixtures.activationIds(), "the refused activation requests must store nothing");
    }

    /**
     * T-05-30 (D-38b guard): a user with no Batch Control permission anywhere and no requests of
     * their own still gets 404 at {@code batch-control/} and at the run requests section.
     */
    @Test
    public void t_05_30_rootStaysAbsentForAUserWithoutRequestOrOwnRequests() throws Exception {
        ApproverFormFixtures.submitRunOk(j, "u9", inside, "u9 on in-x", "a1"); // requests exist, none are none's
        assertEquals(404, ApproverFormFixtures.get(j, "none", "batch-control/").getStatusCode(),
                "a user without Batch Control permission and without own requests must get 404 at the root");
        assertEquals(404, ApproverFormFixtures.get(j, "none", "batch-control/requests/").getStatusCode(),
                "and at the run requests section");
    }

    /**
     * T-05-40 (D-38c, backlog #87): fr holds Request only through {@code ops} and has a run request
     * but no activation request; fr opens the activations section (200), which does not list u9's
     * activation request for {@code out-x} (a job fr cannot see). Guards: the administrator's
     * section lists it, and {@code none} (no Batch Control permission, no requests) still gets 404 at
     * the root and no 200 at the activations section.
     */
    @Test
    public void t_05_40_folderRequesterWithOnlyARunRequestOpensTheActivationsSection() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        String foreign = ActivationFixtures.submitActivationOk(j, "u9", outside, "ACTIVATE", "u9 on out-x", "a1");
        submitFollowing("fr", inside);
        newRequestId(before);

        WebResponse section = ApproverFormFixtures.get(j, "fr", "batch-control/activations/");
        assertEquals(200, section.getStatusCode(), "fr (admitted to the root by a run request) must open the activations"
                + " section (D-38c), got " + section.getStatusCode());
        assertFalse(section.getContentAsString().contains(foreign),
                "the section must not list u9's activation request for a job fr cannot see");
        assertTrue(ApproverFormFixtures.get(j, "admin", "batch-control/activations/").getContentAsString().contains(foreign),
                "guard: the foreign activation request exists and the administrator sees it");

        assertEquals(404, ApproverFormFixtures.get(j, "none", "batch-control/").getStatusCode(),
                "guard: a user without Batch Control permission and without requests gets 404 at the root");
        int noneSection = ApproverFormFixtures.get(j, "none", "batch-control/activations/").getStatusCode();
        assertTrue(noneSection >= 400, "guard: and no activations section, got " + noneSection);
    }

    // ---------------------------------------------------------------- helpers

    /** POSTs the run request form with redirects followed and returns where it landed. */
    private Page submitFollowing(String userId, FreeStyleProject job) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "month-end batch"));
        params.addAll(ApproverFormFixtures.approverPairs("a1"));
        request.setRequestParameters(params);
        return wc.getPage(request);
    }

    private static String newRequestId(Set<String> before) {
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "exactly one run request must have been stored, got " + after);
        String id = after.iterator().next();
        assertNotNull(RunRequestService.get().load(id));
        return id;
    }

    private static boolean can(String userId, Item item, Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }
}
