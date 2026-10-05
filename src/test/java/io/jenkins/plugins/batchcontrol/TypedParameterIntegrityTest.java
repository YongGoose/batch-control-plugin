package io.jenkins.plugins.batchcontrol;

import hudson.model.FileParameterDefinition;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.FormEncodingType;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.KeyDataPair;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.added;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.duplicateTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.editRequestFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.holdsEncrypted;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.markReason;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.renameTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestDirListing;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.requestXml;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.retypeTypedValue;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.tempFiles;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72b (1)-(3) (security-35 S-35-01, S-35-03, S-35-04; spec-review-S7 m-4): each
 * parameter name appears at most once in a run request, and a repeated name is refused before
 * anything is stored; approval refuses a request whose stored values do not match the displayed
 * ones (repeated or differing names, a value that cannot be loaded) and a request stored before
 * D-72; every stored value obeys the display length limit and contains only characters XML can
 * store; a failed save leaves nothing behind. Matrix rows T-05-70 .. T-05-81 (note 265).
 *
 * <p>Form rows post the Request Run endpoint the way the rendered form does (core's structured
 * {@code json} field; multipart when files are involved; raw fields for the plain channel). A field
 * error is pinned as HTTP 400 with an HTML page that is not core's bare error page, shows no crash
 * text, carries the form again (T-05-18, T-UI-117) and adds an explanation that names the field or
 * sits in the field's own parameter block (T-UI-07 precedent). Wording is not pinned.
 *
 * <p>Rows about stored values edit {@code requests/run/<id>.xml} on disk (ARCHITECTURE section 5),
 * as a pre-release file or an administrator's edit would leave it, and first prove that the plugin
 * reads the edited file (a marker appended to the stored reason is read back). Each refusal is
 * paired with an unedited twin request of the same job that is approved and runs with its own
 * value.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72 and D-72b, and the Given/When/Then
 * of docs/reports/security-35.md and docs/reports/spec-review-S7.md only (no src/main knowledge).
 */
@WithJenkins
public class TypedParameterIntegrityTest {

    private static final String REASON = "month-end batch with typed values";
    private static final String EDIT_MARK = " [edited on disk d72b]";

    private JenkinsRule j;

    /** The descriptor of the Secret-carrying parameter type used by T-05-74. */
    @TestExtension
    public static final class TokenDescriptor extends CustomParameterFixtures.TokenDescriptorBase {
    }

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
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    // ================================================================ a parameter name at most once

    /**
     * T-05-70 (S-35-01): u1, holding only Request and Item/Read, posts the form's {@code json} with
     * TARGET twice ({@code staging}, then {@code production}): 400 field error naming TARGET; no
     * request, no file of any name under {@code requests/run/}, nothing queued. Guard: the same
     * post with distinct names (TARGET, DATE) is accepted and shows exactly those values.
     */
    @Test
    public void t_05_70_repeatedNameInTheFormJsonIsRefusedBeforeAnythingIsStored() throws Exception {
        FreeStyleProject job = job("dup-json", new StringParameterDefinition("TARGET", "default-target"),
                new StringParameterDefinition("DATE", "2000-01-01"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        Page refused = postJson("u1", job, form(REASON, value("TARGET", "staging"), value("TARGET", "production")));
        assertFieldRefusal("a repeated parameter name", refused, job, "TARGET");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated name must store no request");
        assertEquals(listing, requestDirListing(j), "a repeated name must leave no file of any name under requests/run/");
        assertNothingRan(job);

        String id = acceptedOne("distinct names",
                postJson("u1", job, form(REASON, value("TARGET", "staging"), value("DATE", "2026-10-01"))), ids);
        assertEquals(Map.of("TARGET", "staging", "DATE", "2026-10-01"), RunRequestService.get().load(id).getParameters(),
                "guard: distinct names are accepted with exactly the submitted values");
    }

    /**
     * T-05-71 (S-35-01, files): the form's {@code json} names the core file parameter UPLOAD twice,
     * each pointing at its own uploaded part (multipart): 400 field error naming UPLOAD; no request;
     * neither upload is kept under the documented temporary directories. Guard: one UPLOAD part
     * posted the same way is accepted and shown as {@code [file] first.csv}, and its file is held.
     */
    @Test
    public void t_05_71_repeatedFileParameterIsRefusedAndKeepsNoUpload() throws Exception {
        FreeStyleProject job = job("dup-file", new FileParameterDefinition("UPLOAD", "core file"),
                new StringParameterDefinition("DATE", "2000-01-01"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<Path> temp = tempFiles(j);

        List<NameValuePair> twice = new ArrayList<>();
        twice.add(new NameValuePair("json", form(REASON, fileRef("UPLOAD", "file0"), fileRef("UPLOAD", "file1"),
                value("DATE", "2026-10-01")).toString()));
        twice.add(filePart("file0", "first.csv", payload("dup-first-marker-Qa71", 1500)));
        twice.add(filePart("file1", "second.csv", payload("dup-second-marker-Qb71", 1500)));
        Page refused = postMultipart("u1", job, twice);
        assertFieldRefusal("a repeated file parameter", refused, job, "UPLOAD");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated file parameter must store no request");
        assertEquals(Set.of(), added(temp, tempFiles(j)), "a refused submission must keep neither upload");
        assertNothingRan(job);

        List<NameValuePair> once = new ArrayList<>();
        once.add(new NameValuePair("json", form(REASON, fileRef("UPLOAD", "file0"), value("DATE", "2026-10-01")).toString()));
        once.add(filePart("file0", "first.csv", payload("single-first-marker-Qc71", 1500)));
        String id = acceptedOne("one file part", postMultipart("u1", job, once), ids);
        assertEquals(fileDisplay("first.csv"), RunRequestService.get().load(id).getParameters().get("UPLOAD"),
                "guard: the same channel with one UPLOAD is accepted");
        assertFalse(added(temp, tempFiles(j)).isEmpty(), "guard: the accepted request holds its file, so the detector above sees uploads");
    }

    /**
     * T-05-72 (S-35-01, raw fields): a url-encoded post without {@code json} that carries the field
     * TARGET twice is refused with a 400 field error naming TARGET and stores nothing. Guard: the
     * same post with TARGET once is accepted and stores {@code staging}.
     */
    @Test
    public void t_05_72_repeatedRawFieldIsRefused() throws Exception {
        FreeStyleProject job = job("dup-raw", new StringParameterDefinition("TARGET", "default-target"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        List<NameValuePair> twice = rawFields();
        twice.add(new NameValuePair("TARGET", "staging"));
        twice.add(new NameValuePair("TARGET", "production"));
        assertFieldRefusal("a repeated raw field", postFields("u1", job, twice), job, "TARGET");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "a repeated raw field must store no request");
        assertEquals(listing, requestDirListing(j), "nothing may be written under requests/run/");
        assertNothingRan(job);

        List<NameValuePair> once = rawFields();
        once.add(new NameValuePair("TARGET", "staging"));
        String id = acceptedOne("one raw TARGET", postFields("u1", job, once), ids);
        assertEquals("staging", RunRequestService.get().load(id).getParameters().get("TARGET"), "guard: the raw channel stores the value");
    }

    /**
     * T-05-73 (S-35-01, service): {@code RunRequestService.create(job, [TARGET=staging,
     * TARGET=production], ...)} and a create with two stashed-file values named DATA each throw
     * {@code IllegalArgumentException}; no request and no file of any name under
     * {@code requests/run/}. Guard: distinct names are accepted (PENDING, exactly those values).
     */
    @Test
    public void t_05_73_serviceRefusesARepeatedNameWithIllegalArgumentException() throws Exception {
        FreeStyleProject job = job("dup-svc", new StringParameterDefinition("TARGET", "default-target"),
                new StringParameterDefinition("DATE", "2000-01-01"), new StashedFileParameterDefinition("DATA"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        List<ParameterValue> strings = new ArrayList<>();
        strings.add(new StringParameterValue("TARGET", "staging"));
        strings.add(new StringParameterValue("TARGET", "production"));
        assertThrows(IllegalArgumentException.class, () -> createTyped(job, strings), "a repeated string name must be refused");

        List<ParameterValue> files = new ArrayList<>();
        files.add(new StashedFileParameterValue("DATA", fileItem("a.bin", payload("dup-svc-a-marker", 600))));
        files.add(new StashedFileParameterValue("DATA", fileItem("b.bin", payload("dup-svc-b-marker", 600))));
        files.add(new StringParameterValue("DATE", "2026-10-01"));
        assertThrows(IllegalArgumentException.class, () -> createTyped(job, files), "a repeated file name must be refused");

        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored from the refused calls");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");
        assertNothingRan(job);

        List<ParameterValue> distinct = new ArrayList<>();
        distinct.add(new StringParameterValue("TARGET", "staging"));
        distinct.add(new StringParameterValue("DATE", "2026-10-01"));
        RunRequest ok = createTyped(job, distinct);
        assertEquals(RequestStatus.PENDING, ok.getStatus());
        assertEquals("staging", RunRequestService.get().load(ok.getId()).getParameters().get("TARGET"), "guard: distinct names are accepted");
    }

    // ================================================================ the display length limit for every stored value

    /**
     * T-05-74 (S-35-01/02): through the service, a Password value of 10,001 characters and a
     * Secret-carrying value of another type ({@code TokenParameterValue}, shown as a mask) of
     * 10,001 characters are each refused (IllegalArgumentException or Failure) and nothing is
     * stored. Boundary guard: both at exactly 10,000 characters are accepted, and the password is
     * held encrypted in the request's file.
     */
    @Test
    public void t_05_74_nonDisplayedValuesOverTheLimitAreRefusedByTheService() throws Exception {
        FreeStyleProject job = job("len-svc",
                new PasswordParameterDefinition("TOKEN", Secret.fromString("len-default"), "token"),
                new CustomParameterFixtures.TokenParameterDefinition("KEY"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        assertRefusedAsInvalid("a password over 10,000 characters must be refused",
                () -> createTyped(job, List.of(new PasswordParameterValue("TOKEN", "p".repeat(10_001)), new StringParameterValue("PLAIN", "x"))));
        assertRefusedAsInvalid("a Secret-carrying value over 10,000 characters must be refused",
                () -> createTyped(job, List.of(new CustomParameterFixtures.TokenParameterValue("KEY", Secret.fromString("k".repeat(10_001))),
                        new StringParameterValue("PLAIN", "x"))));
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored from the refused calls");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");

        List<ParameterValue> atLimit = new ArrayList<>();
        atLimit.add(new PasswordParameterValue("TOKEN", "p".repeat(10_000)));
        atLimit.add(new CustomParameterFixtures.TokenParameterValue("KEY", Secret.fromString("k".repeat(10_000))));
        atLimit.add(new StringParameterValue("PLAIN", "x"));
        RunRequest ok = createTyped(job, atLimit);
        assertEquals(RequestStatus.PENDING, ok.getStatus(), "boundary: 10,000 characters are accepted");
        assertEquals(MASK, RunRequestService.get().load(ok.getId()).getParameters().get("TOKEN"));
        assertTrue(holdsEncrypted(requestFile(j, ok.getId()), "p".repeat(10_000)), "boundary: the password is held encrypted");
    }

    /**
     * T-05-75 (S-35-01/02, form): u1 posts the form with a 10,001-character password TOKEN: 400
     * field error naming TOKEN, nothing stored. Boundary guard: 10,000 characters are accepted and
     * shown as {@code ********}.
     */
    @Test
    public void t_05_75_passwordOverTheLimitIsRefusedOnTheForm() throws Exception {
        FreeStyleProject job = job("len-form", new PasswordParameterDefinition("TOKEN", Secret.fromString("len-default"), "token"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        Page refused = postJson("u1", job, form(REASON, value("TOKEN", "p".repeat(10_001)), value("PLAIN", "x")));
        assertFieldRefusal("a password over 10,000 characters", refused, job, "TOKEN");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertEquals(listing, requestDirListing(j), "no file of any name may be left under requests/run/");

        String id = acceptedOne("a 10,000-character password",
                postJson("u1", job, form(REASON, value("TOKEN", "p".repeat(10_000)), value("PLAIN", "x"))), ids);
        assertEquals(MASK, RunRequestService.get().load(id).getParameters().get("TOKEN"), "boundary: accepted and masked");
    }

    // ================================================================ characters XML can store

    /**
     * T-05-76 (S-35-03, form): u1 posts the form with a TARGET value containing U+0000, then with a
     * reason containing U+0000: each answers a 400 field error (not 500) naming the field; no
     * request, no file of any name (no temporary file) under {@code requests/run/}, no upload kept.
     * Guard: the same value without the character is accepted.
     */
    @Test
    public void t_05_76_xmlIllegalCharacterIsAFieldErrorOnTheForm() throws Exception {
        FreeStyleProject job = job("xml-form", new StringParameterDefinition("TARGET", "default-target"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);
        Set<Path> temp = tempFiles(j);

        assertFieldRefusal("a value with U+0000", postJson("u1", job, form(REASON, value("TARGET", "bad\u0000value"))), job, "TARGET");
        assertFieldRefusal("a reason with U+0000", postJson("u1", job, form("month-end\u0000batch", value("TARGET", "fine"))), job, "reason");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");
        assertEquals(listing, requestDirListing(j), "a failed save must leave no file of any name under requests/run/");
        assertEquals(temp, tempFiles(j), "nothing may be kept under the temporary directories");
        assertNothingRan(job);

        String id = acceptedOne("the value without U+0000", postJson("u1", job, form(REASON, value("TARGET", "badvalue"))), ids);
        assertEquals("badvalue", RunRequestService.get().load(id).getParameters().get("TARGET"));
    }

    /**
     * T-05-77 (S-35-03, service): {@code create} with a value containing U+0000 after a valid one,
     * with a value containing U+FFFF, and with a reason containing U+0000 is each refused
     * (IllegalArgumentException or Failure) and leaves the {@code requests/run/} listing unchanged
     * (no temporary file). Guard: valid values add exactly {@code <id>.xml}.
     */
    @Test
    public void t_05_77_xmlIllegalCharacterIsRefusedByTheServiceWithoutLeftovers() throws Exception {
        FreeStyleProject job = job("xml-svc", new StringParameterDefinition("X", "x-default"), new StringParameterDefinition("Y", "y-default"));
        Set<String> ids = ApproverFormFixtures.runRequestIds();
        Set<String> listing = requestDirListing(j);

        assertRefusedAsInvalid("a value with U+0000 must be refused",
                () -> createTyped(job, List.of(new StringParameterValue("X", "x".repeat(5_000)), new StringParameterValue("Y", "bad\u0000value"))));
        assertEquals(listing, requestDirListing(j), "a refused value must leave no file of any name under requests/run/");
        assertRefusedAsInvalid("a value with U+FFFF must be refused",
                () -> createTyped(job, List.of(new StringParameterValue("Y", "bad￿value"))));
        assertEquals(listing, requestDirListing(j), "a refused value must leave no file of any name under requests/run/");
        assertRefusedAsInvalid("a reason with U+0000 must be refused", () -> {
            try (ACLContext ignored = as("u1")) {
                RunRequestService.get().create(job, List.of(new StringParameterValue("X", "fine")), "month-end\u0000batch", "a1");
            }
        });
        assertEquals(listing, requestDirListing(j), "a refused reason must leave no file of any name under requests/run/");
        assertEquals(ids, ApproverFormFixtures.runRequestIds(), "nothing may be stored");

        RunRequest ok = createTyped(job, List.of(new StringParameterValue("X", "fine"), new StringParameterValue("Y", "also fine")));
        Set<String> after = requestDirListing(j);
        after.removeAll(listing);
        assertEquals(Set.of(ok.getId() + ".xml"), after, "guard: a valid request adds exactly its own file");
    }

    // ================================================================ approval fails closed

    /**
     * T-05-78 (S-35-01 defence in depth): a PENDING request's file is edited to hold a second typed
     * TARGET ({@code production}) while the display still shows {@code staging}: approval is
     * refused (service and HTTP, plain message), the request is neither APPROVED nor EXECUTED,
     * nothing runs and no build ever sees {@code production}. Guard: the unedited twin is approved
     * and runs with its own value.
     */
    @Test
    public void t_05_78_storedRepeatedNameCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-dup");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        editStored(id, xml -> duplicateTypedValue(xml, "TARGET", "production"));
        assertTrue(requestXml(j, id).contains("production"), "premise: the second typed value is in the file");
        assertEquals(Map.of("TARGET", "staging"), RunRequestService.get().load(id).getParameters(), "premise: the approver sees staging only");

        assertApprovalRefused("a request whose typed values repeat TARGET", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("production"), "no build may ever have run with the unseen value");
    }

    /**
     * T-05-79 (S-35-01): the stored typed value TARGET is renamed to OTHER while the display still
     * names TARGET (with MODE in both): approval is refused, nothing runs. Guard: the unedited twin
     * runs with its own value.
     */
    @Test
    public void t_05_79_storedNamesDifferingFromTheDisplayCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-differ", new StringParameterDefinition("MODE", "partial"));
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"), new StringParameterValue("MODE", "full"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"), new StringParameterValue("MODE", "full"))).getId();
        editStored(id, xml -> renameTypedValue(xml, "TARGET", "OTHER"));
        assertEquals(Map.of("TARGET", "staging", "MODE", "full"), RunRequestService.get().load(id).getParameters(),
                "premise: the display still names TARGET");

        assertApprovalRefused("a request whose typed names differ from the displayed ones", job, id);
        assertTwinRuns(job, twin, "guarded");
    }

    /**
     * T-05-80 (S-35-04 (a)): the stored typed value TARGET names a class that cannot be loaded (its
     * plugin removed or the class renamed): approval is refused (fail closed) and nothing runs, so
     * the build never falls back to the default. Guard: the unedited twin runs with its own value.
     */
    @Test
    public void t_05_80_storedValueOfAnUnloadableClassCannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-class");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        editStored(id, xml -> retypeTypedValue(xml, "TARGET", "io.jenkins.plugins.batchcontrol.NoSuchParameterValueD72b"));
        assertTrue(requestXml(j, id).contains("NoSuchParameterValueD72b"), "premise: the file names the unloadable class");

        assertApprovalRefused("a request with a typed value that cannot be loaded", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("default-target"), "no build may have run with the job's default");
    }

    /**
     * T-05-81 (D-72b (3), S7 m-4, S-35-04 (b)): a request file in the shape written before D-72 (the
     * display map, no typed values): approval is refused and nothing runs (no build with the job's
     * default). Guard: the unedited twin runs with its own value.
     */
    @Test
    public void t_05_81_requestStoredBeforeD72CannotBeApproved() throws Exception {
        FreeStyleProject job = storedJob("stored-pre72");
        String id = createTyped(job, List.of(new StringParameterValue("TARGET", "staging"))).getId();
        String twin = createTyped(job, List.of(new StringParameterValue("TARGET", "guarded"))).getId();
        editStored(id, TypedParameterFixtures::withoutTypedValues);
        assertFalse(requestXml(j, id).contains("<parameterValues"), "premise: the file holds no typed values");
        assertEquals(Map.of("TARGET", "staging"), RunRequestService.get().load(id).getParameters(), "premise: the display map stays");

        assertApprovalRefused("a request stored before D-72", job, id);
        assertTwinRuns(job, twin, "guarded");
        assertFalse(TypedParameterFixtures.CaptureEnv.SEEN.containsValue("default-target"), "no build may have run with the job's default");
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject job(String name, ParameterDefinition... definitions) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        job.addProperty(new ParametersDefinitionProperty(definitions));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** An approval-required Freestyle job with TARGET (default {@code default-target}) whose build records TARGET. */
    private FreeStyleProject storedJob(String name, ParameterDefinition... more) throws Exception {
        List<ParameterDefinition> definitions = new ArrayList<>();
        definitions.add(new StringParameterDefinition("TARGET", "default-target"));
        definitions.addAll(Arrays.asList(more));
        FreeStyleProject job = job(name, definitions.toArray(new ParameterDefinition[0]));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TARGET"));
        return job;
    }

    /** Edits request {@code id}'s file and proves the plugin reads the edited file (the reason marker). */
    private void editStored(String id, UnaryOperator<String> edit) throws Exception {
        editRequestFile(j, id, xml -> markReason(edit.apply(xml), EDIT_MARK));
        RunRequest reread = RunRequestService.get().load(id);
        assertNotNull(reread, "premise: the edited request still loads");
        assertTrue(reread.getReason().endsWith(EDIT_MARK), "premise: the plugin reads the edited file (reason was '"
                + reread.getReason() + "')");
        assertEquals(RequestStatus.PENDING, reread.getStatus(), "premise: the edited request is PENDING");
    }

    /** Approval by the designated approver is refused through the service and over HTTP; nothing runs. */
    private void assertApprovalRefused(String what, FreeStyleProject job, String id) throws Exception {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "looks fine");
        } catch (RuntimeException refused) {
            // a refusal; the state below is what counts
        }
        assertNotApproved(what + " (service approve)", id);
        WebResponse http = ApproverFormFixtures.decideRun(j, "a1", id, "approve", "looks fine");
        assertTrue(http.getStatusCode() < 500, what + ": the HTTP approval must be refused without a server error, got HTTP "
                + http.getStatusCode() + ": " + UsabilityFixtures.excerpt(http.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal(what + " (HTTP approve)", http.getContentAsString(), null);
        assertNotApproved(what + " (HTTP approve)", id);
        assertNothingRan(job);
    }

    private static void assertNotApproved(String what, String id) {
        RequestStatus status = RunRequestService.get().load(id).getStatus();
        assertNotEquals(RequestStatus.APPROVED, status, what + ": the approval must be refused");
        assertNotEquals(RequestStatus.EXECUTED, status, what + ": the request must never run");
    }

    /** The unedited twin is approved and runs as build #1 with {@code expected} as TARGET. */
    private void assertTwinRuns(FreeStyleProject job, String twin, String expected) throws Exception {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(twin, "the unedited twin");
        }
        j.waitUntilNoActivity();
        assertNotNull(job.getBuildByNumber(1), "guard: the unedited twin must run");
        assertEquals(expected, TypedParameterFixtures.CaptureEnv.seen(job.getFullName(), 1, "TARGET"),
                "guard: the twin's run receives its own value");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(twin).getStatus());
        assertEquals(1, job.getBuilds().size(), "exactly one run: the twin's");
    }

    private void assertNothingRan(Job<?, ?> job) throws Exception {
        j.waitUntilNoActivity();
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
        assertEquals(1, job.getNextBuildNumber(), "no build number may have been consumed");
    }

    private RunRequest createTyped(Job<?, ?> job, List<ParameterValue> values) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, REASON, "a1");
        }
    }

    private static JSONObject form(String reason, JSONObject... parameters) {
        JSONObject form = new JSONObject();
        form.put("reason", reason);
        form.put("approvers", "a1");
        JSONArray list = new JSONArray();
        for (JSONObject p : parameters) {
            list.add(p);
        }
        form.put("parameter", list);
        return form;
    }

    private static JSONObject value(String name, String value) {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("value", value);
        return p;
    }

    /** A file parameter entry of core's form: the value names the multipart part that holds the file. */
    private static JSONObject fileRef(String name, String part) {
        JSONObject p = new JSONObject();
        p.put("name", name);
        p.put("file", part);
        return p;
    }

    private static KeyDataPair filePart(String part, String fileName, byte[] content) throws Exception {
        return new KeyDataPair(part, uploadFile(fileName, content), fileName, "application/octet-stream", StandardCharsets.UTF_8);
    }

    private static List<NameValuePair> rawFields() {
        List<NameValuePair> fields = new ArrayList<>();
        fields.add(new NameValuePair("reason", REASON));
        fields.add(new NameValuePair("approvers", "a1"));
        return fields;
    }

    private Page postJson(String userId, Job<?, ?> job, JSONObject form) throws Exception {
        return postFields(userId, job, List.of(new NameValuePair("json", form.toString())));
    }

    private Page postFields(String userId, Job<?, ?> job, List<NameValuePair> fields) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(fields));
        return wc.getPage(request);
    }

    private Page postMultipart(String userId, Job<?, ?> job, List<NameValuePair> fields) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "batch-control/submit"), HttpMethod.POST);
        request.setEncodingType(FormEncodingType.MULTIPART);
        request.setRequestParameters(new ArrayList<>(fields));
        return wc.getPage(request);
    }

    /**
     * A field error: 400, an HTML page that is not core's bare error page, no crash text, the form
     * again, and an explanation the plain form does not have (re-entry notices excluded) that names
     * the field, or that sits inside the field's own parameter block, or an {@code X-Error} header
     * naming the field. Wording is not pinned.
     */
    private void assertFieldRefusal(String what, Page answer, Job<?, ?> job, String field) throws Exception {
        WebResponse response = answer.getWebResponse();
        assertEquals(400, response.getStatusCode(), what + " must be refused as a field error (HTTP 400): "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        assertTrue(answer instanceof HtmlPage, what + ": the refusal must be an HTML page, got " + response.getContentType());
        HtmlPage page = (HtmlPage) answer;
        UsabilityFixtures.assertNotBareErrorPage(what, page);
        String text = page.asNormalizedText();
        UsabilityFixtures.assertPlainRefusal(what, text, null);
        assertFalse(UsabilityFixtures.formsEndingWith(page, job.getUrl() + "batch-control/submit").isEmpty(),
                what + ": the refusal must show the form again: " + UsabilityFixtures.formActions(page));

        String header = response.getResponseHeaderValue("X-Error");
        if (header != null && lower(header).contains(lower(field))) {
            return;
        }
        Set<String> plain = lines(UsabilityFixtures.htmlPage(j, "u1", job.getUrl() + "batch-control/").asNormalizedText());
        Set<String> fresh = lines(text);
        fresh.removeAll(plain);
        for (Object notice : page.querySelectorAll("[data-batch-control-notice]")) {
            fresh.removeAll(lines(((org.htmlunit.html.DomNode) notice).asNormalizedText()));
        }
        boolean named = fresh.stream().anyMatch(l -> lower(l).contains(lower(field)));
        boolean nextTo = false;
        List<org.htmlunit.html.HtmlElement> blocks = page.getByXPath(
                "//*[@name='parameter'][.//input[@name='name' and @value='" + field + "']]");
        for (org.htmlunit.html.HtmlElement block : blocks) {
            Set<String> inBlock = lines(block.asNormalizedText());
            inBlock.retainAll(fresh);
            nextTo |= !inBlock.isEmpty();
        }
        assertTrue(named || nextTo, what + ": the refusal must explain itself next to or naming the field " + field
                + "; lines the plain form does not have: " + fresh);
    }

    private static Set<String> lines(String text) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** The submission was accepted and created exactly one request; returns its id. */
    private static String acceptedOne(String what, Page answer, Set<String> before) {
        int code = answer.getWebResponse().getStatusCode();
        assertTrue(code < 400, "guard (" + what + "): the submission must be accepted, got HTTP " + code + ": "
                + UsabilityFixtures.excerpt(answer.getWebResponse().getContentAsString()));
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "guard (" + what + "): exactly one request must be created, got " + created);
        return created.iterator().next();
    }

    private static void assertRefusedAsInvalid(String message, Executable action) {
        boolean refused = false;
        try {
            action.execute();
        } catch (IllegalArgumentException | hudson.model.Failure expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got " + other, other);
        }
        assertTrue(refused, message);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
