package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.scm.NullSCM;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.activationIds;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.approverPairs;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-16 and L3-17: the activation pages and the computed folder's
 * activation summary. Matrix rows T-GAP-357 .. T-GAP-362 (note 279).
 *
 * <p>Basis: SPEC 6a (activation requests "decided like a run request"; "the job page shows whether
 * the job is activated or on hold and links to the activation request form; pending activation and
 * hold requests appear in the approval inbox"; computed folders carry the activation, lines 130-131;
 * "the ancestor's name and state are shown only to viewers with Item/Read on it", line 134); SPEC 3
 * "결재자 변경 시 요청 이력에 (이전 결재자, 새 결재자, 변경자, 시각)이 남는다" and "목록에 없는 사용자 ... 거부";
 * SPEC 8 line 175 / D-66 (lists: pending, active, ended); SPEC 6 usability (a message next to the
 * field, input kept; every link shown only to users who can use it).
 *
 * <p>Screen contract (note 96): the state is read by wording ("not activated" / "on hold" versus
 * "activated"), the request link by its target {@code batch-control-activation}. The lists use
 * {@code data-batch-control-list=active|ended} (D-66, as on the grants page). "Superseded" is the
 * screen's word for an approved ACTIVATE that a later HOLD replaced; no document names it, so the row
 * asserts only that it is no longer among the active rows (note 279).
 *
 * <p>Run control on; r holds Overall/Read, Item/Read and BatchControl/Request; v holds Overall/Read
 * and Item/Read only; a1 and a2 (approvers) hold Approve.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-37, D-46 and D-66 and docs/ARCHITECTURE.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class ActivationPageGapTest {

    private static final String KEPT_REASON = "kept-activation-reason-l3";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ).everywhere().to("v")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    // ------------------------------------------------------------------ L3-16

    /**
     * T-GAP-357 (L3-16; SPEC 8 line 175 / D-66 "pending requests first, then active and ended items",
     * SPEC 6a "rejection changes nothing"): on job {@code k}, an ACTIVATE request a1 rejected is among
     * the ended rows of {@code batch-control/activations/} with the status REJECTED; an approved
     * ACTIVATE is among the active rows (guard); after an approved HOLD on {@code k}, the HOLD is among
     * the active rows and the earlier ACTIVATE is no longer active but among the ended rows.
     */
    @Test
    public void t_gap_357_activationsListPlacesRejectedAndReplacedRequestsAmongTheEnded() throws Exception {
        FreeStyleProject k = job("k");
        String rejected = submitActivationOk(j, "r", k, "ACTIVATE", "first try", "a1");
        assertSuccess(decideActivation(j, "a1", rejected, "reject", "not yet"), "fixture: a1 rejects");
        String activate = submitActivationOk(j, "r", k, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", activate, "approve", "ok"), "fixture: a1 approves the ACTIVATE");

        HtmlPage before = UsabilityFixtures.htmlPage(j, "r", "batch-control/activations/");
        DomElement rejectedRow = row(before, WindowStateFixtures.ENDED_LIST, rejected);
        assertNotNull(rejectedRow, "the rejected ACTIVATE is among the ended rows: " + excerpt(main(before)));
        assertTrue(rejectedRow.asNormalizedText().contains("REJECTED"), "the ended row says REJECTED: " + rejectedRow.asNormalizedText());
        assertNull(row(before, WindowStateFixtures.ACTIVE_LIST, rejected), "the rejected ACTIVATE is not among the active rows");
        assertNotNull(row(before, WindowStateFixtures.ACTIVE_LIST, activate), "guard: the approved ACTIVATE in effect is among the active rows: "
                + excerpt(main(before)));

        String hold = submitActivationOk(j, "r", k, "HOLD", "pause", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "fixture: a1 approves the HOLD");
        HtmlPage after = UsabilityFixtures.htmlPage(j, "r", "batch-control/activations/");
        assertNotNull(row(after, WindowStateFixtures.ACTIVE_LIST, hold), "the approved HOLD in effect is among the active rows: " + excerpt(main(after)));
        assertNull(row(after, WindowStateFixtures.ACTIVE_LIST, activate), "the ACTIVATE replaced by the HOLD is no longer among the active rows: "
                + excerpt(main(after)));
        assertNotNull(row(after, WindowStateFixtures.ENDED_LIST, activate), "the replaced ACTIVATE is among the ended rows: " + excerpt(main(after)));
    }

    /**
     * T-GAP-358 (L3-16; SPEC 3 approver changes recorded as (previous set, new set, changed by, time),
     * SPEC 6 usability): r, the requester of a pending ACTIVATE designating a1, POSTs
     * {@code batch-control/activations/<id>/changeApprover} with {@code a2}: the request page shows the
     * change with a1, a2 and r. Then with no approver and with 51 approvers (a2 and 50 ids not on the
     * list): each is refused (4xx) with a message inside the approvers' form item, and the designated set
     * stays {@code [a2]} with no further change recorded.
     */
    @Test
    public void t_gap_358_activationApproverChangeIsShownAndBadSetsAreRefusedOnTheField() throws Exception {
        FreeStyleProject k = job("k");
        String id = submitActivationOk(j, "r", k, "ACTIVATE", "go live", "a1");
        assertSuccess(ApproverFormFixtures.post(j, "r", "batch-control/activations/" + id + "/changeApprover", approverPairs("a2")),
                "r changes the approvers to a2");
        ActivationRequest changed = ActivationService.get().load(id);
        assertEquals(List.of("a2"), changed.getApprovers(), "the set is now a2");
        HtmlPage page = UsabilityFixtures.htmlPage(j, "r", "batch-control/activations/" + id + "/");
        DomElement change = changeRow(page);
        assertNotNull(change, "SPEC 3: the request page shows the approver change: " + excerpt(main(page)));
        String text = change.asNormalizedText();
        assertTrue(text.contains("a1") && text.contains("a2") && text.contains("r"),
                "SPEC 3: the change names the previous set a1, the new set a2 and r who changed it: " + text);

        String[] many = new String[51];
        many[0] = "a2";
        for (int i = 1; i < many.length; i++) {
            many[i] = String.format(Locale.ROOT, "l3-approver-%02d", i);
        }
        for (String[] set : List.of(new String[0], many)) {
            String what = "r's change of the activation request to " + set.length + " approver(s)";
            Page refused = new GrantPageGapTestDriver(j).postPage("r", "batch-control/activations/" + id + "/changeApprover", approverPairs(set));
            GrantPageGapTest.assertFormRefusal(refused, what, "batch-control/activations/" + id + "/changeApprover", new String[] {"approvers"}, null);
            ActivationRequest reloaded = ActivationService.get().load(id);
            assertEquals(List.of("a2"), reloaded.getApprovers(), what + ": the set is unchanged");
            assertEquals(1, reloaded.getApproverChanges().size(), what + ": no further change is recorded");
        }
    }

    /**
     * T-GAP-359 (L3-16; SPEC 6a "pending activation and hold requests appear", SPEC 3, SPEC 6 usability):
     * the activation form of job {@code k} ({@code job/k/batch-control-activation/}) lists k's pending
     * ACTIVATE request (linked by its id) with its action; submitting the form with 51 approvers (a1 and
     * 50 ids not on the list) is refused (4xx) with a message inside the approvers' form item, the typed
     * reason kept, and no activation request is created. Guard: the same form with a1 alone creates one.
     */
    @Test
    public void t_gap_359_activationFormListsThePendingRequestAndRefusesBadApproverSets() throws Exception {
        FreeStyleProject k = job("k");
        String pending = submitActivationOk(j, "r", k, "ACTIVATE", "go live", "a1");
        HtmlPage formPage = UsabilityFixtures.htmlPage(j, "r", k.getUrl() + "batch-control-activation/");
        assertEquals(200, formPage.getWebResponse().getStatusCode(), "r opens k's activation form");
        assertTrue(UsabilityFixtures.hasLinkTo(j, formPage, "batch-control/activations/" + pending + "/")
                        || formPage.getWebResponse().getContentAsString().contains("activations/" + pending),
                "SPEC 6a: the form lists k's pending request: " + excerpt(main(formPage)));
        assertTrue(main(formPage).contains("ACTIVATE"), "the pending request is shown with its action: " + excerpt(main(formPage)));
        assertSuccess(decideActivation(j, "a1", pending, "reject", "not yet"), "fixture: the pending request is decided");

        String[] many = new String[51];
        many[0] = "a1";
        for (int i = 1; i < many.length; i++) {
            many[i] = String.format(Locale.ROOT, "l3-approver-%02d", i);
        }
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("action", "ACTIVATE"));
        params.add(new NameValuePair("reason", KEPT_REASON));
        params.addAll(approverPairs(many));
        Set<String> before = activationIds();
        Page refused = new GrantPageGapTestDriver(j).postPage("r", k.getUrl() + "batch-control-activation/submit", params);
        assertEquals(before, activationIds(), "nothing may be created");
        GrantPageGapTest.assertFormRefusal(refused, "the activation form with 51 approvers", k.getUrl() + "batch-control-activation/submit",
                new String[] {"approvers"}, KEPT_REASON);
        String created = submitActivationOk(j, "r", k, "ACTIVATE", KEPT_REASON, "a1");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(created).getStatus(), "guard: the valid form creates a PENDING request");
    }

    // ------------------------------------------------------------------ L3-17

    /**
     * T-GAP-360 (L3-17; SPEC 6a lines 130-131 "the ACTIVATE/HOLD request is made on it" and "the job page
     * shows whether the job is activated ... and links to the activation request form", SPEC 6 usability
     * "every button, link and form is shown only to users who can use it"): the multibranch project
     * {@code mb}, created while run control is on, as r: its page says it is not activated and links to
     * its activation form; after r submits an ACTIVATE request on it, r's view of its activation form
     * lists that request. v (Item/Read only) sees the state on {@code mb}'s page, but no link to the
     * activation form and nowhere the pending request's id.
     */
    @Test
    public void t_gap_360_computedFolderPageShowsStateAndRequestLinkToRequestersOnly() throws Exception {
        WorkflowMultiBranchProject mb = multibranch("mb");
        String forR = page("r", mb.getUrl());
        assertTrue(notInService(forR), "SPEC 6a: mb's page says it is not activated: " + excerpt(forR));
        assertTrue(forR.contains("batch-control-activation"), "SPEC 6a: mb's page links r to its activation form");

        String id = submitActivationOk(j, "r", mb, "ACTIVATE", "put mb into service", "a1");
        String form = page("r", mb.getUrl() + "batch-control-activation/");
        assertTrue(form.contains(id), "r's view of mb's activation form lists the pending request " + id + ": " + excerpt(form));

        String forV = page("v", mb.getUrl());
        assertTrue(notInService(forV), "SPEC 6a line 134: v (Item/Read) sees mb's state: " + excerpt(forV));
        assertFalse(forV.contains("batch-control-activation"), "SPEC 6 usability: v, who may not request, gets no link to the activation form");
        assertFalse(forV.contains(id), "v sees nowhere the pending request " + id);
        WebResponse vForm = ApproverFormFixtures.get(j, "v", mb.getUrl() + "batch-control-activation/");
        assertTrue(vForm.getStatusCode() >= 400 || !vForm.getContentAsString().contains(id),
                "v is not offered the activation form or its pending list, got HTTP " + vForm.getStatusCode());
    }

    /**
     * T-GAP-361 (L3-17; SPEC 6a line 134 "Where an item's activation is carried by a computed-folder
     * ancestor, the ancestor's name and state are shown only to viewers with Item/Read on it"): r, who
     * may read {@code mb}, opens the page of its branch job {@code mb/main}: it names {@code mb} and says
     * that it is not activated; after an approved ACTIVATE on {@code mb}, the same page says activated
     * and no longer "not activated".
     */
    @Test
    public void t_gap_361_branchJobPageNamesItsComputedFolderAndItsState() throws Exception {
        WorkflowMultiBranchProject mb = multibranch("mb");
        WorkflowJob branch = index(mb);
        String before = mainOf("r", branch.getUrl());
        assertTrue(before.replace("mb/main", "").contains("mb") && notInService(before), "SPEC 6a line 134: the branch's page names mb and its state (not activated): "
                + excerpt(before));
        String id = submitActivationOk(j, "r", mb, "ACTIVATE", "put mb into service", "a1");
        assertSuccess(decideActivation(j, "a1", id, "approve", "ok"), "fixture: a1 approves");
        String after = mainOf("r", branch.getUrl());
        assertTrue(after.toLowerCase(Locale.ROOT).contains("activated") && !notInService(after),
                "the branch's page reflects mb's new state (activated): " + excerpt(after));
    }

    /**
     * T-GAP-362 (L3-17; SPEC 6a "Approval marks the job activated", "An approved hold marks the job not
     * activated", "the job page shows whether the job is activated or on hold and links to the activation
     * request form"): after a1 approves an ACTIVATE on {@code mb}, its page says activated (not "not
     * activated", not "on hold") and still links to the activation form, which now offers HOLD; after an
     * approved HOLD, the page says it is not in service again.
     */
    @Test
    public void t_gap_362_computedFolderPageFollowsActivationAndHold() throws Exception {
        WorkflowMultiBranchProject mb = multibranch("mb");
        String id = submitActivationOk(j, "r", mb, "ACTIVATE", "put mb into service", "a1");
        assertSuccess(decideActivation(j, "a1", id, "approve", "ok"), "fixture: a1 approves the ACTIVATE");
        assertTrue(ActivationService.get().isActivated(mb), "premise: mb is activated");
        String activated = page("r", mb.getUrl());
        assertTrue(activated.toLowerCase(Locale.ROOT).contains("activated") && !notInService(activated),
                "mb's page says it is activated: " + excerpt(activated));
        assertTrue(activated.contains("batch-control-activation"), "mb's page still links to the activation form");
        String form = mainOf("r", mb.getUrl() + "batch-control-activation/");
        assertTrue(form.contains("HOLD"), "the activation form of an activated mb offers HOLD: " + excerpt(form));

        String hold = submitActivationOk(j, "r", mb, "HOLD", "pause mb", "a1");
        assertSuccess(decideActivation(j, "a1", hold, "approve", "ok"), "fixture: a1 approves the HOLD");
        assertFalse(ActivationService.get().isActivated(mb), "premise: mb is on hold");
        assertTrue(notInService(page("r", mb.getUrl())), "mb's page says it is not in service after the HOLD");
    }

    // ------------------------------------------------------------------ helpers

    /** A run-controlled job created under run control (not activated). */
    private FreeStyleProject job(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        setBatchControl(job, cleared);
        assertFalse(ActivationService.get().isActivated(job), "fixture: " + name + " starts not activated");
        return job;
    }

    private WorkflowMultiBranchProject multibranch(String name) throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, name);
        assertFalse(ActivationService.get().isActivated(mb), "fixture: a computed folder created under run control starts not activated");
        return mb;
    }

    private WorkflowJob index(WorkflowMultiBranchProject mb) throws Exception {
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        Queue.Item indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "fixture: branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "fixture: indexing must have created the branch job");
        return branch;
    }

    private static boolean notInService(String page) {
        String lower = page.toLowerCase(Locale.ROOT);
        return lower.contains("not activated") || lower.contains("on hold");
    }

    private String page(String userId, String path) throws Exception {
        WebResponse response = ApproverFormFixtures.get(j, userId, path);
        assertEquals(200, response.getStatusCode(), userId + " opens " + path);
        return response.getContentAsString();
    }

    private String mainOf(String userId, String path) throws Exception {
        HtmlPage page = UsabilityFixtures.htmlPage(j, userId, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), userId + " opens " + path);
        return main(page);
    }

    private static String main(HtmlPage page) {
        return WindowStateFixtures.mainPanel(page).asNormalizedText();
    }

    /** The row of {@code selector} that links to {@code <id>/} (the activations page links its rows relatively). */
    private DomElement row(HtmlPage page, String selector, String id) throws Exception {
        String path = new URL(j.getURL(), "batch-control/activations/" + id + "/").getPath();
        for (DomNode table : page.querySelectorAll(selector)) {
            for (DomNode n : table.querySelectorAll("tr")) {
                for (DomElement a : ((DomElement) n).getElementsByTagName("a")) {
                    if (a.hasAttribute("href") && page.getFullyQualifiedUrl(a.getAttribute("href")).getPath().equals(path)) {
                        return (DomElement) n;
                    }
                }
            }
        }
        return null;
    }

    /** The body row of the table that follows a heading saying "Approver Changes" (or any row naming a change), or null. */
    private static DomElement changeRow(HtmlPage page) {
        for (DomNode table : WindowStateFixtures.mainPanel(page).querySelectorAll("table")) {
            String head = table.asNormalizedText().toLowerCase(Locale.ROOT);
            if (head.contains("changed by") || head.contains("from")) {
                for (DomNode n : table.querySelectorAll("tbody tr")) {
                    return (DomElement) n;
                }
            }
        }
        return null;
    }

    /** POST helper shared with {@link GrantPageGapTest} (same rule instance). */
    private static final class GrantPageGapTestDriver {
        private final JenkinsRule j;

        GrantPageGapTestDriver(JenkinsRule j) {
            this.j = j;
        }

        Page postPage(String userId, String path, List<NameValuePair> params) throws Exception {
            JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
            wc.getOptions().setJavaScriptEnabled(false);
            org.htmlunit.WebRequest request = new org.htmlunit.WebRequest(wc.createCrumbedUrl(path), org.htmlunit.HttpMethod.POST);
            request.setRequestParameters(new ArrayList<>(params));
            request.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            return wc.getPage(request);
        }
    }
}
