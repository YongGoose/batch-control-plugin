package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.SimpleParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.policy.RequestTooLargeException;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.FileItem;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.post;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.runRequestIds;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-14: custom parameter types — file-like, unreadable and non-text
 * values. Matrix rows T-GAP-153 .. T-GAP-156 (note 276).
 *
 * <p>Basis: SPEC 5 D-72 "a file value only as {@code [file] <original file name>}, never its
 * content, Base64 or a server path"; SPEC 5 D-74 (2) and LIMITATIONS 31 "each value ... counts as
 * the larger of its own size and the size of the uploaded parts it was created from ... A value or
 * uploaded part whose size cannot be determined is refused (fail closed) ... answered with HTTP
 * 413"; SPEC 6 D-60 and LIMITATIONS 48 "the values ... of any other simple parameter whose
 * definition rebuilds the same value from its text; a value the job's definition no longer accepts
 * ... is dropped and the field starts at its default". The fixture types are nested here in the
 * style of CustomParameterFixtures; submissions use core's structured {@code json} field, the way
 * a browser posts a parameters form.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-60/D-72/D-74 and docs/LIMITATIONS.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequestCustomParameterGapTest {

    private static final String CAP_PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(CAP_PROPERTY);
    }

    /**
     * T-GAP-153 (L1-14 type 1): a run request whose value's {@code getValue()} is a
     * {@code java.io.File} shows {@code [file] <name>} on the request page, in {@code requests.csv}
     * and, once approved and run, in {@code runs.csv}, never the file's server path. With the cap
     * below the file's size, the same submission answers 413 over HTTP and the service refuses it
     * with RequestTooLargeException, and nothing is kept under {@code requests/run/}.
     */
    @Test
    public void t_gap_153_fileValuedParameterIsShownAsAFileAndCountsAgainstTheCap() throws Exception {
        FreeStyleProject job = job("gap-fileish", new FileishDefinition("F"));
        File file = uploadFile("fileish-data.csv", payload("gap-fileish-marker", 4096));
        String path = file.getParentFile().getAbsolutePath();

        String id = submitJson(job, "{\"name\":\"F\",\"value\":" + net.sf.json.util.JSONUtils.quote(file.getAbsolutePath()) + "}");
        assertEquals(fileDisplay("fileish-data.csv"), RunRequestService.get().load(id).getParameters().get("F"),
                "the masked display is [file] <name>");
        String page = ApproverFormFixtures.get(j, "a1", "batch-control/requests/" + id + "/").getContentAsString();
        String requestsCsv = ApproverFormFixtures.get(j, "a1", "batch-control/history/requests.csv").getContentAsString();
        for (String[] surface : new String[][] {{"the request page", page}, {"requests.csv", requestsCsv}}) {
            assertTrue(surface[1].contains(fileDisplay("fileish-data.csv")), surface[0] + " shows [file] fileish-data.csv");
            assertFalse(surface[1].contains(path), surface[0] + " must never show the server path " + path);
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "the approved run ran");
        String runsCsv = ApproverFormFixtures.get(j, "a1", "batch-control/history/runs.csv").getContentAsString();
        assertTrue(runsCsv.contains(fileDisplay("fileish-data.csv")), "runs.csv shows [file] fileish-data.csv: " + excerpt(runsCsv));
        assertFalse(runsCsv.contains(path), "runs.csv must never show the server path");

        System.setProperty(CAP_PROPERTY, "2048");
        Set<String> listing = requestDirListing(j);
        Set<String> ids = runRequestIds();
        WebResponse over = postJson(job, "{\"name\":\"F\",\"value\":" + net.sf.json.util.JSONUtils.quote(file.getAbsolutePath()) + "}");
        assertEquals(413, over.getStatusCode(), "a file over the cap answers 413 although the body is small: " + excerpt(over.getContentAsString()));
        try (ACLContext ignored = as("u1")) {
            assertThrows(RequestTooLargeException.class, () -> RunRequestService.get().create(job,
                    List.of(new FileishValue("F", file)), "over the cap", "a1"), "the service counts the file's size too");
        }
        assertEquals(listing, requestDirListing(j), "nothing is kept under requests/run/");
        assertEquals(ids, runRequestIds(), "no request is created");
    }

    /**
     * T-GAP-154 (L1-14 type 2): a value whose {@code getValue()} is a commons-fileupload2
     * {@code FileItem} is shown as {@code [file] <original name>} on the request page and in
     * {@code requests.csv}.
     */
    @Test
    public void t_gap_154_fileItemValuedParameterShowsTheOriginalName() throws Exception {
        FreeStyleProject job = job("gap-itemish", new ItemishDefinition("I"));
        String id = submitJson(job, "{\"name\":\"I\",\"value\":\"orig-item.bin\"}");
        assertEquals(fileDisplay("orig-item.bin"), RunRequestService.get().load(id).getParameters().get("I"),
                "the masked display is [file] <original name>");
        String page = ApproverFormFixtures.get(j, "a1", "batch-control/requests/" + id + "/").getContentAsString();
        assertTrue(page.contains(fileDisplay("orig-item.bin")), "the request page shows [file] orig-item.bin");
        assertTrue(ApproverFormFixtures.get(j, "a1", "batch-control/history/requests.csv").getContentAsString()
                .contains(fileDisplay("orig-item.bin")), "requests.csv shows [file] orig-item.bin");
    }

    /**
     * T-GAP-155 (L1-14 type 3; LIMITATIONS 31 "A value ... whose size cannot be determined is
     * refused (fail closed) ... HTTP 413"): a submission carrying a value whose {@code getValue()}
     * throws answers 413 with a message about the size, and nothing is kept under
     * {@code requests/run/}. Guard: a submission without that value is accepted.
     */
    @Test
    public void t_gap_155_valueOfUnknownSizeIsRefusedWith413() throws Exception {
        FreeStyleProject job = job("gap-unknown-size", new ThrowingDefinition("X"));
        Set<String> listing = requestDirListing(j);
        Set<String> ids = runRequestIds();
        WebResponse refused = postJson(job, "{\"name\":\"X\",\"value\":\"anything\"}");
        assertEquals(413, refused.getStatusCode(), "a value of unknown size answers 413: " + excerpt(refused.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the unknown-size refusal", refused.getContentAsString(), Pattern.compile("(?i)size"));
        assertEquals(listing, requestDirListing(j), "nothing is kept under requests/run/");
        assertEquals(ids, runRequestIds(), "no request is created");

        submitJson(job, "");
        assertEquals(ids.size() + 1, runRequestIds().size(), "guard: a submission without that value is accepted");
    }

    /**
     * T-GAP-156 (L1-14 type 4; SPEC 6 D-60, LIMITATIONS 48): u1's direct build of an
     * approval-required job with four simple parameters (one whose value round-trips through its
     * definition's {@code createValue(String)}, one whose {@code createValue(String)} throws, one
     * whose {@code getValue()} is null and one whose {@code getValue()} throws) is refused and
     * leads to the Request Run form carrying only the round-tripping value: the redirect's query
     * names {@code p.RT} with the submitted text and none of the others, so they start at their
     * defaults. Nothing is queued or stored.
     */
    @Test
    public void t_gap_156_onlyRoundTrippingCustomValuesArePrefilled() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gap-prefill-custom");
        job.addProperty(new ParametersDefinitionProperty(new RoundTripDefinition("RT"), new CreateThrowsDefinition("CT"),
                new NullValueDefinition("NV"), new GetThrowsDefinition("GT")));
        setBatchControl(job, new BatchControlJobProperty(true));
        Set<String> ids = runRequestIds();
        String json = "{\"parameter\":[{\"name\":\"RT\",\"value\":\"rt-typed\"},{\"name\":\"CT\",\"value\":\"ct-typed\"},"
                + "{\"name\":\"NV\",\"value\":\"nv-typed\"},{\"name\":\"GT\",\"value\":\"gt-typed\"}]}";
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("json", json));
        params.add(new NameValuePair("Submit", "Build"));
        WebResponse answer = post(j, "u1", job.getUrl() + "build?delay=0sec", params);
        assertEquals(303, answer.getStatusCode(), "the requester's refused build redirects to the Request Run form: "
                + excerpt(answer.getContentAsString()));
        URL target = new URL(answer.getWebRequest().getUrl(), answer.getResponseHeaderValue("Location"));
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(), "the redirect leads to the Request Run form");
        Map<String, String> query = query(target);
        assertEquals("rt-typed", query.get("p.RT"), "the round-tripping value is carried: " + target);
        for (String dropped : new String[] {"CT", "NV", "GT"}) {
            assertFalse(query.containsKey("p." + dropped), dropped + " must not be carried, so its field starts at its default: " + target);
        }
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "nothing is queued");
        assertTrue(job.getBuilds().isEmpty(), "nothing ran");
        assertEquals(ids, runRequestIds(), "nothing is stored before the form is submitted");
    }

    // ------------------------------------------------------------------ helpers

    private FreeStyleProject job(String name, ParameterDefinition definition) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(definition));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private WebResponse postJson(FreeStyleProject job, String parameterEntry) throws Exception {
        String json = "{\"reason\":\"custom parameter type\",\"approvers\":\"a1\",\"parameter\":[" + parameterEntry + "]}";
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("reason", "custom parameter type"));
        params.add(new NameValuePair("approvers", "a1"));
        params.add(new NameValuePair("json", json));
        return post(j, "u1", job.getUrl() + "batch-control/submit", params);
    }

    private String submitJson(FreeStyleProject job, String parameterEntry) throws Exception {
        Set<String> before = runRequestIds();
        WebResponse answer = postJson(job, parameterEntry);
        assertTrue(answer.getStatusCode() < 400, "fixture: the submission is accepted, got " + answer.getStatusCode() + ": "
                + excerpt(answer.getContentAsString()));
        Set<String> after = runRequestIds();
        after.removeAll(before);
        assertEquals(1, after.size(), "fixture: one request is created");
        return after.iterator().next();
    }

    private static Map<String, String> query(URL url) {
        Map<String, String> out = new LinkedHashMap<>();
        if (url.getQuery() == null) {
            return out;
        }
        for (String pair : url.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    // ------------------------------------------------------------------ fixture types

    /** Base of the fixture definitions: a simple parameter built from the form's {@code value}. */
    public abstract static class TextDefinition extends SimpleParameterDefinition {
        private static final long serialVersionUID = 1L;

        protected TextDefinition(String name) {
            super(name);
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return fromForm(jo.optString("value", ""));
        }

        protected abstract ParameterValue fromForm(String text);

        @Override
        public ParameterValue createValue(String value) {
            return fromForm(value);
        }
    }

    /** Type 1: the value is a {@link File}. */
    public static final class FileishDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public FileishDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new FileishValue(getName(), new File(text));
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return null;
        }
    }

    public static final class FileishValue extends ParameterValue {
        private static final long serialVersionUID = 1L;
        private final File file;

        public FileishValue(String name, File file) {
            super(name);
            this.file = file;
        }

        @Override
        public Object getValue() {
            return file;
        }
    }

    /** Type 2: the value is a commons-fileupload2 {@link FileItem} named by the form's text. */
    public static final class ItemishDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public ItemishDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            try {
                return new ItemishValue(getName(), text);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return null;
        }
    }

    public static final class ItemishValue extends ParameterValue {
        private static final long serialVersionUID = 1L;
        private final String originalName;
        private transient FileItem<?> item;

        public ItemishValue(String name, String originalName) throws IOException {
            super(name);
            this.originalName = originalName;
            this.item = TypedParameterFixtures.fileItem(originalName, payload("gap-itemish", 512));
        }

        @Override
        public Object getValue() {
            if (item == null) {
                try {
                    item = TypedParameterFixtures.fileItem(originalName, payload("gap-itemish", 512));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return item;
        }
    }

    /** Type 3: {@code getValue()} throws, so the value's size cannot be determined. */
    public static final class ThrowingDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public ThrowingDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new GetThrowsValue(getName());
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return null;
        }
    }

    /** Type 4a: a value that round-trips through {@code createValue(String)}. */
    public static final class RoundTripDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public RoundTripDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new RoundTripValue(getName(), text);
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new RoundTripValue(getName(), "rt-default");
        }
    }

    public static final class RoundTripValue extends ParameterValue {
        private static final long serialVersionUID = 1L;
        private final String text;

        public RoundTripValue(String name, String text) {
            super(name);
            this.text = text;
        }

        @Override
        public Object getValue() {
            return new Token(text);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RoundTripValue other && Objects.equals(getName(), other.getName()) && Objects.equals(text, other.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getName(), text);
        }
    }

    /** A non-String value object whose text is its {@code toString()}. */
    public record Token(String text) implements java.io.Serializable {
        @Override
        public String toString() {
            return text;
        }
    }

    /** Type 4b: {@code createValue(String)} throws (the form path still works). */
    public static final class CreateThrowsDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public CreateThrowsDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new RoundTripValue(getName(), text);
        }

        @Override
        public ParameterValue createValue(String value) {
            throw new IllegalArgumentException("test: this definition cannot rebuild a value from text");
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new RoundTripValue(getName(), "ct-default");
        }
    }

    /** Type 4c: {@code getValue()} is null. */
    public static final class NullValueDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public NullValueDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new NullValue(getName());
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new NullValue(getName());
        }
    }

    public static final class NullValue extends ParameterValue {
        private static final long serialVersionUID = 1L;

        public NullValue(String name) {
            super(name);
        }

        @Override
        public Object getValue() {
            return null;
        }
    }

    /** Type 4d: {@code getValue()} throws. */
    public static final class GetThrowsDefinition extends TextDefinition {
        private static final long serialVersionUID = 1L;

        public GetThrowsDefinition(String name) {
            super(name);
        }

        @Override
        protected ParameterValue fromForm(String text) {
            return new GetThrowsValue(getName());
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return null;
        }
    }

    public static final class GetThrowsValue extends ParameterValue {
        private static final long serialVersionUID = 1L;

        public GetThrowsValue(String name) {
            super(name);
        }

        @Override
        public Object getValue() {
            throw new IllegalStateException("test: this value cannot be read");
        }
    }

    // descriptors (the harness loads a test extension only when it is nested in the running class)

    @TestExtension
    public static final class FileishDescriptor extends ParameterDefinition.ParameterDescriptor {
        public FileishDescriptor() {
            super(FileishDefinition.class);
        }
    }

    @TestExtension
    public static final class ItemishDescriptor extends ParameterDefinition.ParameterDescriptor {
        public ItemishDescriptor() {
            super(ItemishDefinition.class);
        }
    }

    @TestExtension
    public static final class ThrowingDescriptor extends ParameterDefinition.ParameterDescriptor {
        public ThrowingDescriptor() {
            super(ThrowingDefinition.class);
        }
    }

    @TestExtension
    public static final class RoundTripDescriptor extends ParameterDefinition.ParameterDescriptor {
        public RoundTripDescriptor() {
            super(RoundTripDefinition.class);
        }
    }

    @TestExtension
    public static final class CreateThrowsDescriptor extends ParameterDefinition.ParameterDescriptor {
        public CreateThrowsDescriptor() {
            super(CreateThrowsDefinition.class);
        }
    }

    @TestExtension
    public static final class NullValueDescriptor extends ParameterDefinition.ParameterDescriptor {
        public NullValueDescriptor() {
            super(NullValueDefinition.class);
        }
    }

    @TestExtension
    public static final class GetThrowsDescriptor extends ParameterDefinition.ParameterDescriptor {
        public GetThrowsDescriptor() {
            super(GetThrowsDefinition.class);
        }
    }
}
