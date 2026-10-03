package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (e2e-03 DEF-01, DEF-02): "the page of an approval-required job always shows a
 * notice that manual runs need an approved request, with a link to the request screen, so a user
 * whose click on another plugin's build button ... only gets that plugin's generic failure
 * message still sees why and where to go. A refusal page links the request screen only for users
 * who may open it and otherwise says whom to ask." Matrix rows T-06-56 .. T-06-60 (note 116),
 * T-06-71 (note 143) and T-UI-23 (note 140); T-06-59 and T-UI-23 revised for D-60 (note 185).
 *
 * <p>The notice is recognised in the job page's main panel (never the side panel, whose
 * "Request Run" entry T-06-15 already covers) as an element whose text names both a manual run
 * ("manual") and approval ("approv"), case-insensitively. The link is an {@code <a>} whose
 * target resolves to {@code job/<name>/batch-control/} (the request screen; not the activation
 * form beneath it).
 *
 * <p>Users: {@code u1} requester (Read, Item/Read, Item/Build, BatchControl/Request),
 * {@code nobc} (Read, Item/Read, Item/Build, no Batch Control permission — the per-job request
 * action is absent for them, SPEC 2), {@code a1} approver, {@code admin}.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class ApprovalNoticeTest {

    private static final Pattern MANUAL = Pattern.compile("(?i)manual");
    private static final Pattern APPROVAL = Pattern.compile("(?i)approv");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-06-56 (DEF-01): on an approval-required job without parameters, the requester's job page
     * shows the manual-run notice in the main panel, and the notice links the request screen.
     */
    @Test
    public void t_06_56_requesterSeesManualRunNoticeWithRequestLink() throws Exception {
        FreeStyleProject job = approvalRequired("notice-req");

        HtmlPage page = jobPage("u1", job);
        DomElement notice = findNotice(page);
        assertNotNull(notice, "the job page of an approval-required job must show a notice that manual runs need an"
                + " approved request (main panel): " + excerpt(mainText(page)));
        assertTrue(hasRequestLinkNear(page, notice, job),
                "the notice must link the request screen " + job.getUrl() + "batch-control/ for a requester: "
                        + excerpt(notice.asNormalizedText()));
    }

    /**
     * T-06-57 (DEF-01, SPEC 2 #31): a user who may not request the run (no Batch Control
     * permission) still sees the notice, but no link to the request action, which is absent for
     * them — nowhere on the page, not only not in the notice.
     */
    @Test
    public void t_06_57_nonRequesterSeesNoticeWithoutLink() throws Exception {
        FreeStyleProject job = approvalRequired("notice-nobc");

        HtmlPage page = jobPage("nobc", job);
        assertNotNull(findNotice(page), "the notice must be shown to every reader of the job, also one who may not"
                + " request: " + excerpt(mainText(page)));
        assertFalse(anyRequestLink(page, job), "no link to " + job.getUrl() + "batch-control/ may be offered to a user"
                + " without Batch Control permission (the action is absent for them)");

        // control: the requester is offered the link on the very same job
        assertTrue(anyRequestLink(jobPage("u1", job), job), "control: the requester's page links the request screen");
    }

    /**
     * T-06-58 (DEF-01, negative twin): no manual-run notice on a job that does not require
     * approval, and none on an approval-required job while run control is off (SPEC 1). The same
     * job shows it again when run control is turned back on, so an always-absent notice cannot
     * pass.
     */
    @Test
    public void t_06_58_noNoticeWithoutApprovalRequirementOrWithRunControlOff() throws Exception {
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("notice-free"));
        BatchControlFixtures.activate(free);
        FreeStyleProject controlled = approvalRequired("notice-rc");

        assertNull(findNotice(jobPage("u1", free)), "a job that does not require approval must show no manual-run notice");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        assertTrue(controlled.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "fixture: still approval-required");
        assertNull(findNotice(jobPage("u1", controlled)), "no manual-run notice while run control is off");

        cfg.setRunControlEnabled(true);
        cfg.save();
        assertNotNull(findNotice(jobPage("u1", controlled)), "control: the notice is back once run control is on");
    }

    /**
     * T-06-59 (DEF-02, rewritten for D-60): the requester submits the parameters form of an
     * approval-required, parameterized job (core's Build with Parameters). The answer is a 303 to
     * the job's request screen {@code job/<name>/batch-control/} carrying the typed value as
     * {@code p.P}; following it shows the request screen (approval named), not the classic build
     * form. Nothing is built.
     */
    @Test
    public void t_06_59_parameterizedRefusalRedirectsRequesterToRequestScreen() throws Exception {
        FreeStyleProject job = parameterized("refuse-req");

        Page answer = submitParametersForm("u1", job, "typed-59", false);
        assertEquals(303, answer.getWebResponse().getStatusCode(), "a requester's refused parameterized build must"
                + " answer 303 (D-60), got " + answer.getWebResponse().getStatusCode());
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the redirect must lead to the request screen: " + location);
        assertTrue(target.getQuery() != null && target.getQuery().contains("p.P=typed-59"),
                "the redirect must carry the typed value as p.P: " + location);

        HtmlPage screen = pageAt("u1", target.toExternalForm().substring(j.getURL().toExternalForm().length()));
        assertTrue(screen.asNormalizedText().toLowerCase(Locale.ROOT).contains("approv"),
                "the request screen must name approval: " + excerpt(screen.asNormalizedText()));
        assertTrue(screen.getForms().stream().noneMatch(f -> "parameters".equals(f.getNameAttribute())),
                "the redirect must not fall back to the classic build form");
        assertBlockedNoBuild(job);
    }

    /**
     * T-06-60 (DEF-02): a user without Batch Control permission is refused on three paths — the
     * parameters form, {@code POST buildWithParameters} and {@code POST build} of a job without
     * parameters. None of the answers offers the request URL (which is a 404 for them, SPEC 2),
     * neither as a link nor as text; each still names the approval requirement and says whom to
     * ask (a configured approver, here {@code a1}, or an administrator).
     */
    @Test
    public void t_06_60_refusalForNonRequesterOffersNoRequestUrlAndSaysWhomToAsk() throws Exception {
        FreeStyleProject param = parameterized("refuse-nobc");
        FreeStyleProject plain = approvalRequired("refuse-nobc-plain");

        List<Page> answers = new ArrayList<>();
        answers.add(submitParametersForm("nobc", param));
        answers.add(PluginInteractionFixtures.post(j, "nobc", param.getUrl() + "buildWithParameters?P=x"));
        answers.add(PluginInteractionFixtures.post(j, "nobc", plain.getUrl() + "build?delay=0sec"));

        String[] paths = {"parameters form", "buildWithParameters", "build (no parameters)"};
        for (int i = 0; i < answers.size(); i++) {
            Page answer = answers.get(i);
            String what = paths[i] + " as nobc";
            int code = answer.getWebResponse().getStatusCode();
            assertTrue(code >= 400, what + ": the refusal must not answer success, got " + code);
            String body = answer.getWebResponse().getContentAsString();
            String text = answer instanceof HtmlPage ? ((HtmlPage) answer).asNormalizedText() : body;
            assertTrue(text.toLowerCase(Locale.ROOT).contains("approv"), what + ": the refusal must name the approval"
                    + " requirement (no silent failure): " + excerpt(text));
            assertFalse(text.contains("/batch-control"), what + ": no request URL may be shown as text to a user who"
                    + " cannot open it: " + excerpt(text));
            if (answer instanceof HtmlPage) {
                assertFalse(anyPluginLink((HtmlPage) answer), what + ": no link into batch-control may be offered");
            }
            String lower = text.toLowerCase(Locale.ROOT);
            assertTrue(text.contains("a1") || lower.contains("administrator"), what + ": the refusal must say whom to"
                    + " ask (an approver or an administrator): " + excerpt(text));
        }
        assertBlockedNoBuild(param);
        assertBlockedNoBuild(plain);
    }

    /**
     * T-06-71 (e2e-run3 DEF-31/32, PR-02/PR-05): the build page of an approval-required job — where
     * other plugins' Rebuild and Retry buttons live and end in their generic "Failed." toast —
     * shows the same manual-run notice as the job page (SPEC 6: a user whose click on another
     * plugin's build button only gets that plugin's generic failure message still sees why and
     * where to go). The requester's notice links the request screen; a user who may not request
     * sees the notice without the link; an uncontrolled job's build page has no notice (note 143).
     */
    @Test
    public void t_06_71_buildPageOfApprovalRequiredJobShowsNotice() throws Exception {
        FreeStyleProject job = approvalRequired("notice-build");
        PluginInteractionFixtures.requestAndApprove(job);
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "fixture: the approved request must have produced build #1");

        HtmlPage requesterPage = pageAt("u1", job.getUrl() + "1/");
        DomElement notice = findNotice(requesterPage);
        assertNotNull(notice, "the build page of an approval-required job must show the manual-run notice in the main"
                + " panel: " + excerpt(mainText(requesterPage)));
        assertTrue(hasRequestLinkNear(requesterPage, notice, job), "the build-page notice must link "
                + job.getUrl() + "batch-control/ for a requester: " + excerpt(notice.asNormalizedText()));

        HtmlPage nobcPage = pageAt("nobc", job.getUrl() + "1/");
        assertNotNull(findNotice(nobcPage), "the build-page notice must be shown also to a reader who may not request: "
                + excerpt(mainText(nobcPage)));
        assertFalse(anyRequestLink(nobcPage, job), "no link to the request screen for a user without Batch Control"
                + " permission");

        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("notice-build-free"));
        BatchControlFixtures.activate(free);
        j.buildAndAssertSuccess(free);
        assertNull(findNotice(pageAt("u1", free.getUrl() + "1/")), "the build page of a job that does not require"
                + " approval must show no manual-run notice");
    }

    /**
     * T-UI-23 (e2e-run3 DEF-28, A-11): the gate's refusal page ("Approval required") carries no
     * hierarchical "Back to ..." link — the breadcrumb already leads back (SPEC 6 usability line,
     * note 140). Read on the three answers a person meets: the requester's direct build, the
     * requester's parameters form, and a non-requester's direct build. Each is still an HTML page
     * naming approval, so an empty answer cannot pass.
     */
    @Test
    public void t_ui_23_refusalPageHasNoBackLink() throws Exception {
        FreeStyleProject plain = approvalRequired("nolink-plain");
        FreeStyleProject param = parameterized("nolink-param");

        List<Page> answers = new ArrayList<>();
        answers.add(PluginInteractionFixtures.post(j, "u1", plain.getUrl() + "build?delay=0sec"));
        answers.add(submitParametersForm("nobc", param));
        answers.add(PluginInteractionFixtures.post(j, "nobc", plain.getUrl() + "build?delay=0sec"));
        String[] paths = {"direct build as u1", "parameters form as nobc", "direct build as nobc"};
        // D-60: the requester's parameters form is no longer a refusal page but a 303 to the
        // pre-filled request screen (T-06-59), so it has no back link to check here
        assertEquals(303, submitParametersForm("u1", param, "x", false).getWebResponse().getStatusCode(),
                "fixture (D-60): the requester's parameters form redirects to the request screen");
        Pattern backText = Pattern.compile("(?i)\\bback\\s+to\\b");
        Pattern backCaption = Pattern.compile("(?is)^\\W*back\\b.*");
        for (int i = 0; i < answers.size(); i++) {
            Page answer = answers.get(i);
            assertTrue(answer.getWebResponse().getStatusCode() >= 400, paths[i] + ": fixture: must be refused, got "
                    + answer.getWebResponse().getStatusCode());
            assertTrue(answer instanceof HtmlPage, paths[i] + ": the refusal must be an HTML page");
            HtmlPage page = (HtmlPage) answer;
            String text = page.asNormalizedText();
            assertTrue(text.toLowerCase(Locale.ROOT).contains("approv"), paths[i] + ": fixture: the refusal names"
                    + " approval: " + excerpt(text));
            assertFalse(backText.matcher(text).find(), paths[i] + ": the refusal page must not carry a \"Back to ...\""
                    + " link or text: " + excerpt(text));
            for (HtmlAnchor a : page.getAnchors()) {
                assertFalse(backCaption.matcher(a.asNormalizedText().trim()).matches(), paths[i]
                        + ": no hierarchical back link may be offered: '" + a.asNormalizedText() + "' -> "
                        + a.getHrefAttribute());
            }
        }
        assertBlockedNoBuild(plain);
        assertBlockedNoBuild(param);
    }

    // ---------------------------------------------------------------- helpers

    private HtmlPage pageAt(String userId, String relative) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), userId + " must reach " + relative);
        return page;
    }

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);   // keep the trigger-lock notice (T-06-49) off the page
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        // an activated job, so that the activation notice (T-06a-38) says "activated" and cannot
        // be mistaken for the manual-run notice
        BatchControlFixtures.activate(job);
        return job;
    }

    private FreeStyleProject parameterized(String name) throws Exception {
        FreeStyleProject job = approvalRequired(name);
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "default")));
        return job;
    }

    private HtmlPage jobPage(String userId, FreeStyleProject job) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        HtmlPage page = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl()), HttpMethod.GET));
        assertEquals(200, page.getWebResponse().getStatusCode(), userId + " must reach " + job.getUrl());
        return page;
    }

    private Page submitParametersForm(String userId, FreeStyleProject job) throws Exception {
        return submitParametersForm(userId, job, null, true);
    }

    /** Submits core's parameters form, optionally typing {@code value} into P, with or without following redirects. */
    private Page submitParametersForm(String userId, FreeStyleProject job, String value, boolean followRedirects)
            throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        wc.getOptions().setJavaScriptEnabled(true);
        HtmlPage formPage = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"),
                HttpMethod.GET));
        // core serves the parameters form of a GET build with HTTP 405 (the build itself needs POST)
        int code = formPage.getWebResponse().getStatusCode();
        assertTrue(code == 200 || code == 405, "fixture: " + userId + " must reach the parameters form of "
                + job.getFullName() + ", got " + code);
        HtmlForm form = formPage.getFormByName("parameters");
        if (value != null) {
            List<org.htmlunit.html.HtmlInput> inputs = form.getByXPath(
                    ".//*[@name='parameter'][.//input[@name='name' and @value='P']]//input[@name='value']");
            assertEquals(1, inputs.size(), "fixture: one value input for P");
            inputs.get(0).setValue(value);
        }
        wc.getOptions().setRedirectEnabled(followRedirects);
        return j.submit(form);
    }

    /** The smallest main-panel element whose text names both a manual run and approval. */
    private static DomElement findNotice(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        if (main == null) {
            return null;
        }
        DomElement best = null;
        for (DomElement element : main.getHtmlElementDescendants()) {
            String tag = element.getTagName();
            if ("script".equals(tag) || "style".equals(tag)) {
                continue;
            }
            String text = element.getTextContent();
            if (text == null || !MANUAL.matcher(text).find() || !APPROVAL.matcher(text).find()) {
                continue;
            }
            if (best == null || text.length() < best.getTextContent().length()) {
                best = element;
            }
        }
        return best;
    }

    private boolean hasRequestLinkNear(HtmlPage page, DomElement notice, FreeStyleProject job) throws Exception {
        DomNode current = notice;
        for (int level = 0; level < 4 && current != null; level++) {
            if (current instanceof DomElement) {
                for (DomElement a : ((DomElement) current).getElementsByTagName("a")) {
                    if (a instanceof HtmlAnchor && isRequestScreen(page, (HtmlAnchor) a, job)) {
                        return true;
                    }
                }
            }
            current = current.getParentNode();
        }
        return false;
    }

    private boolean anyRequestLink(HtmlPage page, FreeStyleProject job) throws Exception {
        for (HtmlAnchor a : page.getAnchors()) {
            if (isRequestScreen(page, a, job)) {
                return true;
            }
        }
        return false;
    }

    /** Any anchor pointing into a Batch Control URL (plugin static resources excluded). */
    private static boolean anyPluginLink(HtmlPage page) {
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            if (href != null && href.contains("batch-control") && !href.contains("/plugin/batch-control/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequestScreen(HtmlPage page, HtmlAnchor a, FreeStyleProject job) throws Exception {
        String href = a.getHrefAttribute();
        if (href == null || href.isEmpty() || href.startsWith("#")) {
            return false;
        }
        String resolved = page.getFullyQualifiedUrl(href).toExternalForm();
        int cut = resolved.indexOf('?');
        if (cut >= 0) {
            resolved = resolved.substring(0, cut);
        }
        cut = resolved.indexOf('#');
        if (cut >= 0) {
            resolved = resolved.substring(0, cut);
        }
        String target = new URL(j.getURL(), job.getUrl() + "batch-control").toExternalForm();
        return resolved.equals(target) || resolved.equals(target + "/");
    }

    private void assertBlockedNoBuild(FreeStyleProject job) throws Exception {
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), job.getFullName() + ": no build may have run");
        assertEquals(1, job.getNextBuildNumber(), job.getFullName() + ": nextBuildNumber must not move");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
    }

    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message
                + (value instanceof DomElement ? ": " + excerpt(((DomElement) value).asNormalizedText()) : ""));
    }

    private static String mainText(HtmlPage page) {
        DomElement main = page.getElementById("main-panel");
        return main == null ? page.asNormalizedText() : main.asNormalizedText();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "<null>";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 1200 ? flat.substring(0, 1200) + "..." : flat;
    }
}
