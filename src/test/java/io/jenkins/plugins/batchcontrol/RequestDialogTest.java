package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-66 (hosting review round 3, R4-9, R4-14): "requesting a permission window (from the Batch
 * Control grants page, a job page or a folder page) and requesting a run (the Request Run action)
 * open a dialog on the current page; submitting it creates the request and leads to its detail
 * page ... A refused direct build still leads to the pre-filled Request Run page (D-60)." Matrix
 * rows T-UI-98..101, T-UI-104, T-UI-105 (notes 247, 249).
 *
 * <p>HtmlUnit is run without JavaScript, so the dialog itself is not opened here: the rows test the
 * server side of it. A page offers an entry when some element outside the tab bar names (in
 * {@code href} or a {@code data-*} attribute) a same-instance URL whose GET renders the request
 * form posting to the existing endpoint ({@code batch-control/grants/create},
 * {@code <job>/batch-control/submit}); a dialog loads its content from such a URL. The submission
 * to that endpoint must answer with a redirect to the new request's detail page. That the dialog
 * opens on the current page and follows the redirect is left to e2e.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-66/D-60 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequestDialogTest {

    static final String GRANT_CREATE = "batch-control/grants/create";

    private JenkinsRule j;
    private FreeStyleProject job;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "g1", "n1", "a1", "p0"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("g1"));
        strategy.add(BatchControlPermissions.VIEW_HISTORY, PermissionEntry.user("n1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("batch-x");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));
        team = j.jenkins.createProject(Folder.class, "team");
    }

    /**
     * T-UI-98: the grants page, a job page and a folder page (R4-14) each offer a grant request
     * entry whose content is the grant request form posting to {@code grants/create}; from the job
     * page it is pre-filled with the job, from the folder page with the folder. Since D-71 there is
     * no scope type: no form carries a {@code scopeType} control (before D-71 the folder form had
     * to select FOLDER and the select had to offer JOB, FOLDER and FOLDER_ONLY; note 260). Guards:
     * a user without BatchControl/RequestGrant is offered no grant form from the folder page, and
     * with change control off neither is the requester.
     */
    @Test
    public void t_ui_98_grantRequestEntriesOnGrantsJobAndFolderPages() throws Exception {
        HtmlPage grants = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/");
        assertEquals(200, grants.getWebResponse().getStatusCode(), "premise: g1 opens the grants page");
        assertFalse(entries("g1", grants).isEmpty(), "the grants page must offer an entry whose content is the grant request form;"
                + " targets were " + RequestPageFixtures.entryTargets(j, grants));

        Map<URL, HtmlForm> fromJob = entries("g1", UsabilityFixtures.htmlPage(j, "g1", "job/batch-x/"));
        assertFalse(fromJob.isEmpty(), "the job page must offer a grant request entry to a RequestGrant holder");
        assertTrue(fromJob.values().stream().anyMatch(f -> "batch-x".equals(scopeFullName(f))),
                "the job page's grant form must be pre-filled with the job: " + scopes(fromJob));

        HtmlPage folderPage = UsabilityFixtures.htmlPage(j, "g1", "job/team/");
        assertEquals(200, folderPage.getWebResponse().getStatusCode(), "premise: g1 opens the folder page");
        Map<URL, HtmlForm> fromFolder = entries("g1", folderPage);
        assertFalse(fromFolder.isEmpty(), "the folder page must offer a grant request entry (R4-14); targets were "
                + RequestPageFixtures.entryTargets(j, folderPage));
        assertTrue(fromFolder.values().stream().anyMatch(f -> "team".equals(scopeFullName(f))),
                "the folder page's grant form must be pre-filled with the folder: " + scopes(fromFolder));
        for (Map<URL, HtmlForm> fromPage : List.of(fromJob, fromFolder)) {
            fromPage.forEach((url, f) -> assertFalse(hasScopeTypeControl(f),
                    "D-71: the grant form from " + url + " must carry no scope type control"));
        }

        HtmlPage blank = UsabilityFixtures.htmlPage(j, "g1", "batch-control/grants/new");
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(blank, GRANT_CREATE);
        assertFalse(forms.isEmpty(), "grants/new must render the grant request form");
        assertFalse(hasScopeTypeControl(forms.get(0)), "D-71: the grant request form must carry no scope type control");
        assertTrue(UsabilityFixtures.hasField(forms.get(0), "scopeFullName") && UsabilityFixtures.hasField(forms.get(0), "actions"),
                "guard: the form still asks for the item and the actions");

        assertTrue(entries("n1", UsabilityFixtures.htmlPage(j, "n1", "job/team/")).isEmpty(),
                "a user without RequestGrant must be offered no grant request form from the folder page");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertTrue(entries("g1", UsabilityFixtures.htmlPage(j, "g1", "job/team/")).isEmpty(),
                "with change control off the folder page must offer no grant request form");
    }

    /**
     * T-UI-99: the job page of an approval-required job offers u1 an entry whose content is the
     * Request Run form posting to {@code <job>/batch-control/submit}. A submission to that endpoint
     * redirects to the new run request's detail page, and a grant request submission to
     * {@code grants/create} redirects to the new grant request's detail page; both pages open.
     */
    @Test
    public void t_ui_99_requestFormsComeFromEntriesAndSubmissionsLandOnTheDetailPage() throws Exception {
        Map<URL, HtmlForm> run = RequestPageFixtures.entriesRenderingForm(j, "u1",
                UsabilityFixtures.htmlPage(j, "u1", "job/batch-x/"), "job/batch-x/batch-control/submit");
        assertFalse(run.isEmpty(), "the job page must offer u1 an entry whose content is the Request Run form");

        Set<String> runsBefore = ApproverFormFixtures.runRequestIds();
        WebResponse runResponse = ApproverFormFixtures.submitRun(j, "u1", job, "month-end run", "a1");
        Set<String> runsAfter = ApproverFormFixtures.runRequestIds();
        runsAfter.removeAll(runsBefore);
        assertEquals(1, runsAfter.size(), "the run request submission must create one request: " + runResponse.getStatusCode());
        assertLandsOn(runResponse, "u1", "batch-control/requests/" + runsAfter.iterator().next());

        Set<String> grantsBefore = ApproverFormFixtures.grantRequestIds();
        WebResponse grantResponse = ApproverFormFixtures.submitGrant(j, "g1", "batch-x",
                Arrays.asList("CONFIGURE"), 30, "fix the job", null, "a1");
        Set<String> grantsAfter = ApproverFormFixtures.grantRequestIds();
        grantsAfter.removeAll(grantsBefore);
        assertEquals(1, grantsAfter.size(), "the grant request submission must create one request: " + grantResponse.getStatusCode());
        assertLandsOn(grantResponse, "g1", "batch-control/grants/" + grantsAfter.iterator().next());
    }

    /**
     * T-UI-100: the direct Request Run page still works (D-66 keeps it reachable for D-60): u1 gets
     * the form posting to {@code <job>/batch-control/submit} with the parameter default, and
     * {@code ?p.P=} pre-fills it. Guard: a user without any Batch Control permission gets 404.
     */
    @Test
    public void t_ui_100_directRequestRunPageAndPrefillStillWork() throws Exception {
        FreeStyleProject param = j.createFreeStyleProject("param-x");
        param.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "p-default")));
        BatchControlFixtures.setBatchControl(param, new BatchControlJobProperty(true));

        HtmlPage plain = UsabilityFixtures.htmlPage(j, "u1", "job/param-x/batch-control/");
        assertEquals(200, plain.getWebResponse().getStatusCode(), "u1 opens the direct Request Run page");
        assertEquals("p-default", parameterValue(form(plain), "P"), "the direct page shows the parameter default");
        HtmlPage prefilled = UsabilityFixtures.htmlPage(j, "u1", "job/param-x/batch-control/?p.P=routed-100");
        assertEquals("routed-100", parameterValue(form(prefilled), "P"), "D-60: ?p.P= must pre-fill the parameter");

        assertEquals(404, ApproverFormFixtures.get(j, "p0", "job/param-x/batch-control/").getStatusCode(),
                "guard: a user without any Batch Control permission must get 404 on the Request Run page (as T-UI-70)");
    }

    /**
     * T-UI-101: a grant form link in the former shape ({@code grants/?scopeFullName=}) redirects to
     * {@code grants/new} with the same query, so links in mails and bookmarks keep working.
     */
    @Test
    public void t_ui_101_oldGrantFormLinkRedirectsToNew() throws Exception {
        WebResponse r = ApproverFormFixtures.get(j, "g1", "batch-control/grants/?scopeFullName=batch-x");
        String location = r.getResponseHeaderValue("Location");
        assertTrue(r.getStatusCode() >= 300 && r.getStatusCode() < 400 && location != null,
                "the old link must redirect, got HTTP " + r.getStatusCode());
        assertEquals(j.getURL() + "batch-control/grants/new?scopeFullName=batch-x",
                new URL(r.getWebRequest().getUrl(), location).toExternalForm(), "the redirect keeps the query");
        assertEquals(200, ApproverFormFixtures.get(j, "g1", "batch-control/grants/").getStatusCode(),
                "guard: the grants page without a scope query is not redirected");
    }

    /**
     * T-UI-104: the dialog fragments render the request forms: {@code job/<j>/batch-control/dialog}
     * for u1 (Request) posts to {@code <job>/batch-control/submit}, {@code grants/dialog} for g1
     * (RequestGrant) posts to {@code grants/create}. Guards: p0 (no Batch Control permission) gets
     * 404 on the job fragment; n1 (no RequestGrant) gets no grant form from {@code grants/dialog}.
     */
    @Test
    public void t_ui_104_dialogFragmentsRenderTheFormsForPermittedUsersOnly() throws Exception {
        WebResponse run = ApproverFormFixtures.get(j, "u1", "job/batch-x/batch-control/dialog");
        assertEquals(200, run.getStatusCode(), "u1 opens the job's request dialog fragment");
        assertTrue(run.getContentAsString().contains("batch-control/submit"), "the job dialog fragment must carry the Request Run form");
        assertEquals(404, ApproverFormFixtures.get(j, "p0", "job/batch-x/batch-control/dialog").getStatusCode(),
                "a user without Request must get 404 on the job dialog fragment");

        WebResponse grant = ApproverFormFixtures.get(j, "g1", "batch-control/grants/dialog");
        assertEquals(200, grant.getStatusCode(), "g1 opens the grant dialog fragment");
        assertTrue(grant.getContentAsString().contains("grants/create"), "the grant dialog fragment must carry the grant request form");
        WebResponse refused = ApproverFormFixtures.get(j, "n1", "batch-control/grants/dialog");
        assertTrue(refused.getStatusCode() >= 400 || !refused.getContentAsString().contains("grants/create"),
                "a user without RequestGrant must not get the grant request form, got HTTP " + refused.getStatusCode());
    }

    /**
     * T-UI-105: a dialog submission ({@code dialog=true}) that is refused answers 400 and re-renders
     * the form with the message (the grant: "240" for a 9999-minute window; the run: no approver),
     * creating nothing; a valid dialog submission redirects to the new request's detail page.
     */
    @Test
    public void t_ui_105_dialogSubmissionsRerenderOn400AndRedirectOnSuccess() throws Exception {
        java.util.Set<String> grantsBefore = ApproverFormFixtures.grantRequestIds();
        List<org.htmlunit.util.NameValuePair> bad = grantParams("9999");
        WebResponse refused = ApproverFormFixtures.post(j, "g1", GRANT_CREATE, bad);
        assertEquals(400, refused.getStatusCode(), "a refused dialog grant submission must answer 400");
        assertTrue(refused.getContentAsString().contains("grants/create") && refused.getContentAsString().contains("240"),
                "the 400 must re-render the grant form with the maximum (240): " + UsabilityFixtures.excerpt(refused.getContentAsString()));
        assertEquals(grantsBefore, ApproverFormFixtures.grantRequestIds(), "a refused submission creates nothing");

        java.util.Set<String> runsBefore = ApproverFormFixtures.runRequestIds();
        List<org.htmlunit.util.NameValuePair> noApprover = new ArrayList<>();
        noApprover.add(new org.htmlunit.util.NameValuePair("dialog", "true"));
        noApprover.add(new org.htmlunit.util.NameValuePair("reason", "month-end run"));
        WebResponse runRefused = ApproverFormFixtures.post(j, "u1", "job/batch-x/batch-control/submit", noApprover);
        assertEquals(400, runRefused.getStatusCode(), "a refused dialog run submission (no approver) must answer 400");
        assertTrue(runRefused.getContentAsString().contains("batch-control/submit"), "the 400 must re-render the Request Run form");
        assertEquals(runsBefore, ApproverFormFixtures.runRequestIds(), "a refused submission creates nothing");

        WebResponse ok = ApproverFormFixtures.post(j, "g1", GRANT_CREATE, grantParams("30"));
        java.util.Set<String> created = ApproverFormFixtures.grantRequestIds();
        created.removeAll(grantsBefore);
        assertEquals(1, created.size(), "the valid dialog submission creates one request: HTTP " + ok.getStatusCode());
        assertLandsOn(ok, "g1", "batch-control/grants/" + created.iterator().next());
    }

    // ---------------------------------------------------------------- helpers

    private static List<org.htmlunit.util.NameValuePair> grantParams(String minutes) {
        List<org.htmlunit.util.NameValuePair> p = new ArrayList<>();
        p.add(new org.htmlunit.util.NameValuePair("dialog", "true"));
        p.add(new org.htmlunit.util.NameValuePair("scopeFullName", "batch-x"));
        p.add(new org.htmlunit.util.NameValuePair("actions", "CONFIGURE"));
        p.add(new org.htmlunit.util.NameValuePair("durationMinutes", minutes));
        p.add(new org.htmlunit.util.NameValuePair("reason", "fix the job"));
        p.add(new org.htmlunit.util.NameValuePair("approvers", "a1"));
        return p;
    }

    /** True if the form carries any control named {@code scopeType} (select, radio, hidden or text input). */
    static boolean hasScopeTypeControl(HtmlForm form) {
        return !form.getByXPath(".//*[@name='scopeType' or @name='_.scopeType']").isEmpty();
    }

    private Map<URL, HtmlForm> entries(String user, HtmlPage page) throws Exception {
        return RequestPageFixtures.entriesRenderingForm(j, user, page, GRANT_CREATE);
    }

    private static HtmlForm form(HtmlPage page) throws Exception {
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, "job/param-x/batch-control/submit");
        assertFalse(forms.isEmpty(), "the page must carry the Request Run form posting to <job>/batch-control/submit");
        return forms.get(0);
    }

    /** The redirect target of {@code response} resolves to {@code detailPath} (trailing slash ignored), and the page opens. */
    private void assertLandsOn(WebResponse response, String user, String detailPath) throws Exception {
        int code = response.getStatusCode();
        String location = response.getResponseHeaderValue("Location");
        assertTrue(code >= 300 && code < 400 && location != null, "the submission must redirect to the new request's detail page "
                + detailPath + ", got HTTP " + code + " Location " + location);
        String target = UsabilityFixtures.stripQueryAndSlash(new URL(response.getWebRequest().getUrl(), location).toExternalForm());
        assertEquals(UsabilityFixtures.stripQueryAndSlash(new URL(j.getURL(), detailPath).toExternalForm()), target,
                "the submission must land on the new request's detail page");
        assertEquals(200, ApproverFormFixtures.get(j, user, detailPath + "/").getStatusCode(), "the detail page must open for " + user);
    }

    /** The value of the form's scopeFullName control (input, textarea or select), or null. */
    static String scopeFullName(HtmlForm form) {
        for (Object e : form.getByXPath(".//*[@name='scopeFullName']")) {
            if (e instanceof HtmlInput i) {
                return i.getValue();
            }
            if (e instanceof HtmlTextArea t) {
                return t.getText();
            }
            if (e instanceof HtmlSelect s && !s.getSelectedOptions().isEmpty()) {
                return s.getSelectedOptions().get(0).getValueAttribute();
            }
        }
        return null;
    }

    private static List<String> scopes(Map<URL, HtmlForm> entries) {
        List<String> out = new ArrayList<>();
        entries.forEach((url, f) -> out.add(url + " -> " + scopeFullName(f)));
        return out;
    }

    /** The value of the job parameter {@code name}: core's {@code <div name="parameter">} block. */
    private static String parameterValue(HtmlForm form, String name) {
        List<HtmlElement> blocks = form.getByXPath(".//*[@name='parameter'][.//input[@name='name' and @value='" + name + "']]");
        assertEquals(1, blocks.size(), "exactly one parameter block for " + name);
        for (HtmlElement e : blocks.get(0).getHtmlElementDescendants()) {
            if ("value".equals(e.getAttribute("name")) && e instanceof HtmlInput input) {
                return input.getValue();
            }
        }
        throw new AssertionError("no value control for " + name + ": " + blocks.get(0).asXml());
    }
}
