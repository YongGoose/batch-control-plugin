package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import jenkins.model.Jenkins;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72: a run request submission whose body is larger than the cap (default 100 MB,
 * system property {@code io.jenkins.plugins.batchcontrol.maxRequestBodyBytes}) is refused before
 * the form is read, because requesting a run does not require Item/Build (D-38a). Frozen contract:
 * the check comes after the permission check, answers HTTP 413, creates no request and leaves
 * nothing on disk. Matrix rows T-05-59 .. T-05-64 (note 260).
 *
 * <p>The property is set per test and cleared afterwards, so the plugin must read it when it checks
 * (the TriggerBlockedAuditTest convention; Request in the deliverable report). The body size of a
 * browser submission is measured on the server side by a test servlet filter that records the
 * {@code Content-Length} of each POST to the submit endpoint, so "just under the cap" is a measured
 * premise rather than an estimate of the multipart overhead.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72 and the frozen D-72 contract only
 * (no src/main knowledge).
 */
@WithJenkins
public class RequestBodyCapTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";
    private static final long CAP = 64 * 1024;

    private JenkinsRule j;
    private FreeStyleProject job;
    private final LengthRecorder recorder = new LengthRecorder();

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ).everywhere().to("plain"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createFreeStyleProject("cap-x");
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        PluginServletFilter.addFilter(recorder);
    }

    @AfterEach
    public void tearDown() throws Exception {
        System.clearProperty(CAP_PROPERTY);
        PluginServletFilter.removeFilter(recorder);
    }

    /**
     * T-05-59: with the cap set to 64 KiB, the Request Run form submitted with an 80 KiB file
     * answers 413; no request is created, no temporary file is left under the two documented
     * directories, nothing is queued.
     */
    @Test
    public void t_05_59_overSizeMultipartSubmissionIsRefusedWith413() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);

        Page answer = submitForm("u1", payload("over-cap-marker-Bv20", (int) CAP + 16 * 1024));

        assertEquals(413, answer.getWebResponse().getStatusCode(), "a submission larger than the cap must answer 413: "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        assertTrue(lastLength() > CAP, "premise: the measured body was larger than the cap, was " + lastLength());
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(tempBefore, tempFiles(j), "no temporary file may be left behind");
        assertNothingRan();
    }

    /**
     * T-05-60 (guard of T-05-59): the same form with a file chosen so that the measured body is
     * just under the cap (within 2 KiB of it) is accepted: one PENDING request holding the file.
     */
    @Test
    public void t_05_60_submissionJustUnderTheCapIsAccepted() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        int probeSize = 1024;
        submitForm("u1", payload("probe-marker-Nc55", probeSize));
        long overhead = lastLength() - probeSize;
        assertTrue(overhead > 0 && overhead < CAP / 2, "premise: the form's own overhead was measured, was " + overhead);

        Set<String> before = ApproverFormFixtures.runRequestIds();
        int size = (int) (CAP - overhead - 512);
        Page answer = submitForm("u1", payload("under-cap-marker-Qe08", size));

        long measured = lastLength();
        assertTrue(measured <= CAP && measured >= CAP - 2048, "premise: the body must be just under the cap, was "
                + measured + " for a cap of " + CAP);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "a submission under the cap must be accepted, got HTTP "
                + answer.getWebResponse().getStatusCode());
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "exactly one request must be created, got " + created);
        String id = created.iterator().next();
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus());
        assertEquals(fileDisplay("data.csv"), RunRequestService.get().load(id).getParameters().get("UPLOAD"));
    }

    /**
     * T-05-61: the cap is applied before the form is read: an over-size body that is not even valid
     * multipart (a boundary that never occurs) still answers 413, not a parse error; an over-size
     * url-encoded submission with valid fields answers 413 too. Neither stores a request.
     */
    @Test
    public void t_05_61_capIsCheckedBeforeTheFormIsRead() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");

        WebRequest garbage = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        garbage.setAdditionalHeader("Content-Type", "multipart/form-data; boundary=never-in-the-body-3Kf");
        garbage.setCharset(StandardCharsets.US_ASCII);
        garbage.setRequestBody("x".repeat((int) CAP + 8 * 1024));
        assertEquals(413, wc.getPage(garbage).getWebResponse().getStatusCode(),
                "an over-size body must be refused with 413 before any attempt to parse it");

        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", "month-end batch"));
        fields.add(new NameValuePair("approvers", "a1"));
        fields.add(new NameValuePair("DATE", "d".repeat((int) CAP + 8 * 1024)));
        WebRequest encoded = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        encoded.setRequestParameters(fields);
        assertEquals(413, wc.getPage(encoded).getWebResponse().getStatusCode(),
                "an over-size url-encoded submission must be refused with 413 as well");

        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(tempBefore, tempFiles(j), "no temporary file may be left behind");
        assertNothingRan();
    }

    /**
     * T-05-62: the permission check comes first: a user without BatchControl/Request posting the
     * same over-size body gets the per-job action's absence (404, SPEC item 2), not 413, and
     * nothing is stored.
     */
    @Test
    public void t_05_62_permissionCheckComesBeforeTheCap() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        Set<String> before = ApproverFormFixtures.runRequestIds();
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", "month-end batch"));
        fields.add(new NameValuePair("approvers", "a1"));
        fields.add(new NameValuePair("DATE", "d".repeat((int) CAP + 8 * 1024)));

        WebResponse answer = ApproverFormFixtures.post(j, "plain", job.getUrl() + "batch-control/submit", fields);
        assertEquals(404, answer.getStatusCode(),
                "a user who may not request must find the action absent (404), not learn about the cap (413)");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertNothingRan();
    }

    /**
     * T-05-63: without the property the default cap (100 MB) applies, so an ordinary 1 MiB upload
     * through the Request Run form is accepted.
     */
    @Test
    public void t_05_63_defaultCapAcceptsAnOrdinaryUpload() throws Exception {
        System.clearProperty(CAP_PROPERTY);
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page answer = submitForm("u1", payload("default-cap-marker-Wd61", 1024 * 1024));
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "a 1 MiB upload must be accepted under the default cap, got HTTP "
                + answer.getWebResponse().getStatusCode());
        assertTrue(lastLength() > 1024 * 1024, "premise: the measured body carried the 1 MiB file, was " + lastLength());
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "exactly one request must be created, got " + created);
    }

    /**
     * T-05-64 (SPEC section 6, CSRF): a well-formed multipart submission under the cap but without a
     * crumb is refused with 403; no request is created and no temporary file is left behind.
     */
    @Test
    public void t_05_64_multipartSubmissionWithoutCrumbIsRefused() throws Exception {
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "u1");
        WebRequest request = new WebRequest(new URL(j.getURL(), job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", "month-end batch"));
        fields.add(new NameValuePair("approvers", "a1"));
        fields.add(new NameValuePair("DATE", "2026-10-01"));
        fields.add(new KeyDataPair("UPLOAD", uploadFile("data.csv", payload("no-crumb-marker-Yt47", 2048)), "data.csv",
                "application/octet-stream", StandardCharsets.UTF_8));
        request.setRequestParameters(fields);

        assertEquals(403, wc.getPage(request).getWebResponse().getStatusCode(), "a multipart state change without a crumb must be refused");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(tempBefore, tempFiles(j), "no temporary file may be left behind");
    }

    // ---------------------------------------------------------------- helpers

    /** Submits the Request Run form as {@code userId} with {@code content} chosen as {@code data.csv}. */
    private Page submitForm(String userId, byte[] content) throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, userId);
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        TypedParameterFixtures.setValue(form, "DATE", "2026-10-01");
        TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("data.csv", content));
        return TypedParameterFixtures.submit(wc, form, "month-end batch", "a1");
    }

    private long lastLength() {
        assertTrue(!recorder.lengths.isEmpty(), "fixture: the submit POST must have been seen by the recording filter");
        return recorder.lengths.get(recorder.lengths.size() - 1);
    }

    private void assertNothingRan() throws Exception {
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
    }

    /** Records the Content-Length of every POST to a run request submit endpoint. */
    private static final class LengthRecorder implements Filter {
        final List<Long> lengths = new CopyOnWriteArrayList<>();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request instanceof HttpServletRequest http && "POST".equals(http.getMethod())
                    && http.getRequestURI().endsWith("/batch-control/submit")) {
                lengths.add(http.getContentLengthLong());
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
            // nothing to release
        }

        @Override
        public void init(jakarta.servlet.FilterConfig config) {
            // nothing to configure
        }
    }
}
