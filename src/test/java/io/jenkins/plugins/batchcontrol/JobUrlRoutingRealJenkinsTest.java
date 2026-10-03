package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #71 (matrix notes 220-221, T-UI-70..72): the job-level URLs keep working, with the same access
 * rules, once the job action no longer routes through {@code StaplerProxy}/{@code getTarget}.
 * The rows run under {@link RealJenkinsExtension} (per-plugin class loaders), because a plain
 * JenkinsRule's flat class path has hidden routing and view-resolution defects before (T-UI-46,
 * T-UI-48). The plain-JenkinsRule rows on the same URLs (T-02-09, T-06a-*, T-06-89..97, T-UI-22/24)
 * keep covering the details; these rows are the real-class-loader smoke.
 *
 * <p>D-64 moves the activation form to its own action, {@code <item>/batch-control-activation/}
 * (submit {@code .../submit}), for jobs and computed folders, and drops the old
 * {@code <item>/batch-control/activation} without a redirect: the new URLs answer an allowed user,
 * the old ones answer 404 even to them. A GET of a new URL is followed through redirects; a POST is
 * not followed and must answer 2xx/3xx and store the request. A user who may not use the action
 * gets 404 (hidden), as before.
 *
 * <p>Written from docs/SPEC.md items 2/6/6a, D-60, D-64, issue #71 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
public class JobUrlRoutingRealJenkinsTest {

    /** D-64: the activation form's own action. */
    static final String NEW_FORM = "batch-control-activation/";
    static final String NEW_SUBMIT = "batch-control-activation/submit";
    /** The URL used before D-64, dropped without a redirect. */
    static final String OLD_FORM = "batch-control/activation";

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension();

    /** T-UI-70: {@code <job>/batch-control/} and its D-60 {@code ?p.X=} pre-fill; 404 for others. */
    @Test
    public void t_ui_70_requestRunFormAndPrefillStillRouteInARealJenkins() throws Throwable {
        rr.then(JobUrlRoutingRealJenkinsTest::requestRunForm);
    }

    /** T-UI-71: the job's activation form and submission at the D-64 URL; the job page's link; old URL 404; 404 for others. */
    @Test
    public void t_ui_71_activationFormUrlsStillRouteInARealJenkins() throws Throwable {
        rr.then(JobUrlRoutingRealJenkinsTest::activationForm);
    }

    /** T-UI-72: a computed folder's activation entry at the D-64 URL; old URL 404; 404 for others. */
    @Test
    public void t_ui_72_computedFolderActivationEntryStillRoutesInARealJenkins() throws Throwable {
        rr.then(JobUrlRoutingRealJenkinsTest::computedFolderActivation);
    }

    // ---------------------------------------------------------------- bodies (run inside Jenkins)

    private static void requestRunForm(JenkinsRule r) throws Throwable {
        setUp(r);
        FreeStyleProject job = r.createFreeStyleProject("route-run");
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P", "p-default")));
        control(job);

        HtmlPage plainForm = html(r, "u1", job.getUrl() + "batch-control/");
        assertTrue(hasRequestRunForm(plainForm, job), "u1 must get the Request Run form at <job>/batch-control/: "
                + excerpt(plainForm));
        assertEquals("p-default", parameterValue(requestRunFormOf(plainForm, job), "P"),
                "premise: without p.P the form shows the parameter's default");

        HtmlPage prefilled = html(r, "u1", job.getUrl() + "batch-control/?p.P=routed-71");
        assertTrue(hasRequestRunForm(prefilled, job), "the pre-filled URL must still render the Request Run form");
        assertEquals("routed-71", parameterValue(requestRunFormOf(prefilled, job), "P"),
                "D-60: ?p.P= must pre-fill the parameter P");

        // guard: the same access rules as before (hidden = 404)
        assertEquals(404, status(r, "plain", job.getUrl() + "batch-control/"),
                "a user without BatchControl/Request must get 404 at <job>/batch-control/");
        assertEquals(404, status(r, "plain", job.getUrl() + "batch-control/?p.P=x"),
                "a user without BatchControl/Request must get 404 at the pre-fill URL");
        assertEquals(404, status(r, "blind", job.getUrl() + "batch-control/"),
                "a user who cannot read the job must get 404 at <job>/batch-control/");
    }

    private static void activationForm(JenkinsRule r) throws Throwable {
        setUp(r);
        FreeStyleProject job = r.createFreeStyleProject("route-act");
        control(job);
        assertFalse(ActivationService.get().isActivated(job), "premise: a job created under run control is not activated");

        HtmlPage form = html(r, "u1", job.getUrl() + NEW_FORM);
        assertTrue(lower(form).contains("activat"), "the activation form must name the activation: " + excerpt(form));
        List<String> crumbs = crumbs(form);
        assertFalse(crumbs.isEmpty(), "the activation form must render breadcrumbs");
        assertFalse(crumbs.get(crumbs.size() - 1).equalsIgnoreCase("Request Run"),
                "the activation form's last breadcrumb must not be 'Request Run': " + crumbs);

        // the link the job page offers today (SPEC 6a acceptance) must reach a working form too
        Set<String> links = activationLinks(html(r, "u1", job.getUrl()));
        assertFalse(links.isEmpty(), "premise (SPEC 6a): the job page links to the activation request form");
        for (String href : links) {
            Page p = client(r, "u1", true).getPage(new URL(href));
            assertEquals(200, p.getWebResponse().getStatusCode(), "the job page's activation link " + href + " must answer 200");
        }

        int before = ActivationService.get().list().size();
        assertOldUrlIsGone(r, job.getUrl(), before);
        WebResponse submit = postActivation(r, "u1", job.getUrl() + NEW_SUBMIT);
        int code = submit.getStatusCode();
        assertTrue(code >= 200 && code < 400, "POST <job>/" + NEW_SUBMIT + " must succeed, got " + code
                + ": " + excerpt(submit.getContentAsString()));
        assertEquals(before + 1, ActivationService.get().list().size(), "the submission must store one activation request");

        // guard: the same access rules as before (hidden = 404), nothing stored
        assertEquals(404, status(r, "plain", job.getUrl() + NEW_FORM),
                "a user without BatchControl/Request must get 404 at the activation form");
        assertEquals(404, postActivation(r, "plain", job.getUrl() + NEW_SUBMIT).getStatusCode(),
                "a user without BatchControl/Request must get 404 at the activation submission");
        assertEquals(404, status(r, "blind", job.getUrl() + NEW_FORM),
                "a user who cannot read the job must get 404 at the activation form");
        assertEquals(before + 1, ActivationService.get().list().size(), "a refused submission must store nothing");
    }

    private static void computedFolderActivation(JenkinsRule r) throws Throwable {
        setUp(r);
        WorkflowMultiBranchProject mb = r.jenkins.createProject(WorkflowMultiBranchProject.class, "route-mb");
        assertFalse(ActivationService.get().isActivated(mb), "premise: a computed folder created under run control is not activated");

        HtmlPage form = html(r, "u1", mb.getUrl() + NEW_FORM);
        assertTrue(lower(form).contains("activat"), "the computed folder's activation form must name the activation: "
                + excerpt(form));

        int before = ActivationService.get().list().size();
        assertOldUrlIsGone(r, mb.getUrl(), before);
        WebResponse submit = postActivation(r, "u1", mb.getUrl() + NEW_SUBMIT);
        int code = submit.getStatusCode();
        assertTrue(code >= 200 && code < 400, "POST <mb>/" + NEW_SUBMIT + " must succeed, got " + code
                + ": " + excerpt(submit.getContentAsString()));
        assertEquals(before + 1, ActivationService.get().list().size(), "the submission must store one activation request");

        // guard
        assertEquals(404, status(r, "plain", mb.getUrl() + NEW_FORM),
                "a user without BatchControl/Request must get 404 at the computed folder's activation form");
        assertEquals(404, postActivation(r, "plain", mb.getUrl() + NEW_SUBMIT).getStatusCode(),
                "a user without BatchControl/Request must get 404 at the computed folder's activation submission");
        assertEquals(404, status(r, "blind", mb.getUrl() + NEW_FORM),
                "a user who cannot read the computed folder must get 404 at its activation form");
        assertEquals(before + 1, ActivationService.get().list().size(), "a refused submission must store nothing");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * D-64: the former {@code <item>/batch-control/activation} URL is dropped without a redirect, so
     * even an allowed user gets 404 on its GET and POST (redirects are not followed, so a 3xx to the
     * new URL also fails), and nothing is stored.
     */
    private static void assertOldUrlIsGone(JenkinsRule r, String itemUrl, int requestsBefore) throws Exception {
        int get = client(r, "u1", false).getPage(new URL(r.getURL(), itemUrl + OLD_FORM)).getWebResponse().getStatusCode();
        assertEquals(404, get, "D-64: GET <item>/" + OLD_FORM + " must answer 404 to an allowed user");
        assertEquals(404, postActivation(r, "u1", itemUrl + OLD_FORM + "/submit").getStatusCode(),
                "D-64: POST <item>/" + OLD_FORM + "/submit must answer 404 to an allowed user");
        assertEquals(requestsBefore, ActivationService.get().list().size(), "the old URL must store nothing");
    }

    private static void setUp(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("blind")
                .grant(Jenkins.READ, Item.READ).everywhere().to("plain")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** Puts the job under run control with approval required (one property, ours). */
    private static void control(Job<?, ?> job) throws Exception {
        while (job.getProperty(BatchControlJobProperty.class) != null) {
            job.removeProperty(BatchControlJobProperty.class);
        }
        job.addProperty(new BatchControlJobProperty(true));
    }

    private static JenkinsRule.WebClient client(JenkinsRule r, String user, boolean redirects) throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.login(user);
        wc.getOptions().setRedirectEnabled(redirects);
        return wc;
    }

    private static HtmlPage html(JenkinsRule r, String user, String path) throws Exception {
        Page p = client(r, user, true).getPage(new URL(r.getURL(), path));
        String body = p.getWebResponse().getContentAsString();
        assertEquals(200, p.getWebResponse().getStatusCode(), user + " GET /" + path + " must answer 200 in a real Jenkins: "
                + (body.length() > 1500 ? body.substring(0, 1500) : body));
        assertTrue(p instanceof HtmlPage, user + " GET /" + path + " must render HTML");
        return (HtmlPage) p;
    }

    private static int status(JenkinsRule r, String user, String path) throws Exception {
        return client(r, user, true).getPage(new URL(r.getURL(), path)).getWebResponse().getStatusCode();
    }

    private static WebResponse postActivation(JenkinsRule r, String user, String path) throws Exception {
        JenkinsRule.WebClient wc = client(r, user, false);
        // the crumb is fetched as the logged-in user over HTTP: in a real Jenkins the test thread
        // cannot compute the crumb of the client's session (createCrumbedUrl answers 403 there)
        net.sf.json.JSONObject crumb = net.sf.json.JSONObject.fromObject(
                wc.goTo("crumbIssuer/api/json", "application/json").getWebResponse().getContentAsString());
        WebRequest req = new WebRequest(new URL(r.getURL(), path), HttpMethod.POST);
        req.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("action", "ACTIVATE"));
        params.add(new NameValuePair("reason", "routing smoke #71"));
        params.add(new NameValuePair("approvers", "a1"));
        req.setRequestParameters(params);
        return wc.getPage(req).getWebResponse();
    }

    private static boolean hasRequestRunForm(HtmlPage page, Job<?, ?> job) throws Exception {
        return requestRunFormOf(page, job) != null;
    }

    /** The form whose action, resolved against the page, ends in {@code <job>/batch-control/submit}. */
    private static HtmlForm requestRunFormOf(HtmlPage page, Job<?, ?> job) throws Exception {
        for (HtmlForm f : UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit")) {
            return f;
        }
        return null;
    }

    /** The value of the job parameter {@code name}: core's {@code <div name="parameter">} block. */
    private static String parameterValue(HtmlForm form, String name) {
        assertTrue(form != null, "the page must carry the Request Run form");
        List<HtmlElement> blocks = form.getByXPath(".//*[@name='parameter'][.//input[@name='name' and @value='" + name + "']]");
        assertEquals(1, blocks.size(), "exactly one parameter block for " + name);
        for (HtmlElement e : blocks.get(0).getHtmlElementDescendants()) {
            if ("value".equals(e.getAttribute("name")) && e instanceof HtmlInput) {
                return ((HtmlInput) e).getValue();
            }
        }
        throw new AssertionError("no value control for " + name + ": " + blocks.get(0).asXml());
    }

    private static List<String> crumbs(HtmlPage page) {
        List<String> out = new ArrayList<>();
        for (DomElement li : page.querySelectorAll("li.jenkins-breadcrumbs__list-item").stream().map(n -> (DomElement) n).toList()) {
            String t = li.asNormalizedText().trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Absolute hrefs on the page that lead to an activation form (not the activations inbox). */
    private static Set<String> activationLinks(HtmlPage page) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (HtmlAnchor a : page.getAnchors()) {
            String href = a.getHrefAttribute();
            String low = href.toLowerCase(Locale.ROOT);
            if (low.contains("activation") && !low.contains("activations")) {
                out.add(page.getFullyQualifiedUrl(href).toExternalForm());
            }
        }
        return out;
    }

    private static String lower(HtmlPage page) {
        return page.getWebResponse().getContentAsString().toLowerCase(Locale.ROOT);
    }

    private static String excerpt(HtmlPage page) {
        return excerpt(page.getWebResponse().getContentAsString());
    }

    private static String excerpt(String body) {
        return body.length() > 1500 ? body.substring(0, 1500) : body;
    }
}
