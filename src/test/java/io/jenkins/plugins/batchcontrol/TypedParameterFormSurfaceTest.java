package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedStashPipeline;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fallbackTarget;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.formUrl;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.noticeText;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.notices;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.postRerun;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The D-72 surface of the Request Run form (ui-dev 68f52c3, as documented in its commit message,
 * LIMITATIONS items 16, 31 and 49 and the coordinator's list; matrix note 263): the 413 answer of
 * an over-size submission re-renders the form with the reason, on the page and in the dialog
 * ({@code submit?dialog=true}); the notices marked {@code data-batch-control-notice} ({@code rerun}
 * on the rerun fallback form, {@code prefilled} on the D-60 carry-over, {@code reenter} on a 400
 * re-render); and the crumb on the multipart submission. Matrix rows T-UI-113 .. T-UI-118.
 *
 * <p>Message wording is not pinned: the size message is found as a line the 413 answer adds to the
 * form, matched case-insensitively against size words; notices are found by their marker and must
 * name the parameters concerned.
 *
 * <p>Written from docs/SPEC.md items 5, 6 and 11 and section 6 (usability, CSRF), docs/DECISIONS.md
 * D-60, D-72 and D-72a, docs/LIMITATIONS.md and the frozen D-72 contract only (no src/main
 * knowledge).
 */
@WithJenkins
public class TypedParameterFormSurfaceTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";
    private static final long CAP = 64 * 1024;
    private static final Pattern SIZE = Pattern.compile("(?i)(too large|too big|larger than|exceed|size|limit|maximum)");
    private static final Pattern SELECT_AGAIN = Pattern.compile("(?i)select the files? again");
    private static final Pattern ENCRYPTED = Pattern.compile("\\{[A-Za-z0-9+/=]{16,}\\}");
    private static final String TYPED_SECRET = "reenter-typed-s3cr3t-263";
    private static final String SECRET_DEFAULT = "reenter-d3fault-263";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void clearCap() {
        System.clearProperty(CAP_PROPERTY);
    }

    /**
     * T-UI-113: with the cap at 64 KiB, u1 submits the Request Run page (multipart) with an 80 KiB
     * file: the answer is 413 and an HTML page that carries the Request Run form again (a page
     * form, not the dialog's: its action has no {@code dialog=true}) and a line, absent from the
     * plain form, that explains the size; no crash page; nothing created or kept. Guard: the plain
     * form has no such line (it is the baseline).
     */
    @Test
    public void t_ui_113_overSizePageSubmissionRerendersTheFormWithTheSizeMessage() throws Exception {
        FreeStyleProject job = capJob("cap-page");
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        Set<String> baseline = lines(((HtmlPage) form.getPage()).asNormalizedText());
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);

        TypedParameterFixtures.setValue(form, "DATE", "2026-10-01");
        TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", payload("page-413-marker-Lk40", (int) CAP + 16 * 1024)));
        Page answer = TypedParameterFixtures.submit(wc, form, "month-end batch", "a1");

        assertEquals(413, answer.getWebResponse().getStatusCode(), "an over-size submission must answer 413");
        assertTrue(answer instanceof HtmlPage, "the 413 answer must be an HTML page, got " + answer.getWebResponse().getContentType());
        HtmlPage page = (HtmlPage) answer;
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit");
        assertFalse(forms.isEmpty(), "the 413 answer must carry the Request Run form again: " + UsabilityFixtures.formActions(page));
        assertFalse(forms.get(0).getActionAttribute().contains("dialog=true"), "the page re-render must not post as the dialog: "
                + forms.get(0).getActionAttribute());
        assertSizeMessage(page, baseline);
        UsabilityFixtures.assertNotBareErrorPage("the 413 page", page);
        UsabilityFixtures.assertPlainRefusal("the 413 page", page.asNormalizedText(), SIZE);
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(tempBefore, tempFiles(j), "nothing may be kept under the temporary directories");
    }

    /**
     * T-UI-114: the same over-size submission posted as the dialog ({@code submit?dialog=true},
     * multipart, crumb in the URL) answers 413 with the Request Run form again, still posting as
     * the dialog ({@code dialog=true} in its action), and a line, absent from the dialog fragment
     * ({@code job/<j>/batch-control/dialog}), that explains the size; nothing created or kept.
     */
    @Test
    public void t_ui_114_overSizeDialogSubmissionRerendersTheDialogFormWithTheSizeMessage() throws Exception {
        FreeStyleProject job = capJob("cap-dialog");
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        HtmlPage fragment = UsabilityFixtures.htmlPage(j, "u1", job.getUrl() + "batch-control/dialog");
        assertEquals(200, fragment.getWebResponse().getStatusCode(), "fixture: the dialog fragment must open");
        Set<String> baseline = lines(fragment.asNormalizedText());
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);

        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit?dialog=true"), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setRequestParameters(multipartFields(payload("dialog-413-marker-Pz17", (int) CAP + 16 * 1024)));
        Page answer = wc.getPage(request);

        assertEquals(413, answer.getWebResponse().getStatusCode(), "an over-size dialog submission must answer 413");
        assertTrue(answer instanceof HtmlPage, "the 413 answer must be HTML, got " + answer.getWebResponse().getContentType());
        HtmlPage page = (HtmlPage) answer;
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit");
        assertFalse(forms.isEmpty(), "the 413 answer must carry the Request Run form again: " + UsabilityFixtures.formActions(page));
        assertTrue(forms.get(0).getActionAttribute().contains("dialog=true"), "the dialog re-render must keep posting as the dialog: "
                + forms.get(0).getActionAttribute());
        assertSizeMessage(page, baseline);
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(tempBefore, tempFiles(j), "nothing may be kept under the temporary directories");
    }

    /**
     * T-UI-115: the Request Run form reached through an incident rerun's fallback (stashed file
     * DATA, password TOKEN) carries a {@code rerun} notice naming DATA and TOKEN, the values to
     * provide again, and saying the request will be linked to the incident (D-72a; the old
     * "is not linked to the incident" sentence is gone). Guards: the bare form and the same form prefilled without {@code fromRerun}
     * carry no {@code rerun} notice.
     */
    @Test
    public void t_ui_115_rerunFallbackFormNamesTheValuesToProvideAgain() throws Exception {
        WorkflowJob job = failedStashPipeline(j, "notice-rerun", "notice-rerun-marker-Hy03");
        Incident incident = incidentFor("notice-rerun#1");
        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));

        HtmlPage fallback = page("u1", target);
        assertFalse(notices(fallback, "rerun").isEmpty(), "the fallback form must carry a rerun notice: "
                + UsabilityFixtures.excerpt(fallback.asNormalizedText()));
        String text = noticeText(fallback, "rerun");
        assertTrue(text.contains("DATA"), "the rerun notice must name the file parameter to provide again: " + text);
        assertTrue(text.contains("TOKEN"), "the rerun notice must name the password parameter to provide again: " + text);
        assertTrue(text.contains(RerunFallbackFixtures.LINKED), "the rerun notice must say the request "
                + RerunFallbackFixtures.LINKED + " (D-72a): " + text);
        assertFalse(fallback.asNormalizedText().contains(RerunFallbackFixtures.NOT_LINKED),
                "the pre-D-72a sentence saying the request is not linked must be gone");

        assertTrue(notices(page("u1", formUrl(j, job, Map.of(), null)), "rerun").isEmpty(),
                "guard: the bare Request Run form carries no rerun notice");
        assertTrue(notices(page("u1", formUrl(j, job, Map.of("DATE", "2026-10-01"), null)), "rerun").isEmpty(),
                "guard: a prefilled form without an incident reference carries no rerun notice");
    }

    /**
     * T-UI-116 (D-60, issue #115): u1's refused build form submission on a job with a core file
     * UPLOAD, a stashed file DATA and a string leads to the Request Run form with a
     * {@code prefilled} notice that names UPLOAD and DATA and says to select the file again.
     * Guards: the same refusal on a job without file parameters leads to a form that does not say
     * so, and the bare form of the file job carries no {@code prefilled} notice.
     */
    @Test
    public void t_ui_116_d60CarryOverSaysToSelectTheFilesAgain() throws Exception {
        FreeStyleProject files = d60Job("notice-files", true);
        HtmlPage carried = page("u1", refusedBuildTarget(files, true));
        assertFalse(notices(carried, "prefilled").isEmpty(), "the carried-over form must carry a prefilled notice: "
                + UsabilityFixtures.excerpt(carried.asNormalizedText()));
        String text = noticeText(carried, "prefilled");
        assertTrue(text.contains("UPLOAD") && text.contains("DATA"), "the notice must name each file parameter: " + text);
        assertTrue(SELECT_AGAIN.matcher(text).find(), "the notice must say to select the file again: " + text);

        FreeStyleProject plain = d60Job("notice-plain", false);
        HtmlPage plainCarried = page("u1", refusedBuildTarget(plain, false));
        assertFalse(SELECT_AGAIN.matcher(plainCarried.asNormalizedText()).find(),
                "guard: without file parameters the form must not ask to select a file again");
        assertTrue(notices(page("u1", formUrl(j, files, Map.of(), null)), "prefilled").isEmpty(),
                "guard: the bare form carries no prefilled notice");
    }

    /**
     * T-UI-117: u1 submits the Request Run form (multipart) with a file for UPLOAD, a typed
     * password for TOKEN and BATCH_DAY, but no reason: 400, the form again with a {@code reenter}
     * notice naming UPLOAD and TOKEN as values to provide again; the page holds the typed password
     * neither in plaintext nor in an encrypted value that decrypts to it; BATCH_DAY is kept (SPEC
     * usability: the user's input is kept); no request; no upload kept under the temporary
     * directories. Guard: the plain form carries no {@code reenter} notice.
     */
    @Test
    public void t_ui_117_refusedSubmissionAsksForPasswordsAndFilesAgain() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reenter-x");
        job.addProperty(new ParametersDefinitionProperty(
                new FileParameterDefinition("UPLOAD", "core file"),
                new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token"),
                new StringParameterDefinition("BATCH_DAY", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        assertTrue(notices((HtmlPage) form.getPage(), "reenter").isEmpty(), "guard: the plain form carries no reenter notice");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);

        TypedParameterFixtures.setValue(form, "BATCH_DAY", "2026-10-03");
        TypedParameterFixtures.setValue(form, "TOKEN", TYPED_SECRET);
        TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", payload("reenter-marker-Jc81", 2000)));
        Page answer = TypedParameterFixtures.submit(wc, form, "", "a1");

        assertEquals(400, answer.getWebResponse().getStatusCode(), "a submission without a reason must answer 400");
        assertTrue(answer instanceof HtmlPage, "the refusal must be an HTML page");
        HtmlPage page = (HtmlPage) answer;
        assertFalse(UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").isEmpty(),
                "the refusal must show the form again: " + UsabilityFixtures.formActions(page));
        assertFalse(notices(page, "reenter").isEmpty(), "the re-render must carry a reenter notice: "
                + UsabilityFixtures.excerpt(page.asNormalizedText()));
        String text = noticeText(page, "reenter");
        assertTrue(text.contains("UPLOAD") && text.contains("TOKEN"), "the notice must name the file and the password to provide again: " + text);
        String source = page.getWebResponse().getContentAsString();
        assertFalse(source.contains(TYPED_SECRET), "the re-render must not show the typed password");
        assertFalse(decryptsTo(source, TYPED_SECRET), "the re-render must not carry the typed password in encrypted form either");
        assertTrue(UsabilityFixtures.pageKeepsValue(page, "2026-10-03"), "the plain value the user typed must be kept: "
                + UsabilityFixtures.excerpt(page.asNormalizedText()));
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(Set.of(), added(tempBefore, tempFiles(j)), "a refused submission must not keep its upload");
    }

    /**
     * T-UI-118 (SPEC section 6, CSRF; positive twin of T-05-64): a well-formed multipart submission
     * with the crumb in the request header is accepted (one request), and so is one with the crumb
     * in the query; with a wrong crumb in the header or the query it is refused with 403 and
     * nothing is created or kept.
     */
    @Test
    public void t_ui_118_multipartSubmissionAcceptsTheCrumbInHeaderOrQuery() throws Exception {
        FreeStyleProject job = capJob("crumb-x");
        String submit = job.getUrl() + "batch-control/submit";
        NameValuePair crumb = crumbHeader();
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");

        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);
        WebRequest wrongHeader = multipart(new URL(j.getURL(), submit), "wrong-header");
        wrongHeader.setAdditionalHeader(crumb.getName(), "not-a-valid-crumb-263");
        assertEquals(403, wc.getPage(wrongHeader).getWebResponse().getStatusCode(), "a wrong crumb in the header must be refused");
        WebRequest wrongQuery = multipart(new URL(j.getURL(), submit + "?" + crumb.getName() + "=not-a-valid-crumb-263"), "wrong-query");
        assertEquals(403, wc.getPage(wrongQuery).getWebResponse().getStatusCode(), "a wrong crumb in the query must be refused");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "a refused submission creates nothing");
        assertEquals(tempBefore, tempFiles(j), "a refused submission keeps nothing");

        WebRequest header = multipart(new URL(j.getURL(), submit), "header");
        header.setAdditionalHeader(crumb.getName(), crumb.getValue());
        Set<String> beforeHeader = ApproverFormFixtures.runRequestIds();
        assertAccepted(wc.getPage(header), beforeHeader, "the crumb in the header");
        Set<String> beforeQuery = ApproverFormFixtures.runRequestIds();
        assertAccepted(wc.getPage(multipart(wc.createCrumbedUrl(submit), "query")), beforeQuery, "the crumb in the query");
    }

    // ---------------------------------------------------------------- helpers

    /** The crumb as the {@code (field name, value)} pair a script sends in a request header. */
    private NameValuePair crumbHeader() {
        hudson.security.csrf.CrumbIssuer issuer = j.jenkins.getCrumbIssuer();
        assertNotNull(issuer, "fixture: CSRF protection must be on");
        return new NameValuePair(issuer.getDescriptor().getCrumbRequestField(), issuer.getCrumb((jakarta.servlet.ServletRequest) null));
    }

    /** An approval-required Freestyle job with a core file UPLOAD and a string DATE. */
    private FreeStyleProject capJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** As RefusedBuildPrefillTest's job: approval-required, activated, timer and upstream doors open. */
    private FreeStyleProject d60Job(String name, boolean withFiles) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        BatchControlFixtures.activate(job);
        if (withFiles) {
            job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "core file"),
                    new StashedFileParameterDefinition("DATA"), new StringParameterDefinition("DATE", "2000-01-01")));
        } else {
            job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", "2000-01-01")));
        }
        return job;
    }

    /** u1 submits core's parameters form of {@code job} (files when asked, DATE); returns the 303 target. */
    private URL refusedBuildTarget(FreeStyleProject job, boolean withFiles) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        wc.getOptions().setJavaScriptEnabled(true);
        HtmlPage formPage = (HtmlPage) wc.getPage(new WebRequest(new URL(j.getURL(), job.getUrl() + "build?delay=0sec"), HttpMethod.GET));
        HtmlForm form = formPage.getFormByName("parameters");
        TypedParameterFixtures.setValue(form, "DATE", "2026-10-02");
        if (withFiles) {
            byte[] content = payload("d60-notice-marker-Xe55", 1500);
            TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", content));
            TypedParameterFixtures.setFile(form, "DATA", uploadFile("report.bin", content));
        }
        wc.getOptions().setRedirectEnabled(false);
        Page answer = j.submit(form);
        assertEquals(303, answer.getWebResponse().getStatusCode(), "fixture: a requester's refused build redirects (D-60)");
        String location = answer.getWebResponse().getResponseHeaderValue("Location");
        assertNotNull(location, "fixture: the 303 must carry a Location");
        return new URL(answer.getUrl(), location);
    }

    private HtmlPage page(String userId, URL url) throws Exception {
        Page answer = UsabilityFixtures.clientNoJs(j, userId).getPage(url);
        assertEquals(200, answer.getWebResponse().getStatusCode(), userId + " must open " + url);
        assertTrue(answer instanceof HtmlPage, url + " must be HTML");
        return (HtmlPage) answer;
    }

    private static Set<String> lines(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    /** Some line the answer adds to the baseline explains the size. */
    private static void assertSizeMessage(HtmlPage page, Set<String> baseline) {
        List<String> fresh = new ArrayList<>(lines(page.asNormalizedText()));
        fresh.removeAll(baseline);
        assertTrue(fresh.stream().anyMatch(l -> SIZE.matcher(l).find()), "the 413 answer must explain the size in a line the plain"
                + " form does not have; new lines were " + fresh);
    }

    private static List<NameValuePair> multipartFields(byte[] content) throws Exception {
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", "month-end batch"));
        fields.add(new NameValuePair("approvers", "a1"));
        fields.add(new NameValuePair("DATE", "2026-10-01"));
        fields.add(new KeyDataPair("UPLOAD", uploadFile("data.csv", content), "data.csv", "application/octet-stream",
                StandardCharsets.UTF_8));
        return fields;
    }

    private static WebRequest multipart(URL url, String marker) throws Exception {
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setRequestParameters(multipartFields(payload("crumb-marker-" + marker, 1024)));
        return request;
    }

    private static void assertAccepted(Page answer, Set<String> before, String what) {
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code < 400, what + ": the multipart submission must be accepted, got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), what + ": exactly one request must be created, got " + created);
    }

    private static boolean decryptsTo(String source, String plaintext) {
        Matcher m = ENCRYPTED.matcher(source);
        while (m.find()) {
            Secret secret = Secret.decrypt(m.group());
            if (secret != null && plaintext.equals(secret.getPlainText())) {
                return true;
            }
        }
        return false;
    }
}
