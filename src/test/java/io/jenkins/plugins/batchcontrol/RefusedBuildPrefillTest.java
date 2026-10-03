package io.jenkins.plugins.batchcontrol;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlSelect;
import org.htmlunit.html.HtmlTextArea;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, D-60 (P-03): a refused build submission with parameters on an approval-required
 * job leads a user who may request to the job's Request Run form with the submitted values filled
 * in, sensitive values excepted; nothing is queued or stored until the requester submits the
 * form; the refusal does not fall back to the classic build form. Matrix rows T-06-89 .. T-06-96
 * (note 185).
 *
 * <p>Frozen contract: the refusal answers {@code 303} to {@code <job>/batch-control/} carrying the
 * values as {@code p.<NAME>=<value>}; {@code GET <job>/batch-control/?p.<NAME>=<value>} pre-fills
 * only parameters the job defines, of the simple kinds string, text, boolean and choice; password
 * values never appear in the redirect and are ignored if supplied; an invalid choice and unknown
 * {@code p.*} are ignored; values longer than 2000 characters are not carried; values are HTML
 * escaped; the form's POST never reads {@code p.*}. A user who may not request keeps the 400
 * refusal page, rendered inside {@code form.batch-control-refusal} with the {@code X-Dialog-Title}
 * header.
 *
 * <p>Users: {@code u1} requester (Item/Read, Item/Build, BatchControl/Request), {@code nobc}
 * (Item/Read, Item/Build, no Batch Control permission), {@code a1} approver.
 *
 * Written from docs/SPEC.md item 6, docs/DECISIONS.md D-60 and P-03, and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class RefusedBuildPrefillTest {

    private static final String SECRET_DEFAULT = "pw-default-7Qx";
    private static final String TYPED_SECRET = "typed-secret-9Zk";

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, Item.BUILD).everywhere().to("nobc")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("prefill-x");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("P", "default", "a string"),
                new TextParameterDefinition("T", "text-default", "a text"),
                new BooleanParameterDefinition("B", false, "a boolean"),
                new ChoiceParameterDefinition("C", new String[] {"first", "second", "third"}, "a choice"),
                new PasswordParameterDefinition("S", Secret.fromString(SECRET_DEFAULT), "a password")));
    }

    /**
     * T-06-89: u1 submits core's parameters form (typed P, T, B, C and a typed password S). The
     * answer is a 303 to {@code job/prefill-x/batch-control/} carrying p.P, p.T, p.B and p.C with
     * the typed values; the password, typed or default, appears nowhere in the Location. Nothing is
     * queued and no request is stored. Following the redirect shows the Request Run form (not the
     * classic build form) with the values filled in.
     */
    @Test
    public void t_06_89_requesterRefusalRedirectsToPrefilledRequestForm() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = jsClient("u1");
        HtmlForm form = coreParametersForm(wc);
        setValue(form, "P", "typed p");
        setValue(form, "T", "line one");
        setValue(form, "B", "true");
        setValue(form, "C", "second");
        setValue(form, "S", TYPED_SECRET);
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);

        assertEquals(303, answer.getWebResponse().getStatusCode(), "a requester's refused parameterized build must"
                + " answer 303: " + excerpt(answer.getWebResponse().getContentAsString()));
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "the 303 must carry a Location");
        URL target = new URL(answer.getUrl(), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the redirect must lead to the job's Request Run form");
        Map<String, String> query = query(target);
        assertEquals("typed p", query.get("p.P"), "the string value must be carried: " + location);
        assertEquals("line one", query.get("p.T"), "the text value must be carried: " + location);
        assertEquals("true", query.get("p.B"), "the boolean value must be carried: " + location);
        assertEquals("second", query.get("p.C"), "the choice value must be carried: " + location);
        assertFalse(query.containsKey("p.S"), "no password parameter may be carried: " + location);
        assertFalse(location.contains(TYPED_SECRET) || location.contains(SECRET_DEFAULT),
                "no password value may appear in the redirect: " + location);
        assertNothingQueuedOrStored(before);

        HtmlPage requestForm = page("u1", target.toExternalForm());
        assertTrue(requestForm.getForms().stream().noneMatch(f -> "parameters".equals(f.getNameAttribute())),
                "the redirect must not fall back to the classic build form");
        HtmlForm submit = requestRunForm(requestForm);
        assertEquals("typed p", valueOf(submit, "P"));
        assertEquals("line one", valueOf(submit, "T"));
        assertEquals("true", valueOf(submit, "B"));
        assertEquals("second", valueOf(submit, "C"));
        assertFalse(requestForm.getWebResponse().getContentAsString().contains(TYPED_SECRET),
                "the typed password must not reach the Request Run form");
        assertNothingQueuedOrStored(before);
    }

    /**
     * T-06-90: nobc (may not request) submits the same form: the answer is the 400 refusal page,
     * its message inside {@code form.batch-control-refusal}, with a non-empty {@code X-Dialog-Title}
     * header; no redirect to the request form and no fallback to the classic build form; nothing
     * queued or stored.
     */
    @Test
    public void t_06_90_nonRequesterKeepsTheRefusalPage() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        JenkinsRule.WebClient wc = jsClient("nobc");
        HtmlForm form = coreParametersForm(wc);
        setValue(form, "P", "typed p");
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);

        assertEquals(400, answer.getWebResponse().getStatusCode(), "a non-requester's refused build must answer 400");
        String title = answer.getWebResponse().getResponseHeaderValue("X-Dialog-Title");
        assertTrue(title != null && !title.isBlank(), "the refusal must set the X-Dialog-Title header");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page");
        HtmlPage page = (HtmlPage) answer;
        List<DomElement> refusal = page.getByXPath("//form[contains(concat(' ', normalize-space(@class), ' '), ' batch-control-refusal ')]");
        assertEquals(1, refusal.size(), "the refusal must be rendered inside one form.batch-control-refusal: "
                + excerpt(page.asXml()));
        assertTrue(refusal.get(0).asNormalizedText().toLowerCase(java.util.Locale.ROOT).contains("approv"),
                "the refusal message inside the form must name approval: " + excerpt(refusal.get(0).asNormalizedText()));
        assertTrue(page.getForms().stream().noneMatch(f -> "parameters".equals(f.getNameAttribute())),
                "the refusal must not fall back to the classic build form");
        assertFalse(page.getWebResponse().getContentAsString().contains("typed p"),
                "no value is carried for a user who cannot open the request form");
        assertNothingQueuedOrStored(before);
    }

    /**
     * T-06-91: {@code GET job/prefill-x/batch-control/?p.P=..&p.T=..&p.B=true&p.C=third} fills the
     * Request Run form with the values; without the query the same form shows the defaults
     * (falsifiability twin); nothing is queued or stored by either GET.
     */
    @Test
    public void t_06_91_getPrefillsSimpleParameters() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        HtmlForm bare = requestRunForm(page("u1", formUrl(Map.of())));
        assertEquals("default", valueOf(bare, "P"), "twin: without p.* the form shows the default");
        assertEquals("false", valueOf(bare, "B"));
        assertEquals("first", valueOf(bare, "C"));

        Map<String, String> p = new LinkedHashMap<>();
        p.put("p.P", "from query");
        p.put("p.T", "text from query");
        p.put("p.B", "true");
        p.put("p.C", "third");
        HtmlForm filled = requestRunForm(page("u1", formUrl(p)));
        assertEquals("from query", valueOf(filled, "P"));
        assertEquals("text from query", valueOf(filled, "T"));
        assertEquals("true", valueOf(filled, "B"));
        assertEquals("third", valueOf(filled, "C"));
        assertNothingQueuedOrStored(before);
    }

    /**
     * T-06-92: a supplied password value is ignored and never appears in the page; an unknown
     * {@code p.*} is ignored and never appears; an invalid choice is ignored (the default stays) and
     * never appears. A valid P in the same query is still filled (twin).
     */
    @Test
    public void t_06_92_passwordUnknownAndInvalidChoiceAreIgnored() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("p.S", "query-secret-4Wm");
        p.put("p.NOPE", "unknown-marker-8Rt");
        p.put("p.C", "invalid-choice-3Pv");
        p.put("p.P", "still filled");
        HtmlPage page = page("u1", formUrl(p));
        String html = page.getWebResponse().getContentAsString();
        HtmlForm form = requestRunForm(page);
        assertEquals("still filled", valueOf(form, "P"), "twin: a valid value in the same query is filled");
        assertFalse(html.contains("query-secret-4Wm"), "a supplied password value must be ignored and not rendered");
        assertFalse(html.contains("unknown-marker-8Rt"), "an unknown p.* must be ignored and not rendered");
        assertFalse(html.contains("invalid-choice-3Pv"), "an invalid choice must be ignored and not rendered");
        assertEquals("first", valueOf(form, "C"), "an invalid choice leaves the default selected");
    }

    /**
     * T-06-93: a value of 2001 characters is not carried (the default stays and the value is not
     * rendered); a value of exactly 2000 characters is (boundary twin). The refusal redirect does
     * not carry the 2001-character value either.
     */
    @Test
    public void t_06_93_valuesOver2000CharactersAreNotCarried() throws Exception {
        String tooLong = "y".repeat(2001);
        String longest = "z".repeat(2000);
        HtmlPage refused = page("u1", formUrl(Map.of("p.P", tooLong)));
        assertEquals("default", valueOf(requestRunForm(refused), "P"), "a 2001-character value must not be carried");
        assertFalse(refused.getWebResponse().getContentAsString().contains(tooLong), "the long value must not be rendered");
        assertEquals(longest, valueOf(requestRunForm(page("u1", formUrl(Map.of("p.P", longest)))), "P"),
                "a 2000-character value is within the bound and must be carried");

        JenkinsRule.WebClient wc = jsClient("u1");
        HtmlForm form = coreParametersForm(wc);
        setValue(form, "P", tooLong);
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);
        assertEquals(303, answer.getWebResponse().getStatusCode(), "fixture: the requester's refusal redirects");
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertFalse(location != null && location.contains(tooLong), "the redirect must not carry a 2001-character value");
    }

    /** T-06-94: a markup value is HTML escaped: no injected element, and the field holds the literal text. */
    @Test
    public void t_06_94_prefilledValuesAreHtmlEscaped() throws Exception {
        String payload = "\"><b id=\"injected94\">x</b><script>window.injected94=1</script>";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("p.P", payload);
        p.put("p.T", "</textarea><i id=\"injected94t\">y</i>");
        HtmlPage page = page("u1", formUrl(p));
        assertNull(page.getElementById("injected94"), "the string value must not inject markup");
        assertNull(page.getElementById("injected94t"), "the text value must not break out of its textarea");
        assertFalse(page.getWebResponse().getContentAsString().contains("<b id=\"injected94\">"),
                "the raw markup must not appear unescaped");
        HtmlForm form = requestRunForm(page);
        assertEquals(payload, valueOf(form, "P"), "the field holds the literal text");
        assertEquals("</textarea><i id=\"injected94t\">y</i>", valueOf(form, "T"), "the textarea holds the literal text");
    }

    /**
     * T-06-95: the form's POST never reads {@code p.*}: a raw POST to {@code batch-control/submit}
     * with {@code ?p.P=evil} and the field {@code P=posted} stores P=posted; the same POST without a
     * P field stores the default, never "evil".
     */
    @Test
    public void t_06_95_postNeverReadsPrefillParameters() throws Exception {
        String withField = submitRaw("batch-control/submit?p.P=evil-post&p.C=third", "P", "posted");
        RunRequest first = RunRequestService.get().load(withField);
        assertEquals("posted", first.getParameters().get("P"), "the posted field wins; p.* is not read by the POST");
        assertEquals("first", first.getParameters().get("C"), "p.C on the POST must not change the stored choice");

        String withoutField = submitRaw("batch-control/submit?p.P=evil-post", null, null);
        RunRequest second = RunRequestService.get().load(withoutField);
        assertEquals("default", second.getParameters().get("P"), "without a P field the default is stored, not p.P");
        assertFalse(second.getParameters().containsValue("evil-post"));
    }

    /**
     * T-06-96: a GET with p.* by a user who may not request does not open a pre-filled form (the
     * per-job action is absent for them, SPEC 2) and stores nothing; the requester's GET (twin)
     * opens it.
     */
    @Test
    public void t_06_96_prefillIsOnlyForRequesters() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        String url = formUrl(Map.of("p.P", "nobc-marker-5Lq"));
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("nobc");
        Page answer = wc.getPage(new WebRequest(new URL(url), HttpMethod.GET));
        assertTrue(answer.getWebResponse().getStatusCode() >= 400, "nobc must not open the Request Run form, got "
                + answer.getWebResponse().getStatusCode());
        assertFalse(answer.getWebResponse().getContentAsString().contains("nobc-marker-5Lq"), "the value must not be reflected");
        assertEquals("nobc-marker-5Lq", valueOf(requestRunForm(page("u1", url)), "P"), "twin: the requester's form is filled");
        assertNothingQueuedOrStored(before);
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient jsClient(String userId) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        wc.getOptions().setJavaScriptEnabled(true);
        return wc;
    }

    /** Core's parameters form ("Build with Parameters"), opened with GET build. */
    private HtmlForm coreParametersForm(JenkinsRule.WebClient wc) throws Exception {
        HtmlPage formPage = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"),
                HttpMethod.GET));
        int code = formPage.getWebResponse().getStatusCode();
        assertTrue(code == 200 || code == 405, "fixture: the parameters form must open, got " + code);
        return formPage.getFormByName("parameters");
    }

    private HtmlPage page(String userId, String absoluteUrl) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
        Page answer = wc.getPage(new WebRequest(new URL(absoluteUrl), HttpMethod.GET));
        assertEquals(200, answer.getWebResponse().getStatusCode(), userId + " must open " + absoluteUrl);
        assertTrue(answer instanceof HtmlPage, "the Request Run form must be HTML");
        return (HtmlPage) answer;
    }

    private String formUrl(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder(new URL(j.getURL(), job.getUrl() + "batch-control/").toExternalForm());
        char sep = '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sep).append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    private HtmlForm requestRunForm(HtmlPage page) throws Exception {
        HtmlForm form = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").stream()
                .findFirst().orElse(null);
        assertNotNull(form, "the page must carry the Request Run form; forms: " + UsabilityFixtures.formActions(page));
        return form;
    }

    /** The value control of the job parameter {@code name}: core's {@code <div name="parameter">} block. */
    private static HtmlElement control(HtmlForm form, String name) {
        List<HtmlElement> blocks = form.getByXPath(".//*[@name='parameter'][.//input[@name='name' and @value='" + name + "']]");
        assertEquals(1, blocks.size(), "fixture: exactly one parameter block for " + name + ": " + excerpt(form.asXml()));
        List<HtmlElement> controls = new ArrayList<>();
        for (HtmlElement e : blocks.get(0).getHtmlElementDescendants()) {
            if ("value".equals(e.getAttribute("name")) && !"hidden".equalsIgnoreCase(e.getAttribute("type"))) {
                controls.add(e);
            }
        }
        if (controls.isEmpty()) {
            // core's password widget keeps the value in a hidden input
            for (HtmlElement e : blocks.get(0).getHtmlElementDescendants()) {
                if ("value".equals(e.getAttribute("name"))) {
                    controls.add(e);
                }
            }
        }
        assertFalse(controls.isEmpty(), "fixture: a value control for " + name + ": " + excerpt(blocks.get(0).asXml()));
        return controls.get(0);
    }

    private static String valueOf(HtmlForm form, String name) {
        HtmlElement c = control(form, name);
        if (c instanceof HtmlCheckBoxInput) {
            return String.valueOf(((HtmlCheckBoxInput) c).isChecked());
        }
        if (c instanceof HtmlSelect) {
            List<HtmlOption> selected = ((HtmlSelect) c).getSelectedOptions();
            return selected.isEmpty() ? "" : selected.get(0).getValueAttribute();
        }
        if (c instanceof HtmlTextArea) {
            return ((HtmlTextArea) c).getText();
        }
        return ((HtmlInput) c).getValue();
    }

    private static void setValue(HtmlForm form, String name, String value) {
        HtmlElement c = control(form, name);
        if (c instanceof HtmlCheckBoxInput) {
            ((HtmlCheckBoxInput) c).setChecked(Boolean.parseBoolean(value));
        } else if (c instanceof HtmlSelect) {
            ((HtmlSelect) c).setSelectedAttribute(value, true);
        } else if (c instanceof HtmlTextArea) {
            ((HtmlTextArea) c).setText(value);
        } else {
            ((HtmlInput) c).setValue(value);
        }
    }

    private static Map<String, String> query(URL url) {
        Map<String, String> out = new LinkedHashMap<>();
        if (url.getQuery() == null) {
            return out;
        }
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    /** Raw POST to the request form's endpoint (path relative to the job); returns the stored request id. */
    private String submitRaw(String jobRelative, String field, String value) throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "month-end batch"));
        params.addAll(ApproverFormFixtures.approverPairs("a1"));
        if (field != null) {
            params.add(new NameValuePair(field, value));
        }
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.post(j, "u1", job.getUrl() + jobRelative, params),
                "fixture: the raw request submission");
        Set<String> after = ApproverFormFixtures.runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: exactly one request stored, got " + after);
        return after.iterator().next();
    }

    private void assertNothingQueuedOrStored(Set<String> before) throws Exception {
        j.waitUntilNoActivity();
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no run request may be stored before the form is submitted");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
        assertTrue(job.getBuilds().isEmpty(), "no build may exist");
    }

    private static String excerpt(String text) {
        return ApproverFormFixtures.excerpt(text);
    }
}
