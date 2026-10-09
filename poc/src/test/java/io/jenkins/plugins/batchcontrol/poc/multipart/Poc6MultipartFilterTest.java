package io.jenkins.plugins.batchcontrol.poc.multipart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Item;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.batchcontrol.poc.multipart.Poc6Http.CrumbIn;
import io.jenkins.plugins.batchcontrol.poc.multipart.Poc6Http.Multipart;
import io.jenkins.plugins.batchcontrol.poc.multipart.PocMultipartAction.Seen;
import io.jenkins.plugins.batchcontrol.poc.multipart.PocRepeatedFieldsFilter.Outcome;
import io.jenkins.plugins.batchcontrol.poc.multipart.PocRepeatedFieldsFilter.Probe;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * PoC-6 (D-37, LIMITATIONS 56): can a servlet filter in front of Stapler give the action every value
 * of a repeated multipart field, without breaking the file part, the size cap or CSRF protection?
 * Each test names the question (q1..q5) it answers.
 */
@WithJenkins
class Poc6MultipartFilterTest {

    private static final String SUBMIT = "job/x/" + PocMultipartAction.URL_NAME + "/submit";

    private JenkinsRule j;
    private Poc6Http http;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        j.createFreeStyleProject("x");
        http = new Poc6Http(j).withCrumb();
        reset();
    }

    @AfterEach
    void reset() {
        PocRepeatedFieldsFilter.enabled = true;
        PocRepeatedFieldsFilter.maxBytes = 104_857_600L;
        PocRepeatedFieldsFilter.memoryThreshold = 256 * 1024;
        PocRepeatedFieldsFilter.overrideParameterValues = false;
        PocRepeatedFieldsFilter.LAST = null;
        PocMultipartAction.LAST = null;
    }

    private static Multipart fileAndApprovers(byte[] content, String... approvers) {
        Multipart m = new Multipart().field("reason", "month-end").field("approvers", approvers[0]);
        m.file("file0", "data.csv", content);
        for (int i = 1; i < approvers.length; i++) {
            m.field("approvers", approvers[i]);
        }
        return m;
    }

    private Seen submit(boolean filterOn, Multipart body) throws Exception {
        PocRepeatedFieldsFilter.enabled = filterOn;
        PocRepeatedFieldsFilter.LAST = null;
        PocMultipartAction.LAST = null;
        HttpResponse<String> r = http.submit("x", body);
        assertEquals(200, r.statusCode(), "submit must succeed: " + r.body());
        return PocMultipartAction.LAST;
    }

    /** What the action saw, minus what only the filter adds and per-request noise. */
    private static String signature(Seen s) {
        return s == null ? "action not reached" : s.rawApprovers + "|" + s.reason + "|" + s.jsonApprovers + "|"
                + s.fileName + "|" + s.fileSize + "|" + s.fileSha256 + "|" + s.remainingBodyBytes + "|"
                + s.remainingBodyError + "|" + s.refusedTooLarge;
    }

    // ------------------------------------------------------------------ q1: does the filter run first?

    @Test
    void q1_baselineWithoutFilter_repeatedApproversCollapseToLast() throws Exception {
        Seen s = submit(false, fileAndApprovers("a,b\n".getBytes(StandardCharsets.UTF_8), "alice", "bob"));
        System.out.println("[PoC-6 q1 baseline] " + s);
        assertEquals(List.of("bob"), s.rawApprovers, "LIMITATIONS 56 reproduced: Stapler keeps the last part only");
        assertNull(s.filterFields);
        assertEquals("data.csv", s.fileName);
    }

    @Test
    void q1_filterSeesTheUnreadBodyBeforeStaplerParsesIt() throws Exception {
        Multipart body = fileAndApprovers("a,b\n".getBytes(StandardCharsets.UTF_8), "alice", "bob");
        Seen s = submit(true, body);
        Probe p = PocRepeatedFieldsFilter.LAST;
        System.out.println("[PoC-6 q1] " + p + " / " + s);
        assertEquals(Outcome.PARSED, p.outcome);
        assertFalse(p.staplerRequestAlreadyCurrent, "Stapler has not started on this request when the filter runs");
        assertEquals(body.build().length, p.contentLength);
        assertEquals(p.contentLength, p.bytesBuffered, "the filter read the whole body from the socket: nobody read it before");
        assertTrue(p.seq < s.seq, "the filter ran before the action");
        assertEquals(List.of("alice", "bob"), s.filterFields.get("approvers"));
    }

    // ------------------------------------------------------------------ q2: read and replay

    @Test
    void q2_fileAndRepeatedApprovers_actionSeesAllInOrder_fileUnchanged() throws Exception {
        byte[] content = Poc6Http.payload(100_000, 1);
        Seen s = submit(true, fileAndApprovers(content, "alice", "bob", "carol"));
        System.out.println("[PoC-6 q2] " + s);
        assertEquals(List.of("alice", "bob", "carol"), s.filterFields.get("approvers"),
                "every repeated part, in body order, including the parts before and after the file");
        assertEquals(List.of("carol"), s.rawApprovers, "Stapler's own view is unchanged (last part)");
        assertEquals("data.csv", s.fileName);
        assertEquals(content.length, s.fileSize);
        assertEquals(PocMultipartAction.sha256(new ByteArrayInputStream(content)), s.fileSha256, "file bytes intact");
        assertEquals("month-end", s.reason);
        assertEquals(Map.of("approvers", List.of("alice", "bob", "carol")), s.filterFields,
                "only the listed fields are collected: no reason, no file");
    }

    @Test
    void q2_bodyWithoutRepeatedFields_actionSeesExactlyWhatItSeesWithoutFilter() throws Exception {
        byte[] content = Poc6Http.payload(50_000, 2);
        Multipart plain = fileAndApprovers(content, "alice");
        Multipart browserShape = fileAndApprovers(content, "alice").field("json",
                "{\"reason\":\"month-end\",\"approvers\":[\"alice\"]}");
        for (Multipart body : List.of(plain, browserShape)) {
            String without = signature(submit(false, body));
            Seen with = submit(true, body);
            System.out.println("[PoC-6 q2 same] without=" + without + "\n                 with   =" + signature(with));
            assertEquals(without, signature(with));
            assertEquals(List.of("alice"), with.filterFields.get("approvers"));
        }
    }

    @Test
    void q2_overridingGetParameterValuesInstead_duplicatesTheLastValue() throws Exception {
        PocRepeatedFieldsFilter.overrideParameterValues = true;
        Seen two = submit(true, fileAndApprovers(new byte[] {1}, "alice", "bob"));
        Seen one = submit(true, fileAndApprovers(new byte[] {1}, "alice"));
        System.out.println("[PoC-6 q2 override] two=" + two.rawApprovers + " one=" + one.rawApprovers);
        // RequestImpl#getParameterValues for multipart = super.getParameterValues(name) + its own last part.
        assertEquals(List.of("alice", "bob", "bob"), two.rawApprovers);
        assertEquals(List.of("alice", "alice"), one.rawApprovers,
                "a single value comes back twice: core's SimpleParameterDefinition#createValue(StaplerRequest2) "
                        + "refuses length != 1, so overriding for parameter fields would break raw submissions");
    }

    @Test
    void q2_nonAsciiValue_filterDecodesUtf8_staplerPlainFieldDoesNot() throws Exception {
        Seen s = submit(true, fileAndApprovers(new byte[] {1}, "jürgen", "山田").field("json",
                "{\"approvers\":[\"jürgen\",\"山田\"]}"));
        System.out.println("[PoC-6 q2 charset] filter=" + s.filterFields + " stapler plain=" + s.rawApprovers
                + " stapler json=" + s.jsonApprovers);
        assertEquals(List.of("jürgen", "山田"), s.filterFields.get("approvers"), "decoded as UTF-8, like the json part");
        assertEquals("[\"jürgen\",\"山田\"]", s.jsonApprovers, "Stapler's json part: request charset (UTF-8)");
        assertEquals(List.of(new String("山田".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)),
                s.rawApprovers, "pre-existing: Stapler's plain multipart field is decoded as ISO-8859-1");
    }

    // ------------------------------------------------------------------ q3: size cap and resources

    @Test
    void q3_declaredLengthOverCap_passesThroughUnread_actionStillAnswers413() throws Exception {
        PocRepeatedFieldsFilter.maxBytes = 64 * 1024;
        Multipart body = fileAndApprovers(Poc6Http.payload(200_000, 3), "alice", "bob");
        HttpResponse<String> r = http.submit("x", body);
        Probe p = PocRepeatedFieldsFilter.LAST;
        System.out.println("[PoC-6 q3 declared] status=" + r.statusCode() + " " + p);
        assertEquals(Outcome.PASSED_OVER_CAP_DECLARED, p.outcome);
        assertEquals(-1, p.bytesBuffered, "not a byte copied by the filter");
        assertNull(p.tempFile);
        assertEquals(413, r.statusCode(), "the action's own refusal still happens");
        assertTrue(PocMultipartAction.LAST.refusedTooLarge);
    }

    @Test
    void q3_streamedBodyOverCap_copiedOnlyUpToCap_thenHandedOnUnparsed() throws Exception {
        PocRepeatedFieldsFilter.maxBytes = 64 * 1024;
        PocRepeatedFieldsFilter.memoryThreshold = 16 * 1024;
        byte[] content = Poc6Http.payload(200_000, 4);
        Multipart body = fileAndApprovers(content, "alice", "bob");
        HttpResponse<String> r = http.post(SUBMIT, body.contentType(), body.build(), true, CrumbIn.QUERY);
        Probe p = PocRepeatedFieldsFilter.LAST;
        Seen s = PocMultipartAction.LAST;
        System.out.println("[PoC-6 q3 chunked] status=" + r.statusCode() + " " + p + " / " + s);
        assertEquals(-1, p.contentLength, "premise: no declared length (chunked)");
        assertEquals(Outcome.PASSED_OVER_CAP_STREAMED, p.outcome);
        assertEquals(64 * 1024 + 1, p.bytesBuffered, "copied at most cap + 1 bytes, then stopped");
        assertTrue(p.tempFileDeletedAfterChain);
        assertNull(s.filterFields, "not parsed: no attribute");
        assertEquals(PocMultipartAction.sha256(new ByteArrayInputStream(content)), s.fileSha256,
                "Stapler got prefix + rest unchanged; the real action's stage 2 (kept size) then decides");
        assertEquals(List.of("bob"), s.rawApprovers);
    }

    @Test
    void q3_largeBody_ownerOnlyTempFile_uploadHeldTwice_copyDeletedAfterRequest() throws Exception {
        byte[] content = Poc6Http.payload(8 * 1024 * 1024, 5);
        Multipart body = fileAndApprovers(content, "alice", "bob");
        long[] off = new long[3];
        long[] on = new long[3];
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            submit(false, body);
            off[i] = (System.nanoTime() - t0) / 1_000_000;
            t0 = System.nanoTime();
            submit(true, body);
            on[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        Probe p = PocRepeatedFieldsFilter.LAST;
        Seen s = PocMultipartAction.LAST;
        System.out.println("[PoC-6 q3 large] 8 MiB file, ms without filter=" + java.util.Arrays.toString(off)
                + " with filter=" + java.util.Arrays.toString(on));
        System.out.println("[PoC-6 q3 large] " + p + " / stapler file " + s.staplerFilePath + " inMemory=" + s.fileInMemory
                + " existedDuringAction=" + s.staplerFileExistedDuringAction);
        assertFalse(p.inMemory, "over the 256 KiB threshold: copied to a temp file, not the heap");
        assertEquals(p.contentLength, p.bytesBuffered);
        if (!"n/a".equals(p.tempFilePermissions)) {
            assertEquals("rw-------", p.tempFilePermissions, "owner-only temp file");
        }
        assertTrue(s.filterTempFileExistedDuringAction, "the filter's copy exists while the action runs ...");
        assertFalse(s.fileInMemory);
        assertTrue(s.staplerFileExistedDuringAction, "... and so does Stapler's own copy: the upload is on disk twice");
        assertTrue(p.tempFileDeletedAfterChain, "the filter's copy is gone when the request ends");
        assertFalse(Files.exists(p.tempFile));
        assertEquals(List.of("alice", "bob"), s.filterFields.get("approvers"));
        assertEquals(PocMultipartAction.sha256(new ByteArrayInputStream(content)), s.fileSha256);
    }

    // ------------------------------------------------------------------ q4: security and integration

    @Test
    void q4_crumbsOn_securedInstance_multipartWithCrumbAccepted_filterRunsAfterAuthentication() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ).everywhere().to("alice"));
        assertNotNull(j.jenkins.getCrumbIssuer(), "premise: crumbs on");
        Poc6Http alice = new Poc6Http(j).as("alice").withCrumb();
        Multipart body = fileAndApprovers(new byte[] {1, 2, 3}, "alice", "bob");

        for (CrumbIn where : List.of(CrumbIn.QUERY, CrumbIn.HEADER)) {
            reset();
            HttpResponse<String> r = alice.post(SUBMIT, body.contentType(), body.build(), false, where);
            Probe p = PocRepeatedFieldsFilter.LAST;
            System.out.println("[PoC-6 q4 crumb " + where + "] status=" + r.statusCode() + " " + p);
            assertEquals(200, r.statusCode());
            assertEquals("alice", p.authentication, "authentication already ran when the filter runs");
            assertEquals(List.of("alice", "bob"), PocMultipartAction.LAST.filterFields.get("approvers"));
        }
    }

    @Test
    void q4_crumbsOn_noCrumb_refusedWith403_beforeTheFilterBuffersAnything() throws Exception {
        Multipart body = fileAndApprovers(new byte[] {1}, "alice", "bob");
        HttpResponse<String> r = http.post(SUBMIT, body.contentType(), body.build(), false, CrumbIn.NONE);
        System.out.println("[PoC-6 q4 no crumb] status=" + r.statusCode() + " probe=" + PocRepeatedFieldsFilter.LAST);
        assertEquals(403, r.statusCode());
        assertNull(PocRepeatedFieldsFilter.LAST, "CrumbFilter refused it before the plugin filter chain ran");
        assertNull(PocMultipartAction.LAST);
    }

    @Test
    void q4_crumbOnlyInsideTheMultipartBody_sameOutcomeWithAndWithoutFilter() throws Exception {
        Multipart body = fileAndApprovers(new byte[] {1}, "alice", "bob").field(http.crumbField, http.crumb);
        int[] status = new int[2];
        for (int i = 0; i < 2; i++) {
            reset();
            PocRepeatedFieldsFilter.enabled = i == 1;
            status[i] = http.post(SUBMIT, body.contentType(), body.build(), false, CrumbIn.NONE).statusCode();
        }
        System.out.println("[PoC-6 q4 crumb in body] without=" + status[0] + " with=" + status[1]
                + " probe=" + PocRepeatedFieldsFilter.LAST);
        assertEquals(status[0], status[1], "the filter does not change crumb handling");
        assertEquals(403, status[1], "CrumbFilter reads the container's parameters, which exclude multipart parts");
    }

    @Test
    void q4_scope_otherMethodsContentTypesAndUrlsAreNotTouched() throws Exception {
        // GET on the same URL
        int get = http.client.send(http.builder(SUBMIT).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        assertNull(PocRepeatedFieldsFilter.LAST, "GET not in scope (status " + get + ")");
        // url-encoded POST on the same URL: the container already gives every value
        byte[] form = "reason=r&approvers=alice&approvers=bob".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> urlencoded = http.post(SUBMIT, "application/x-www-form-urlencoded", form, false, CrumbIn.QUERY);
        assertEquals(200, urlencoded.statusCode());
        assertNull(PocRepeatedFieldsFilter.LAST, "url-encoded not in scope");
        assertEquals(List.of("alice", "bob"), PocMultipartAction.LAST.rawApprovers);
        // multipart POST to other URLs under the job and elsewhere
        Multipart body = fileAndApprovers(new byte[] {1}, "alice", "bob");
        for (String path : List.of("job/x/" + PocMultipartAction.URL_NAME + "/other", "job/x/build",
                "job/x/" + PocMultipartAction.URL_NAME + "/submit/extra", "manage/submit")) {
            int status = http.post(path, body.contentType(), body.build(), false, CrumbIn.QUERY).statusCode();
            System.out.println("[PoC-6 q4 scope] " + path + " -> " + status);
            assertNull(PocRepeatedFieldsFilter.LAST, path + " not in scope");
        }
        // a view-prefixed URL to the same action is in scope
        HttpResponse<String> viaView = http.post("view/all/" + SUBMIT, body.contentType(), body.build(), false, CrumbIn.QUERY);
        assertEquals(200, viaView.statusCode());
        assertEquals(Outcome.PARSED, PocRepeatedFieldsFilter.LAST.outcome, "view/all/job/x/... is the same action");
    }

    @Test
    void q4_malformedMultipart_noAttribute_sameResponseAsWithoutFilter() throws Exception {
        Multipart ok = fileAndApprovers(new byte[] {1, 2}, "alice", "bob");
        byte[][] bodies = {
            ok.buildTruncated(),
            "garbage, not multipart at all".getBytes(StandardCharsets.UTF_8),
            ok.build(),
        };
        String[] types = {ok.contentType(), ok.contentType(), "multipart/form-data"};
        String[] names = {"truncated", "garbage", "no boundary"};
        for (int i = 0; i < bodies.length; i++) {
            String[] result = new String[2];
            for (int f = 0; f < 2; f++) {
                reset();
                PocRepeatedFieldsFilter.enabled = f == 1;
                HttpResponse<String> r = http.post(SUBMIT, types[i], bodies[i], false, CrumbIn.QUERY);
                result[f] = r.statusCode() + " " + signature(PocMultipartAction.LAST);
            }
            Probe p = PocRepeatedFieldsFilter.LAST;
            System.out.println("[PoC-6 q4 malformed " + names[i] + "] without=" + result[0] + "\n      with   =" + result[1]
                    + "\n      " + p);
            assertEquals(result[0], result[1], names[i] + ": same response and same action view as without the filter");
            if (p.outcome == Outcome.PARSED) {
                // no delimiter anywhere: fileupload2 (like Stapler) finds no part at all, not an error
                assertEquals(Map.of(), PocMultipartAction.LAST.filterFields, names[i]);
            } else {
                assertEquals(Outcome.NOT_PARSED, p.outcome, names[i]);
                if (PocMultipartAction.LAST != null) {
                    assertNull(PocMultipartAction.LAST.filterFields, names[i]);
                }
            }
        }
        // a later well-formed request is unaffected
        assertEquals(List.of("alice", "bob"), submit(true, ok).filterFields.get("approvers"));
    }

    @Test
    void q4_clientAbortsMidUpload_filterLeavesNoTempFile() throws Exception {
        PocRepeatedFieldsFilter.memoryThreshold = 16 * 1024;
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long before = countTemp(tmp);
        int created = PocRepeatedFieldsFilter.TEMP_FILES_CREATED.get();
        byte[] start = fileAndApprovers(Poc6Http.payload(300_000, 8), "alice", "bob").buildTruncated();
        java.net.http.HttpRequest req = http.builder(SUBMIT + "?" + http.crumbField + "=" + http.crumb)
                .header("Content-Type", new Multipart().contentType())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.InputStream() {
                    private int pos;

                    @Override
                    public int read() throws java.io.IOException {
                        if (pos >= start.length) {
                            throw new java.io.IOException("PoC-6: client gives up mid-upload");
                        }
                        return start[pos++] & 0xff;
                    }
                })).build();
        try {
            http.client.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException expected) {
            // the client aborted its own request
        }
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && (PocRepeatedFieldsFilter.LAST == null
                || PocRepeatedFieldsFilter.LAST.outcome != Outcome.COPY_FAILED)) {
            Thread.sleep(50);
        }
        System.out.println("[PoC-6 q4 abort] " + PocRepeatedFieldsFilter.LAST + " temp before=" + before
                + " after=" + countTemp(tmp));
        assertEquals(Outcome.COPY_FAILED, PocRepeatedFieldsFilter.LAST.outcome, "the server saw the upload break off");
        assertTrue(PocRepeatedFieldsFilter.TEMP_FILES_CREATED.get() > created,
                "premise: the copy had already spilled to a temp file when the client went away");
        assertEquals(before, countTemp(tmp), "no bc-multipart-*.tmp left behind");
        assertNull(PocMultipartAction.LAST, "the action never ran");
    }

    private static long countTemp(java.nio.file.Path dir) throws java.io.IOException {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("bc-multipart-")).count();
        }
    }

    // ------------------------------------------------------------------ q5: cheaper alternatives

    @Test
    void q5_containerGetParts_withoutMultipartConfig() throws Exception {
        Poc6GetPartsProbe probe = new Poc6GetPartsProbe();
        PluginServletFilter.addFilter(probe);
        try {
            byte[] content = Poc6Http.payload(1000, 6);
            Seen s = submit(false, fileAndApprovers(content, "alice", "bob"));
            System.out.println("[PoC-6 q5 getParts] container request " + probe.containerRequestClass + ": "
                    + probe.result + " " + probe.partNames + " / action " + s);
            assertNotNull(probe.result);
            assertEquals(PocMultipartAction.sha256(new ByteArrayInputStream(content)), s.fileSha256,
                    "whatever getParts did, record whether Stapler still got the file");
        } finally {
            PluginServletFilter.removeFilter(probe);
        }
    }

    @Test
    void q5_rawInputStream_isAlreadyConsumedWhenTheActionRuns() throws Exception {
        Seen s = submit(false, fileAndApprovers(Poc6Http.payload(1000, 7), "alice", "bob"));
        System.out.println("[PoC-6 q5 inputStream] remaining=" + s.remainingBodyBytes + " error=" + s.remainingBodyError);
        assertTrue(s.remainingBodyBytes == 0 || s.remainingBodyError != null,
                "Stapler parsed the body during dispatch (HistoryWidget), nothing is left for the action");
    }
}
