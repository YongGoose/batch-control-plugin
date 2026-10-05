package io.jenkins.plugins.batchcontrol;

import hudson.FilePath;
import hudson.model.BooleanParameterDefinition;
import hudson.model.BooleanParameterValue;
import hudson.model.Cause;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.Failure;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TextParameterDefinition;
import hudson.model.TextParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlForm;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertAbsent;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.serverPaths;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.storeFilesContaining;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.submitRequest;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-72 (supersedes P-03 option ①): a run request keeps the submitted parameter
 * values with their types, for every parameter type including core {@code file} and the
 * file-parameters plugin's {@code stashedFile} and {@code base64File}; the approved build receives
 * exactly those values, the original secret and file included; every textual form shows a masked
 * map ({@code ********} for a sensitive value, {@code [file] <original name>} for a file). Matrix
 * rows T-05-41 .. T-05-50 (note 260); T-05-93, T-05-96 and T-05-97 (note 265: another
 * Secret-carrying type, no file content in the store, run control off).
 *
 * <p>Requests are submitted through the job's Request Run form as a browser does (multipart, the
 * file chosen in the parameter's own file control) wherever the row is about the form, and through
 * the service's typed overload {@code RunRequestService.create(Job, List<ParameterValue>, reason,
 * approver)} where the row is about the stored value. Every "never shown" assertion is paired with
 * a positive one on the same surface.
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/Request; no Item/Build, D-38a),
 * {@code a1} approver, {@code viewer} (ViewHistory), {@code admin}.
 *
 * <p>Written from docs/SPEC.md item 5, docs/DECISIONS.md D-72, docs/ARCHITECTURE.md sections 2 and
 * 5 and the frozen D-72 contract only (no src/main knowledge).
 */
@WithJenkins
public class TypedParameterValuesTest {

    private static final String CORE_MARKER = "core-file-marker-Zq81";
    private static final String STASH_MARKER = "stash-file-marker-Wk42";
    private static final String B64_MARKER = "b64-file-marker-Hq27";
    private static final String SECRET = "pl41n-s3cr3t-d72-Xv9";
    private static final String SECRET_DEFAULT = "d3fault-s3cr3t-d72-Lm4";
    private static final String PLAIN = "plain-visible-d72-Rt5";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        TypedParameterFixtures.CaptureEnv.SEEN.clear();
    }

    /**
     * T-05-41: a core {@code file} parameter submitted with the Request Run form (multipart) reaches
     * the approved Freestyle build byte for byte: the file lands in the workspace at the parameter's
     * location and in core's own copy under the build directory; the build carries a
     * {@link FileParameterValue} with the original file name and the plain value next to it.
     */
    @Test
    public void t_05_41_coreFileReachesTheApprovedFreestyleBuildByteForByte() throws Exception {
        byte[] content = payload(CORE_MARKER, 6000);
        FreeStyleProject job = coreFileJob("upload-x");
        String id = submitRequest(j, "u1", job, Map.of("DATE", PLAIN),
                Map.of("UPLOAD", uploadFile("data.csv", content)));

        RunRequest pending = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, pending.getStatus());
        assertEquals(fileDisplay("data.csv"), pending.getParameters().get("UPLOAD"),
                "the stored display form of a file value is [file] <original name>");
        assertEquals(PLAIN, pending.getParameters().get("DATE"));
        assertTrue(job.getBuilds().isEmpty(), "premise: nothing runs before the approval");

        approve(id);
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as build #1");
        j.assertBuildStatusSuccess(build);
        assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")),
                "the workspace file at the parameter's location must hold exactly the uploaded bytes");
        assertArrayEquals(content, Files.readAllBytes(build.getRootDir().toPath().resolve("fileParameters").resolve("UPLOAD")),
                "core's copy of the build's file parameter must hold exactly the uploaded bytes");
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertNotNull(parameters, "the approved build must carry its parameters");
        ParameterValue upload = parameters.getParameter("UPLOAD");
        assertTrue(upload instanceof FileParameterValue, "the build must receive a core file value, got " + upload);
        assertEquals("data.csv", ((FileParameterValue) upload).getOriginalFileName());
        assertEquals(PLAIN, ((StringParameterValue) parameters.getParameter("DATE")).getValue(),
                "guard: the plain value travels next to the file");
        assertEquals(id, build.getCause(ApprovedCause.class).getRequestId());
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus());
    }

    /**
     * T-05-42: the core file value of T-05-41 is shown as {@code [file] data.csv} on the request
     * detail page (requester, approver, administrator), the dashboard, {@code requests.csv} and
     * {@code runs.csv}, and none of those, nor the request list or the history screens, shows the
     * file content or a server path.
     */
    @Test
    public void t_05_42_coreFileIsShownOnlyAsItsNameOnEveryTextualSurface() throws Exception {
        byte[] content = payload(CORE_MARKER, 6000);
        FreeStyleProject job = coreFileJob("shown-x");
        String id = submitRequest(j, "u1", job, Map.of("DATE", PLAIN),
                Map.of("UPLOAD", uploadFile("data.csv", content)));
        approve(id);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(job.getBuildByNumber(1));

        List<String> forbidden = new ArrayList<>(serverPaths(j));
        forbidden.add(CORE_MARKER);
        forbidden.add(Base64.getEncoder().encodeToString(content).substring(0, 32));
        String shown = fileDisplay("data.csv");

        for (String user : new String[] {"u1", "a1", "admin"}) {
            String detail = readable(j, user, "batch-control/requests/" + id + "/");
            assertTrue(detail.contains(shown), "the request detail seen by " + user + " must show " + shown);
            assertTrue(detail.contains(PLAIN), "guard: the detail seen by " + user + " shows the plain value");
            assertAbsent("the request detail seen by " + user, detail, forbidden);
        }
        assertAbsent("the request list", readable(j, "u1", "batch-control/requests/"), forbidden);
        for (String kind : new String[] {"requests", "runs"}) {
            assertAbsent("the " + kind + " history", readable(j, "viewer", "batch-control/history/?kind=" + kind), forbidden);
        }
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/requests.csv",
                "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(shown), surface + " must show the file value as " + shown);
            assertAbsent(surface, body, forbidden);
        }
    }

    /**
     * T-05-43: a file-parameters {@code stashedFile} submitted with the Request Run form reaches the
     * approved Pipeline build: {@code unstash} and {@code withFileParameter} read exactly the
     * uploaded bytes, and {@code DATA_FILENAME} is the original file name.
     */
    @Test
    public void t_05_43_stashedFileReachesTheApprovedPipelineByteForByte() throws Exception {
        byte[] content = payload(STASH_MARKER, 5000);
        WorkflowJob job = pipeline("stash-x",
                "node {\n"
                + "  unstash 'DATA'\n"
                + "  withFileParameter('DATA') {\n"
                + "    writeFile file: 'via-wrapper.bin', text: readFile(file: env.DATA, encoding: 'Base64'), encoding: 'Base64'\n"
                + "  }\n"
                + "  writeFile file: 'filename.txt', text: env.DATA_FILENAME\n"
                + "}\n",
                new StashedFileParameterDefinition("DATA"), new StringParameterDefinition("DATE", "2000-01-01"));
        String id = submitRequest(j, "u1", job, Map.of("DATE", PLAIN),
                Map.of("DATA", uploadFile("report.bin", content)));
        assertEquals(fileDisplay("report.bin"), RunRequestService.get().load(id).getParameters().get("DATA"));

        approve(id);
        j.waitUntilNoActivity();

        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull(run, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(run);
        FilePath ws = j.jenkins.getWorkspaceFor(job);
        assertArrayEquals(content, bytes(ws.child("DATA")), "unstash 'DATA' must restore exactly the uploaded bytes");
        assertArrayEquals(content, bytes(ws.child("via-wrapper.bin")), "withFileParameter must expose exactly the uploaded bytes");
        assertEquals("report.bin", ws.child("filename.txt").readToString(), "DATA_FILENAME must be the original file name");
        assertEquals(id, run.getCause(ApprovedCause.class).getRequestId());
    }

    /**
     * T-05-44: a file-parameters {@code base64File} submitted with the Request Run form reaches the
     * approved Pipeline build byte for byte ({@code withFileParameter}, {@code B64_FILENAME});
     * neither its Base64 text nor its content appears on the request detail page, the dashboard, the
     * history screens or the CSV exports, which show {@code [file] payload.bin}; in the store the
     * Base64 lives only inside the request's own file while it is pending, nowhere once the approved
     * run has started (D-72b (5); note 265 moved the "own file" check before the approval), and the
     * decoded content never.
     */
    @Test
    public void t_05_44_base64FileReachesTheRunAndNeverAppearsAsText() throws Exception {
        byte[] content = payload(B64_MARKER, 3000);
        String base64 = Base64.getEncoder().encodeToString(content);
        WorkflowJob job = pipeline("b64-x",
                "node {\n"
                + "  withFileParameter('B64') {\n"
                + "    writeFile file: 'b64-copy.bin', text: readFile(file: env.B64, encoding: 'Base64'), encoding: 'Base64'\n"
                + "  }\n"
                + "  writeFile file: 'filename.txt', text: env.B64_FILENAME\n"
                + "}\n",
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01"));
        String id = submitRequest(j, "u1", job, Map.of("DATE", PLAIN),
                Map.of("B64", uploadFile("payload.bin", content)));
        assertEquals(fileDisplay("payload.bin"), RunRequestService.get().load(id).getParameters().get("B64"));
        String requestFile = "requests/run/" + id + ".xml";
        assertEquals(List.of(requestFile), storeFilesContaining(j, base64.substring(0, 32)),
                "while pending, the Base64 must live inside the request's own file and nowhere else in the store");
        assertTrue(storeFilesContaining(j, base64).contains(requestFile), "while pending, the request file must hold the whole Base64 (D-72)");

        approve(id);
        j.waitUntilNoActivity();
        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull(run, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(run);
        FilePath ws = j.jenkins.getWorkspaceFor(job);
        assertArrayEquals(content, bytes(ws.child("b64-copy.bin")), "the run must receive exactly the uploaded bytes");
        assertEquals("payload.bin", ws.child("filename.txt").readToString());

        List<String> forbidden = List.of(base64.substring(0, 32), B64_MARKER);
        String shown = fileDisplay("payload.bin");
        for (String user : new String[] {"u1", "a1", "admin"}) {
            String detail = readable(j, user, "batch-control/requests/" + id + "/");
            assertTrue(detail.contains(shown), "the request detail seen by " + user + " must show " + shown);
            assertAbsent("the request detail seen by " + user, detail, forbidden);
        }
        for (String kind : new String[] {"requests", "runs"}) {
            assertAbsent("the " + kind + " history", readable(j, "viewer", "batch-control/history/?kind=" + kind), forbidden);
        }
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/requests.csv",
                "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(shown), surface + " must show the file value as " + shown);
            assertAbsent(surface, body, forbidden);
        }

        assertEquals(List.of(), storeFilesContaining(j, base64.substring(0, 32)),
                "after the run the Base64 must be nowhere in the store: the request file drops its typed values once the approved"
                + " run starts (D-72b (5)), and run records, incidents, changes and CSV sources hold the masked form");
        assertEquals(List.of(), storeFilesContaining(j, B64_MARKER), "the decoded content must not be written anywhere in the store");
    }

    /**
     * T-05-45: a Password parameter given to the typed service overload reaches the approved build
     * as the original plaintext (observed inside the build, never printed), while the request's file
     * holds it only in Jenkins' encrypted form, no store file holds the plaintext, and the display
     * map and the detail page show {@code ********}. Guard: the plain value is stored verbatim and
     * reaches the build.
     */
    @Test
    public void t_05_45_passwordReachesTheBuildAndIsStoredOnlyEncrypted() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("secret-svc");
        addParameters(job, new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "TOKEN", "PLAIN"));
        setBatchControl(job, new BatchControlJobProperty(true));

        List<ParameterValue> values = new ArrayList<>();
        values.add(new PasswordParameterValue("TOKEN", SECRET));
        values.add(new StringParameterValue("PLAIN", PLAIN));
        RunRequest request = createTyped(job, values);
        Map<String, String> shown = RunRequestService.get().load(request.getId()).getParameters();
        assertEquals(MASK, shown.get("TOKEN"), "a sensitive value is shown as " + MASK);
        assertEquals(PLAIN, shown.get("PLAIN"));

        assertTrue(TypedParameterFixtures.holdsEncrypted(TypedParameterFixtures.requestFile(j, request.getId()), SECRET),
                "the request's file must hold the secret in Jenkins' encrypted form (D-72), so the run can receive it");
        assertEquals(List.of(), storeFilesContaining(j, SECRET), "no store file may hold the secret in plaintext");
        assertEquals(List.of(), storeFilesContaining(j, SECRET_DEFAULT), "no store file may hold the definition default in plaintext");
        assertFalse(storeFilesContaining(j, PLAIN).isEmpty(), "guard: the plain value is in the store, so the scan reads the right place");

        approve(request.getId());
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must have run as #1");
        j.assertBuildStatusSuccess(build);
        assertEquals(SECRET, TypedParameterFixtures.CaptureEnv.seen("secret-svc", 1, "TOKEN"),
                "the approved build must receive the original secret (D-72; P-03 option ① superseded)");
        assertEquals(PLAIN, TypedParameterFixtures.CaptureEnv.seen("secret-svc", 1, "PLAIN"));
        ParameterValue token = build.getAction(ParametersAction.class).getParameter("TOKEN");
        assertTrue(token instanceof PasswordParameterValue && token.isSensitive(), "the secret stays a sensitive Password value: " + token);

        assertEquals(List.of(), storeFilesContaining(j, SECRET), "after the run no store file (run records included) holds the plaintext");
        String detail = readable(j, "a1", "batch-control/requests/" + request.getId() + "/");
        assertTrue(detail.contains(MASK) && detail.contains(PLAIN), "the detail page shows the mask and the plain value");
        assertAbsent("the request detail", detail, List.of(SECRET, SECRET_DEFAULT));
    }

    /**
     * T-05-46: the typed values are not reachable as text: the request detail page (requester,
     * approver, administrator), the request lists, the job's Request Run page, the history screen,
     * {@code requests.csv} and every REST/JSON/XML API under the request (if exported at all) never
     * contain the plaintext secret, the Base64 text or the file content. Guard: the detail page shows
     * {@code ********}, {@code [file] payload.bin} and the plain value.
     */
    @Test
    public void t_05_46_typedValuesAreNotExposedThroughPagesOrApis() throws Exception {
        byte[] content = payload(B64_MARKER, 2000);
        String base64 = Base64.getEncoder().encodeToString(content);
        FreeStyleProject job = j.createFreeStyleProject("expose-x");
        addParameters(job, new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token"),
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("PLAIN", "plain-default"));
        setBatchControl(job, new BatchControlJobProperty(true));
        Base64FileParameterValue file = new Base64FileParameterValue("B64");
        file.setFile(fileItem("payload.bin", content));
        List<ParameterValue> values = new ArrayList<>();
        values.add(new PasswordParameterValue("TOKEN", SECRET));
        values.add(file);
        values.add(new StringParameterValue("PLAIN", PLAIN));
        String id = createTyped(job, values).getId();

        List<String> forbidden = List.of(SECRET, SECRET_DEFAULT, base64.substring(0, 32), B64_MARKER);
        String detail = readable(j, "admin", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains(MASK), "guard: the detail page shows the mask");
        assertTrue(detail.contains(fileDisplay("payload.bin")), "guard: the detail page shows the file name");
        assertTrue(detail.contains(PLAIN), "guard: the detail page shows the plain value");
        for (String user : new String[] {"u1", "a1", "admin"}) {
            assertAbsent("the request detail seen by " + user, readable(j, user, "batch-control/requests/" + id + "/"), forbidden);
            assertAbsent("the request list seen by " + user, readable(j, user, "batch-control/requests/"), forbidden);
        }
        assertAbsent("the job's Request Run page", readable(j, "u1", job.getUrl() + "batch-control/"), forbidden);
        assertAbsent("the overview", readable(j, "admin", "batch-control/"), forbidden);
        assertAbsent("the requests history", readable(j, "admin", "batch-control/history/?kind=requests"), forbidden);
        assertAbsent("requests.csv", readable(j, "admin", "batch-control/history/requests.csv"), forbidden);
        for (String api : new String[] {
                "batch-control/requests/" + id + "/api/json?depth=5",
                "batch-control/requests/" + id + "/api/xml?depth=5",
                "batch-control/requests/api/json?depth=5",
                "batch-control/api/json?depth=5",
                job.getUrl() + "batch-control/api/json?depth=5",
                "batch-control/requests/" + id + "/parameterValues/",
                "batch-control/requests/" + id + "/parameterValues/api/json?depth=5",
                "batch-control/requests/" + id + "/parameterValues/0/"}) {
            for (String user : new String[] {"admin", "u1"}) {
                Page page = TypedParameterFixtures.get(j, user, api);
                assertAbsent(api + " (HTTP " + page.getWebResponse().getStatusCode() + ", " + user + ")",
                        page.getWebResponse().getContentAsString(), forbidden);
            }
        }
    }

    /**
     * T-05-47 (unchanged behaviour): string, text, boolean and choice values submitted with the
     * Request Run form reach the approved build exactly, with core's value types, and the display
     * map shows them verbatim.
     */
    @Test
    public void t_05_47_simpleValuesStillRoundTripExactly() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("simple-x");
        addParameters(job,
                new StringParameterDefinition("P", "p-default", "a string"),
                new TextParameterDefinition("T", "t-default", "a text"),
                new BooleanParameterDefinition("B", false, "a boolean"),
                new ChoiceParameterDefinition("C", new String[] {"first", "second", "third"}, "a choice"));
        setBatchControl(job, new BatchControlJobProperty(true));
        Map<String, String> typed = new LinkedHashMap<>();
        typed.put("P", "typed p");
        typed.put("T", "line one");
        typed.put("B", "true");
        typed.put("C", "second");
        String id = submitRequest(j, "u1", job, typed, Map.of());

        Map<String, String> shown = RunRequestService.get().load(id).getParameters();
        assertEquals("typed p", shown.get("P"));
        assertEquals("line one", shown.get("T"));
        assertEquals("true", shown.get("B"));
        assertEquals("second", shown.get("C"));

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build);
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertSame(StringParameterValue.class, parameters.getParameter("P").getClass());
        assertEquals("typed p", parameters.getParameter("P").getValue());
        assertSame(TextParameterValue.class, parameters.getParameter("T").getClass());
        assertEquals("line one", parameters.getParameter("T").getValue());
        assertSame(BooleanParameterValue.class, parameters.getParameter("B").getClass());
        assertEquals(Boolean.TRUE, parameters.getParameter("B").getValue());
        assertEquals("second", parameters.getParameter("C").getValue());
    }

    /**
     * T-05-48 (unchanged behaviour): approval matching is unchanged with typed values: the approved
     * request with a file runs exactly once (EXECUTED, its run id, the ApprovedCause naming it); a
     * second approval and a re-queue presenting the consumed marker are refused and run nothing.
     */
    @Test
    public void t_05_48_approvedTypedRequestStillRunsExactlyOnce() throws Exception {
        byte[] content = payload(CORE_MARKER, 1000);
        FreeStyleProject job = coreFileJob("match-x");
        List<ParameterValue> values = new ArrayList<>();
        values.add(new FileParameterValue("UPLOAD", uploadFile("data.csv", content), "data.csv"));
        values.add(new StringParameterValue("DATE", PLAIN));
        String id = createTyped(job, values).getId();

        approve(id);
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "the approved request must run exactly once");
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")));
        RunRequest executed = RunRequestService.get().load(id);
        assertEquals(RequestStatus.EXECUTED, executed.getStatus());
        assertEquals("match-x#1", executed.getExecutedRunId());
        assertEquals(id, build.getCause(ApprovedCause.class).getRequestId());

        assertRefused("a second approval of an executed request must be refused", () -> approve(id));
        ApprovedRunAction marker = build.getAction(ApprovedRunAction.class);
        assertNotNull(marker, "the executed run must carry the approval marker");
        try (ACLContext ignored = as("u1")) {
            try {
                Future<?> requeued = job.scheduleBuild2(0, new Cause.UserIdCause(), marker);
                assertNull(requeued, "a consumed marker must not schedule the job again");
            } catch (Failure refusedWithGuidance) {
                // a refusal with guidance is equally a refusal
            }
        }
        j.waitUntilNoActivity();
        assertEquals(2, job.getNextBuildNumber(), "nothing may have run after the single approved run");
        assertTrue(j.jenkins.getQueue().isEmpty(), "the queue must be empty");
    }

    /**
     * T-05-49 (unchanged behaviour, D-22): the 10,000-character limit applies to typed text and
     * string values given to the service: 10,001 characters are refused and nothing is stored;
     * exactly 10,000 characters are accepted (boundary guard).
     */
    @Test
    public void t_05_49_tenThousandCharacterLimitAppliesToTypedTextValues() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("limit-x");
        addParameters(job, new TextParameterDefinition("T", "", "a text"), new StringParameterDefinition("S", "", "a string"));
        setBatchControl(job, new BatchControlJobProperty(true));
        Set<String> before = ApproverFormFixtures.runRequestIds();

        assertRejectedAsInvalid("a text value over 10,000 characters must refuse the request",
                () -> createTyped(job, List.of(new TextParameterValue("T", "x".repeat(10_001)))));
        assertRejectedAsInvalid("a string value over 10,000 characters must refuse the request",
                () -> createTyped(job, List.of(new StringParameterValue("S", "x".repeat(10_001)))));
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "nothing may be stored from the refused requests");

        List<ParameterValue> atLimit = new ArrayList<>();
        atLimit.add(new TextParameterValue("T", "y".repeat(10_000)));
        atLimit.add(new StringParameterValue("S", "z".repeat(10_000)));
        RunRequest accepted = createTyped(job, atLimit);
        assertEquals(RequestStatus.PENDING, accepted.getStatus());
        assertEquals("y".repeat(10_000), RunRequestService.get().load(accepted.getId()).getParameters().get("T"));
    }

    /**
     * T-05-50 (unchanged behaviour, D-22): over the Request Run form, a text parameter of 10,001
     * characters stores no request; 10,000 characters store one (boundary guard).
     */
    @Test
    public void t_05_50_tenThousandCharacterLimitAppliesToTextValuesOnTheForm() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("limit-form");
        addParameters(job, new TextParameterDefinition("T", "", "a text"));
        setBatchControl(job, new BatchControlJobProperty(true));
        Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = TypedParameterFixtures.browser(j, "u1");
        HtmlForm form = TypedParameterFixtures.requestRunForm(j, wc, job);
        TypedParameterFixtures.setValue(form, "T", "x".repeat(10_001));
        TypedParameterFixtures.submit(wc, form, "an over-long text value", "a1");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "a text value over 10,000 characters must store no request");

        String id = submitRequest(j, "u1", job, Map.of("T", "y".repeat(10_000)), Map.of());
        assertEquals("y".repeat(10_000), RunRequestService.get().load(id).getParameters().get("T"));
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "nothing runs before an approval");
    }

    /**
     * T-05-93 (S7 m-1): a parameter value of another type that carries a {@code Secret} field (not a
     * {@code PasswordParameterValue}, not flagged sensitive) given to the typed service overload:
     * the request's file holds it only in Jenkins' encrypted form, no store file holds the
     * plaintext, the display map, the detail page, {@code requests.csv} and the history show
     * {@code ********}; the approved build receives the original, and afterwards no store file
     * (run records included) and neither {@code runs.csv} nor the runs history holds the plaintext.
     * Guard: the plain value next to it is stored and shown verbatim.
     */
    @Test
    public void t_05_93_otherSecretCarryingValueIsStoredEncryptedAndDelivered() throws Exception {
        String secret = "t0ken-s3cr3t-d72b-Pz7";
        FreeStyleProject job = j.createFreeStyleProject("token-x");
        addParameters(job, new CustomParameterFixtures.TokenParameterDefinition("KEY"),
                new StringParameterDefinition("PLAIN", "plain-default"));
        job.getBuildersList().add(new TypedParameterFixtures.CaptureEnv(false, "KEY", "PLAIN"));
        setBatchControl(job, new BatchControlJobProperty(true));

        List<ParameterValue> values = new ArrayList<>();
        values.add(new CustomParameterFixtures.TokenParameterValue("KEY", Secret.fromString(secret)));
        values.add(new StringParameterValue("PLAIN", PLAIN));
        String id = createTyped(job, values).getId();

        assertEquals(MASK, RunRequestService.get().load(id).getParameters().get("KEY"), "a Secret value is shown as " + MASK);
        assertEquals(PLAIN, RunRequestService.get().load(id).getParameters().get("PLAIN"));
        assertTrue(TypedParameterFixtures.holdsEncrypted(TypedParameterFixtures.requestFile(j, id), secret),
                "the request's file must hold the Secret field in Jenkins' encrypted form");
        assertEquals(List.of(), storeFilesContaining(j, secret), "no store file may hold the plaintext");
        assertFalse(storeFilesContaining(j, PLAIN).isEmpty(), "guard: the plain value is in the store, so the scan reads the right place");
        String detail = readable(j, "a1", "batch-control/requests/" + id + "/");
        assertTrue(detail.contains(MASK) && detail.contains(PLAIN), "the detail page shows the mask and the plain value");
        assertAbsent("the request detail", detail, List.of(secret));
        assertAbsent("requests.csv", readable(j, "viewer", "batch-control/history/requests.csv"), List.of(secret));
        assertAbsent("the requests history", readable(j, "viewer", "batch-control/history/?kind=requests"), List.of(secret));

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the approved request must run");
        j.assertBuildStatusSuccess(build);
        assertEquals(secret, TypedParameterFixtures.CaptureEnv.seen("token-x", 1, "KEY"), "the approved build must receive the original secret");
        assertEquals(PLAIN, TypedParameterFixtures.CaptureEnv.seen("token-x", 1, "PLAIN"));
        assertEquals(List.of(), storeFilesContaining(j, secret), "after the run no store file (run records included) holds the plaintext");
        String runsCsv = readable(j, "viewer", "batch-control/history/runs.csv");
        assertTrue(runsCsv.contains(PLAIN), "guard: runs.csv lists the run with its plain value");
        assertAbsent("runs.csv", runsCsv, List.of(secret));
        assertAbsent("the runs history", readable(j, "viewer", "batch-control/history/?kind=runs"), List.of(secret));
    }

    /**
     * T-05-96 (S7 m-10 (a)): Batch Control does not copy file content: a core {@code file} request
     * (Freestyle) and a {@code stashedFile} request (Pipeline) submitted on the form leave neither
     * file's content, nor its Base64, in any file of the Batch Control store, while pending and
     * after the approved runs. Guard: the store does hold the request ({@code [file] <name>}).
     */
    @Test
    public void t_05_96_coreAndStashedFileContentIsNeverInTheStore() throws Exception {
        byte[] coreContent = payload(CORE_MARKER, 2500);
        byte[] stashContent = payload(STASH_MARKER, 2500);
        FreeStyleProject core = coreFileJob("scan-core");
        WorkflowJob stash = pipeline("scan-stash", "node {\n  unstash 'DATA'\n}\n", new StashedFileParameterDefinition("DATA"));
        String coreId = submitRequest(j, "u1", core, Map.of("DATE", PLAIN), Map.of("UPLOAD", uploadFile("scan.csv", coreContent)));
        String stashId = submitRequest(j, "u1", stash, Map.of(), Map.of("DATA", uploadFile("scan.bin", stashContent)));
        List<String> forbidden = List.of(CORE_MARKER, STASH_MARKER,
                Base64.getEncoder().encodeToString(coreContent).substring(0, 32),
                Base64.getEncoder().encodeToString(stashContent).substring(0, 32));

        assertFalse(storeFilesContaining(j, fileDisplay("scan.csv")).isEmpty(), "guard: the store holds the core file request");
        assertFalse(storeFilesContaining(j, fileDisplay("scan.bin")).isEmpty(), "guard: the store holds the stashed file request");
        for (String needle : forbidden) {
            assertEquals(List.of(), storeFilesContaining(j, needle), "while pending, no store file may hold file content: " + needle);
        }

        approve(coreId);
        approve(stashId);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(core.getBuildByNumber(1));
        j.assertBuildStatusSuccess(stash.getBuildByNumber(1));
        for (String needle : forbidden) {
            assertEquals(List.of(), storeFilesContaining(j, needle), "after the runs, no store file may hold file content: " + needle);
        }
    }

    /**
     * T-05-97 (S7 m-10 (d), SPEC item 1): with run control off, an approval-required job with a core
     * file parameter behaves as Jenkins does: core's build form submitted with a file queues and
     * runs the build, whose workspace file holds exactly the uploaded bytes; no run request is
     * created and the upload is not disposed of before the build used it.
     */
    @Test
    public void t_05_97_runControlOffLeavesTypedSubmissionsUnchanged() throws Exception {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(false);
        cfg.save();
        byte[] content = payload("switch-off-marker-Vd97", 3000);
        FreeStyleProject job = coreFileJob("off-x");
        assertTrue(job.getProperty(BatchControlJobProperty.class).isApprovalRequired(), "premise: the job is approval-required");
        java.util.Set<String> before = ApproverFormFixtures.runRequestIds();

        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin");
        org.htmlunit.html.HtmlPage formPage = wc.getPage(new java.net.URL(j.getURL(), job.getUrl() + "build?delay=0sec"));
        HtmlForm form = formPage.getFormByName("parameters");
        TypedParameterFixtures.setValue(form, "DATE", PLAIN);
        TypedParameterFixtures.setFile(form, "UPLOAD", uploadFile("off.csv", content));
        Page answer = j.submit(form);
        assertTrue(answer.getWebResponse().getStatusCode() < 400, "core's build form must be accepted with run control off, got HTTP "
                + answer.getWebResponse().getStatusCode());
        j.waitUntilNoActivity();

        FreeStyleBuild build = job.getBuildByNumber(1);
        assertNotNull(build, "the build must run as in Jenkins");
        j.assertBuildStatusSuccess(build);
        assertArrayEquals(content, bytes(build.getWorkspace().child("UPLOAD")), "the build must receive exactly the uploaded bytes");
        assertEquals(PLAIN, ((StringParameterValue) build.getAction(ParametersAction.class).getParameter("DATE")).getValue());
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no run request may be created with run control off");
    }

    // ---------------------------------------------------------------- helpers

    /** The descriptor of the Secret-carrying parameter type used by T-05-93. */
    @TestExtension
    public static final class TokenDescriptor extends CustomParameterFixtures.TokenDescriptorBase {
    }

    private FreeStyleProject coreFileJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        addParameters(job, new FileParameterDefinition("UPLOAD", "the input file"),
                new StringParameterDefinition("DATE", "2000-01-01", "the batch date"));
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private WorkflowJob pipeline(String name, String script, ParameterDefinition... definitions) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        addParameters(job, definitions);
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addParameters(Job<?, ?> job, ParameterDefinition... definitions) throws Exception {
        ((Job) job).addProperty(new ParametersDefinitionProperty(definitions));
    }

    private RunRequest createTyped(Job<?, ?> job, List<ParameterValue> values) {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, values, "month-end batch with typed values", "a1");
        }
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "checked the parameters");
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private static void assertRejectedAsInvalid(String message, Executable action) {
        boolean rejected = false;
        try {
            action.execute();
        } catch (IllegalArgumentException | Failure expected) {
            rejected = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - expected IllegalArgumentException or Failure, got " + other, other);
        }
        assertTrue(rejected, message);
    }

    private static void assertRefused(String message, Executable action) {
        boolean refused = false;
        try {
            action.execute();
        } catch (RuntimeException expected) {
            refused = true;
        } catch (Throwable other) {
            throw new AssertionError(message + " - unexpected exception " + other, other);
        }
        assertTrue(refused, message);
    }
}
