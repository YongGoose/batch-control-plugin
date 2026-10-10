package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RequestTooLargeException;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
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
import java.util.Map;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72: a run request submission whose body is larger than the cap (default 100 MB,
 * system property {@code io.jenkins.plugins.batchcontrol.maxRequestBodyBytes}) is refused before
 * the form is read, because requesting a run does not require Item/Build (D-38a). Frozen contract:
 * the check comes after the permission check, answers HTTP 413, creates no request and leaves
 * nothing on disk. Matrix rows T-05-59 .. T-05-64 (note 260); T-05-94 (the exact default) and
 * T-05-95 (a chunked body is judged by its actual size, D-72b (4)) use {@link RawHttpFixtures}
 * (note 265). T-05-98 .. T-05-100 (security-37 S-37-01, note 265 addendum): what a request keeps
 * (file contents, decoded Base64, stored texts) is held to the cap whatever names, encodings or part
 * kinds a chunked body uses, and the service refuses it with {@code RequestTooLargeException}.
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
    @Tag("core")
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
    @Tag("core")
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

    /**
     * T-05-94 (S7 m-2): with the property unset, the cap is exactly 104,857,600 bytes. A
     * submission that declares a length of 104,857,601 bytes is refused with 413 at once (decided
     * from the declared length, no body sent); one that declares exactly 104,857,600 bytes is not
     * refused by the cap (any answer but 413, and without waiting for the body). Neither creates a
     * request.
     */
    @Test
    public void t_05_94_defaultCapIsExactly104857600Bytes() throws Exception {
        System.clearProperty(CAP_PROPERTY);
        Set<String> before = ApproverFormFixtures.runRequestIds();
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));

        int over = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth),
                RawHttpFixtures.header("Content-Type", "application/octet-stream"),
                RawHttpFixtures.header("Content-Length", "104857601")), new byte[0], false);
        assertEquals(413, over, "a declared length one byte over the default cap of 104857600 must answer 413");

        int exact = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth),
                RawHttpFixtures.header("Content-Type", "application/octet-stream"),
                RawHttpFixtures.header("Content-Length", "104857600")), new byte[0], false);
        assertNotEquals(413, exact, "a declared length of exactly the default cap must not be refused by the cap");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "neither probe may create a request");
        assertNothingRan();
    }

    /**
     * T-05-95 (D-72b (4), S7 m-3/O-1): with the cap at 64 KiB, a multipart submission sent chunked
     * (no declared length) is judged by its actual size: a 1 KiB file is accepted (one request,
     * {@code [file] data.csv}); an 80 KiB file is refused with 413, creates no request and keeps no
     * temporary file. Premise: the same small body sent with a Content-Length is accepted (the raw
     * channel and the body are valid).
     */
    @Test
    public void t_05_95_chunkedBodyIsJudgedByItsActualSize() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));
        String boundary = "d72b-chunked-boundary-Yk95";
        String type = "multipart/form-data; boundary=" + boundary;

        byte[] small = chunkedBody(boundary, payload("chunked-small-marker-Lq95", 1024));
        Set<String> before = ApproverFormFixtures.runRequestIds();
        int declared = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth), RawHttpFixtures.header("Content-Type", type),
                RawHttpFixtures.header("Content-Length", Integer.toString(small.length))), small, false);
        assertTrue(declared < 400, "premise: the small body with a declared length must be accepted, got HTTP " + declared);
        assertEquals(1, created(before).size(), "premise: the declared-length body creates one request");

        before = ApproverFormFixtures.runRequestIds();
        int chunkedSmall = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth), RawHttpFixtures.header("Content-Type", type)), small, true);
        assertTrue(chunkedSmall < 400, "a chunked body under the cap must be accepted, got HTTP " + chunkedSmall);
        Set<String> one = created(before);
        assertEquals(1, one.size(), "the chunked body under the cap must create exactly one request, got " + one);
        assertEquals(fileDisplay("data.csv"), RunRequestService.get().load(one.iterator().next()).getParameters().get("UPLOAD"));

        before = ApproverFormFixtures.runRequestIds();
        Set<Path> tempBefore = tempFiles(j);
        byte[] large = chunkedBody(boundary, payload("chunked-large-marker-Ms95", (int) CAP + 16 * 1024));
        int chunkedLarge = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth), RawHttpFixtures.header("Content-Type", type)), large, true);
        assertEquals(413, chunkedLarge, "a chunked body over the cap must answer 413");
        assertEquals(Set.of(), created(before), "a chunked body over the cap must create no request");
        assertEquals(tempBefore, tempFiles(j), "a chunked body over the cap must keep nothing under the temporary directories");
        assertNothingRan();
    }

    /**
     * T-05-98 (security-37 S-37-01, numeric references): with the cap at 64 KiB, a chunked multipart
     * body whose {@code json} refers to two ~400 KiB parts by the JSON numbers {@code 7} (core file
     * UPLOAD, {@code data.csv}) and {@code 8} (base64File B64, {@code data.bin}) answers 413, creates
     * no request and keeps nothing: neither {@code fileParameterValueFiles/} nor
     * {@code requests/run/} gains a file, nothing is queued. Guard: the same encoding with 1 KiB parts
     * is not refused by the cap (so the 413 is the size, not the encoding). The string-reference
     * control (an over-size chunked body naming its part) is T-05-95.
     */
    @Test
    public void t_05_98_chunkedPartsReferencedByNumbersAreHeldToTheCap() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        FreeStyleProject two = twoFileJob("cap-num");
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));
        String boundary = "s37-numeric-boundary-Rn98";

        byte[] small = numericReferenceBody(boundary, payload("s37-num-small-csv-Ka98", 1024), payload("s37-num-small-bin-Kb98", 1024));
        int smallAnswer = postChunked(two, auth, boundary, small);
        assertNotEquals(413, smallAnswer, "guard: numeric references under the cap must not be refused by the cap");
        assertTrue(smallAnswer < 500, "guard: numeric references under the cap must not crash, got HTTP " + smallAnswer);

        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);
        byte[] large = numericReferenceBody(boundary, payload("s37-num-large-csv-La98", 400 * 1024),
                payload("s37-num-large-bin-Lb98", 400 * 1024));
        assertTrue(large.length > 2 * 400 * 1024, "premise: the body carries both 400 KiB parts, was " + large.length);

        assertEquals(413, postChunked(two, auth, boundary, large),
                "parts referenced by JSON numbers must count against the cap: 413 expected");
        assertKeptNothing(two, ids, listing, temp);
    }

    /**
     * T-05-99 (security-37 S-37-01, {@code json} as a file part): with the cap at 64 KiB, a chunked
     * multipart body with a small form-field {@code json} (naming only DATE) followed by a file part
     * named {@code json} that refers to two ~400 KiB parts {@code blob} (UPLOAD, {@code data.csv})
     * and {@code blob2} (B64, {@code data.bin}) answers 413, creates no request and keeps nothing
     * under {@code fileParameterValueFiles/} or {@code requests/run/}; nothing is queued.
     */
    @Test
    public void t_05_99_jsonSentAsAFilePartAfterADecoyIsHeldToTheCap() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        FreeStyleProject two = twoFileJob("cap-decoy");
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));
        String boundary = "s37-decoy-boundary-Dj99";

        String decoy = requestJson("[{\"name\":\"DATE\",\"value\":\"2026-10-01\"}]");
        String real = requestJson("[{\"name\":\"UPLOAD\",\"file\":\"blob\"},{\"name\":\"B64\",\"file\":\"blob2\"},"
                + "{\"name\":\"DATE\",\"value\":\"2026-10-01\"}]");
        List<Object[]> parts = new ArrayList<>();
        parts.add(RawHttpFixtures.part("json", null, decoy.getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("json", "json", real.getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("blob", "data.csv", payload("s37-decoy-csv-Ma99", 400 * 1024)));
        parts.add(RawHttpFixtures.part("blob2", "data.bin", payload("s37-decoy-bin-Mb99", 400 * 1024)));
        byte[] body = RawHttpFixtures.multipart(boundary, parts);

        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);
        assertEquals(413, postChunked(two, auth, boundary, body),
                "a file-part json after a form-field decoy must not escape the cap: 413 expected");
        assertKeptNothing(two, ids, listing, temp);
    }

    /**
     * T-05-100 (security-37 S-37-01, service): with the cap at 64 KiB, {@code
     * RunRequestService.create(job, values, ...)} refuses with {@code RequestTooLargeException} (an
     * {@code IllegalArgumentException}) a core {@code FileParameterValue} of 80 KiB, a
     * {@code base64File} value of 80 KiB (decoded), and the two together at 36 KiB each (the cap
     * bounds the sum); no request is created, {@code requests/run/} is unchanged and nothing new is
     * kept under the temporary directories. Guard: the same values at 16 KiB each are accepted
     * (PENDING, shown as {@code [file] small.csv} and {@code [file] small.bin}).
     */
    @Test
    public void t_05_100_serviceRefusesKeptContentOverTheCap() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        FreeStyleProject svc = twoFileJob("cap-svc");
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);
        int over = (int) CAP + 16 * 1024;
        int half = (int) CAP / 2 + 4 * 1024;

        assertThrows(RequestTooLargeException.class, () -> createAsU1(svc, List.of(
                coreFile("data.csv", payload("s37-svc-core-over-Na00", over)), date())),
                "a core file value over the cap must be refused with RequestTooLargeException");
        assertThrows(RequestTooLargeException.class, () -> createAsU1(svc, List.of(
                base64File("data.bin", payload("s37-svc-b64-over-Nb00", over)), date())),
                "a base64File value whose decoded size is over the cap must be refused with RequestTooLargeException");
        assertThrows(RequestTooLargeException.class, () -> createAsU1(svc, List.of(
                coreFile("data.csv", payload("s37-svc-core-half-Nc00", half)),
                base64File("data.bin", payload("s37-svc-b64-half-Nd00", half)), date())),
                "two file values that together exceed the cap must be refused with RequestTooLargeException");
        assertKeptNothing(svc, ids, listing, temp);

        RunRequest ok = createAsU1(svc, List.of(coreFile("small.csv", payload("s37-svc-core-ok-Ne00", (int) CAP / 4)),
                base64File("small.bin", payload("s37-svc-b64-ok-Nf00", (int) CAP / 4)), date()));
        assertEquals(RequestStatus.PENDING, ok.getStatus(), "guard: the same values under the cap are accepted");
        Map<String, String> shown = RunRequestService.get().load(ok.getId()).getParameters();
        assertEquals(fileDisplay("small.csv"), shown.get("UPLOAD"), "guard: the accepted request shows the core file");
        assertEquals(fileDisplay("small.bin"), shown.get("B64"), "guard: the accepted request shows the base64File");
    }

    // ---------------------------------------------------------------- helpers

    /** The Request Run fields (reason, approver, DATE) and {@code data.csv} as UPLOAD, as multipart. */
    private static byte[] chunkedBody(String boundary, byte[] file) throws IOException {
        List<Object[]> parts = new ArrayList<>();
        parts.add(RawHttpFixtures.part("reason", null, "month-end batch".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("approvers", null, "a1".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("DATE", null, "2026-10-01".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("UPLOAD", "data.csv", file));
        return RawHttpFixtures.multipart(boundary, parts);
    }

    /** An approval-required Freestyle job with a core file UPLOAD, a base64File B64 and a string DATE. */
    private FreeStyleProject twoFileJob(String name) throws Exception {
        FreeStyleProject two = j.createFreeStyleProject(name);
        two.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(two, new BatchControlJobProperty(true));
        return two;
    }

    /** Core's {@code json} form value: reason, approver and the given parameter array. */
    private static String requestJson(String parameterArray) {
        return "{\"reason\":\"month-end batch\",\"approvers\":\"a1\",\"parameter\":" + parameterArray + "}";
    }

    /** {@code json} naming UPLOAD's part by the number 7 and B64's by 8, then the two parts. */
    private static byte[] numericReferenceBody(String boundary, byte[] csv, byte[] bin) throws IOException {
        String json = requestJson("[{\"name\":\"UPLOAD\",\"file\":7},{\"name\":\"B64\",\"file\":8},"
                + "{\"name\":\"DATE\",\"value\":\"2026-10-01\"}]");
        List<Object[]> parts = new ArrayList<>();
        parts.add(RawHttpFixtures.part("json", null, json.getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("7", "data.csv", csv));
        parts.add(RawHttpFixtures.part("8", "data.bin", bin));
        return RawHttpFixtures.multipart(boundary, parts);
    }

    /** POSTs {@code body} to {@code target}'s submit endpoint chunked (no declared length) and returns the status. */
    private int postChunked(FreeStyleProject target, String auth, String boundary, byte[] body) throws IOException {
        return RawHttpFixtures.post(j.getURL(), target.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth),
                RawHttpFixtures.header("Content-Type", "multipart/form-data; boundary=" + boundary)), body, true);
    }

    /** No request created, {@code requests/run/} unchanged, nothing new under the temporary directories, nothing ran. */
    private void assertKeptNothing(FreeStyleProject target, Set<String> ids, Set<String> listing, Set<Path> temp) throws Exception {
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertEquals(listing, requestDirListing(j), "no file of any name may be added under requests/run/");
        assertEquals(temp, tempFiles(j), "nothing may be kept under fileParameterValueFiles/ or stashedFileParameterValueFiles/");
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, target.getNextBuildNumber(), "no build number may have been consumed");
    }

    private static RunRequest createAsU1(FreeStyleProject target, List<ParameterValue> values) {
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            return RunRequestService.get().create(target, values, "month-end batch", "a1");
        }
    }

    private static ParameterValue coreFile(String fileName, byte[] content) throws IOException {
        return new FileParameterValue("UPLOAD", uploadFile(fileName, content), fileName);
    }

    private static ParameterValue base64File(String fileName, byte[] content) throws IOException {
        Base64FileParameterValue value = new Base64FileParameterValue("B64");
        value.setFile(fileItem(fileName, content));
        return value;
    }

    private static ParameterValue date() {
        return new StringParameterValue("DATE", "2026-10-01");
    }

    private static Set<String> created(Set<String> before) {
        Set<String> now = ApproverFormFixtures.runRequestIds();
        now.removeAll(before);
        return now;
    }

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
