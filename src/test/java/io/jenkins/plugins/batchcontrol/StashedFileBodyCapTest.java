package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72, D-72b (4), D-74 (2)): "a run request submission whose body is larger than the
 * cap (default 100 MB, system property {@code io.jenkins.plugins.batchcontrol.maxRequestBodyBytes})
 * is refused (HTTP 413) before Batch Control reads the form, with no request created and nothing
 * kept in JENKINS_HOME"; D-74 (2): the kept-size check uses the sizes of the uploaded parts the
 * submission actually read, in two stages (the declared Content-Length as an early filter, then the
 * kept-size check before storing). Matrix rows T-05-134 and T-05-135 (note 270, coverage inventory
 * G-M8): the existing cap rows (T-05-59 .. T-05-64, T-05-94 .. T-05-100) use a core {@code file} and
 * a {@code base64File}; these rows put the file-parameters plugin's {@code stashedFile} through both
 * stages, with and without a declared length, and through core's structured {@code json} form.
 *
 * <p>"Nothing kept" is measured as in RequestBodyCapTest: no request created, no file of any name
 * added under {@code requests/run/}, nothing new under {@code fileParameterValueFiles/} or
 * {@code stashedFileParameterValueFiles/}, nothing queued and no build number consumed. The cap is
 * set per test and cleared afterwards (the plugin reads it when it checks). Bodies without a declared
 * length are sent with {@link RawHttpFixtures} (an API token, so no crumb is involved).
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72, D-72b and D-74 only (no src/main
 * knowledge).
 */
@WithJenkins
public class StashedFileBodyCapTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";
    private static final long CAP = 64 * 1024;

    private JenkinsRule j;
    private WorkflowJob job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        job = j.createProject(WorkflowJob.class, "cap-stash");
        job.setDefinition(new CpsFlowDefinition("node {\n  unstash 'DATA'\n}\n", true));
        job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(CAP_PROPERTY);
    }

    /**
     * T-05-134 (G-M8): with the cap at 64 KiB, a {@code stashedFile} of 80 KiB is refused with 413
     * whether the body declares its length (the Request Run form submitted as a browser does; a raw
     * multipart body with Content-Length) or not (the same raw body sent chunked), and none of them
     * keeps anything. Guards: a 1 KiB {@code stashedFile} is accepted through the form and chunked,
     * each creating exactly one request that shows {@code [file] report.bin}.
     */
    @Test
    public void t_05_134_overCapStashedFileIsRefusedWithAndWithoutADeclaredLength() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));
        String boundary = "d74-stash-boundary-Gm08";

        Set<String> before = ApproverFormFixtures.runRequestIds();
        Page small = submitForm(payload("stash-form-small-Gm08", 1024));
        assertTrue(small.getWebResponse().getStatusCode() < 400, "guard: a 1 KiB stashedFile through the form must be accepted, got HTTP "
                + small.getWebResponse().getStatusCode());
        assertCreatedOne(before, "guard: the form under the cap");

        Snapshot snapshot = snapshot();
        Page over = submitForm(payload("stash-form-over-Gm08", (int) CAP + 16 * 1024));
        assertEquals(413, over.getWebResponse().getStatusCode(), "a stashedFile over the cap through the form must answer 413: "
                + UsabilityFixtures.excerpt(over.getWebResponse().getContentAsString()));
        assertKeptNothing(snapshot, "the form over the cap");

        byte[] overBody = plainBody(boundary, payload("stash-raw-over-Gm08", (int) CAP + 16 * 1024));
        snapshot = snapshot();
        int declared = RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth),
                RawHttpFixtures.header("Content-Type", "multipart/form-data; boundary=" + boundary),
                RawHttpFixtures.header("Content-Length", Integer.toString(overBody.length))), overBody, false);
        assertEquals(413, declared, "a raw body over the cap with a declared length must answer 413");
        assertKeptNothing(snapshot, "the raw body with a declared length");

        before = ApproverFormFixtures.runRequestIds();
        int chunkedSmall = postChunked(auth, boundary, plainBody(boundary, payload("stash-raw-small-Gm08", 1024)));
        assertTrue(chunkedSmall < 400, "guard: a chunked body with a 1 KiB stashedFile must be accepted, got HTTP " + chunkedSmall);
        assertCreatedOne(before, "guard: the chunked body under the cap");

        snapshot = snapshot();
        assertEquals(413, postChunked(auth, boundary, overBody), "a chunked body over the cap (no declared length) must answer 413");
        assertKeptNothing(snapshot, "the chunked body over the cap");
    }

    /**
     * T-05-135 (G-M8, the D-74 (2) kept-size check on parts the submission actually read; the
     * {@code stashedFile} twin of T-05-98/99): with the cap at 64 KiB, a chunked multipart body whose
     * {@code json} names DATA's file part {@code blob} ({@code report.bin}, 400 KiB) answers 413 and
     * keeps nothing. Guard: the same encoding with a 1 KiB part is not refused by the cap (neither
     * 413 nor a server error).
     */
    @Test
    public void t_05_135_overCapStashedFileReferencedFromTheJsonFormIsRefused() throws Exception {
        System.setProperty(CAP_PROPERTY, Long.toString(CAP));
        String auth = RawHttpFixtures.basic("u1", RawHttpFixtures.apiToken("u1"));
        String boundary = "d74-stash-json-boundary-Gm08";

        int small = postChunked(auth, boundary, jsonBody(boundary, payload("stash-json-small-Gm08", 1024)));
        assertNotEquals(413, small, "guard: a json-referenced stashedFile under the cap must not be refused by the cap");
        assertTrue(small < 500, "guard: a json-referenced stashedFile under the cap must not crash, got HTTP " + small);

        Snapshot snapshot = snapshot();
        byte[] large = jsonBody(boundary, payload("stash-json-large-Gm08", 400 * 1024));
        assertTrue(large.length > 400 * 1024, "premise: the body carries the 400 KiB part, was " + large.length);
        assertEquals(413, postChunked(auth, boundary, large), "a json-referenced stashedFile over the cap must answer 413");
        assertKeptNothing(snapshot, "the json-referenced body over the cap");
    }

    // ---------------------------------------------------------------- helpers

    /** What "nothing kept" is compared against. */
    private record Snapshot(Set<String> ids, Set<String> listing, Set<Path> temp) {
    }

    private Snapshot snapshot() throws IOException {
        return new Snapshot(ApproverFormFixtures.runRequestIds(), requestDirListing(j), tempFiles(j));
    }

    private void assertKeptNothing(Snapshot before, String what) throws Exception {
        assertEquals(before.ids(), ApproverFormFixtures.runRequestIds(), what + ": no request may be created");
        assertEquals(before.listing(), requestDirListing(j), what + ": no file of any name may be added under requests/run/");
        assertEquals(before.temp(), tempFiles(j), what + ": nothing may be kept under fileParameterValueFiles/ or stashedFileParameterValueFiles/");
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), what + ": the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), what + ": no build number may have been consumed");
    }

    private void assertCreatedOne(Set<String> before, String what) {
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), what + ": exactly one request must be created, got " + created);
        assertEquals(fileDisplay("report.bin"), RunRequestService.get().load(created.iterator().next()).getParameters().get("DATA"),
                what + ": the request shows the stashed file by its name");
    }

    /** The Request Run form of {@code cap-stash} submitted as u1 with {@code content} chosen as {@code report.bin}. */
    private Page submitForm(byte[] content) throws Exception {
        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        TypedParameterFixtures.setValue(form, "DATE", "2026-10-06");
        TypedParameterFixtures.setFile(form, "DATA", uploadFile("report.bin", content));
        return TypedParameterFixtures.submit(wc, form, "month-end batch", "a1");
    }

    /** The plain Request Run fields (reason, approver, DATE) and {@code report.bin} as the part DATA. */
    private static byte[] plainBody(String boundary, byte[] file) throws IOException {
        List<Object[]> parts = new ArrayList<>();
        parts.add(RawHttpFixtures.part("reason", null, "month-end batch".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("approvers", null, "a1".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("DATE", null, "2026-10-06".getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("DATA", "report.bin", file));
        return RawHttpFixtures.multipart(boundary, parts);
    }

    /** Core's {@code json} form field naming DATA's file part {@code blob}, then that part. */
    private static byte[] jsonBody(String boundary, byte[] file) throws IOException {
        String json = "{\"reason\":\"month-end batch\",\"approvers\":\"a1\",\"parameter\":["
                + "{\"name\":\"DATA\",\"file\":\"blob\"},{\"name\":\"DATE\",\"value\":\"2026-10-06\"}]}";
        List<Object[]> parts = new ArrayList<>();
        parts.add(RawHttpFixtures.part("json", null, json.getBytes(StandardCharsets.UTF_8)));
        parts.add(RawHttpFixtures.part("blob", "report.bin", file));
        return RawHttpFixtures.multipart(boundary, parts);
    }

    private int postChunked(String auth, String boundary, byte[] body) throws IOException {
        return RawHttpFixtures.post(j.getURL(), job.getUrl() + "batch-control/submit", RawHttpFixtures.headers(
                RawHttpFixtures.header("Authorization", auth),
                RawHttpFixtures.header("Content-Type", "multipart/form-data; boundary=" + boundary)), body, true);
    }
}
