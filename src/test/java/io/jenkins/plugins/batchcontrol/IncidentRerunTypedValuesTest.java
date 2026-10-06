package io.jenkins.plugins.batchcontrol;

import hudson.FilePath;
import hudson.model.Cause;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.ops.RerunNeedsFormException;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.net.URL;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.CaptureEnv;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.MASK;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.bytes;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.query;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.storeFilesContaining;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SPEC item 11, D-72: "Request rerun" on an incident creates a RunRequest that carries the failed
 * run's own parameter values, including the original secret and the file values that can still be
 * recovered from the build. When a value cannot be recovered (a stashed file, which the build
 * clears when it completes, or a deleted build) the rerun creates no request and opens the job's
 * Request Run form prefilled with the recoverable non-sensitive values; secrets are never
 * prefilled. Frozen contract: that answer is a 302 to {@code job/<name>/batch-control/?p.<NAME>=...}
 * and the service throws {@code RerunNeedsFormException} whose {@code getPrefill()} holds the
 * recoverable non-sensitive simple values. Matrix rows T-11-08 .. T-11-12 (note 260); D-72b adds
 * T-11-21 (a run with a repeated name), T-11-22 (the job was deleted and re-created, S-35-06),
 * T-11-23 (a {@code base64File} value is reused) and T-11-24 (a refused rerun leaves no recreated
 * file copy) (note 265).
 *
 * <p>The failed runs are unattended submissions of an activated job taken out of run control, which
 * is then put under approval (the RequesterBuildPermissionTest pattern). Values seen inside a build
 * are recorded by {@link CaptureEnv} without being printed.
 *
 * <p>Written from docs/SPEC.md item 11 and 5, docs/DECISIONS.md D-72 and the frozen D-72 contract
 * only (no src/main knowledge).
 */
@WithJenkins
public class IncidentRerunTypedValuesTest {

    private static final String SECRET = "rerun-s3cr3t-d72-Kq3";
    private static final String SECRET_DEFAULT = "rerun-d3fault-d72-Vb6";
    private static final String DATE = "2026-09-30";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        CaptureEnv.SEEN.clear();
    }

    /**
     * T-11-08: a failed Freestyle run with a password and a string parameter; "Request Rerun"
     * creates a linked request (shown masked) and its approved run receives the original password
     * and string; no store file holds the plaintext.
     */
    @Test
    public void t_11_08_rerunCarriesTheOriginalSecretAndString() throws Exception {
        FreeStyleProject job = failedSecretRun("rerun-secret");
        Incident incident = incidentFor("rerun-secret#1");
        assertEquals(SECRET, CaptureEnv.seen("rerun-secret", 1, "TOKEN"), "premise: the failed run had the secret");
        assertEquals(MASK, incident.getParameters().get("TOKEN"), "the incident shows the secret masked");
        assertEquals(DATE, incident.getParameters().get("DATE"));

        Set<String> before = ApproverFormFixtures.runRequestIds();
        WebResponse response = postRerun(incident);
        ApproverFormFixtures.assertSuccess(response, "the rerun POST");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "the rerun must create exactly one request, got " + created);
        String id = created.iterator().next();
        RunRequest rerun = RunRequestService.get().load(id);
        assertEquals(RequestStatus.PENDING, rerun.getStatus());
        assertEquals(incident.getId(), rerun.getIncidentId());
        assertEquals(MASK, rerun.getParameters().get("TOKEN"));
        assertEquals(DATE, rerun.getParameters().get("DATE"));
        assertEquals(List.of(), storeFilesContaining(j, SECRET), "no store file may hold the secret in plaintext");

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild second = job.getBuildByNumber(2);
        assertNotNull(second, "the approved rerun must run as #2");
        j.assertBuildStatusSuccess(second);
        assertEquals(SECRET, CaptureEnv.seen("rerun-secret", 2, "TOKEN"), "the rerun must receive the original secret");
        assertEquals(DATE, CaptureEnv.seen("rerun-secret", 2, "DATE"), "the rerun must receive the original string");
        assertEquals(List.of(), storeFilesContaining(j, SECRET), "after the rerun no store file holds the plaintext");
    }

    /**
     * T-11-09: a failed Pipeline run with a stashed file, a string and a password: the stashed file
     * cannot be recovered, so "Request Rerun" answers 302 to the job's Request Run form carrying only
     * {@code p.DATE} (no secret, no file); no request is created or linked; the form opens with DATE
     * filled in and without the secret.
     */
    @Test
    public void t_11_09_unrecoverableStashedFileLeadsToThePrefilledForm() throws Exception {
        WorkflowJob job = failedStashRun("rerun-stash");
        Incident incident = incidentFor("rerun-stash#1");
        assertEquals(fileDisplay("report.bin"), incident.getParameters().get("DATA"), "the incident shows the file by name");
        assertEquals(MASK, incident.getParameters().get("TOKEN"));
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse response = postRerun(incident);
        assertEquals(302, response.getStatusCode(), "an unrecoverable value must lead to the Request Run form: "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        String location = response.getResponseHeaderValue("Location");
        assertNotNull(location, "the 302 must carry a Location");
        URL target = new URL(new URL(j.getURL(), "batch-control/incidents/" + incident.getId() + "/rerun"), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath(),
                "the redirect must lead to the job's Request Run form");
        Map<String, String> query = query(target);
        assertEquals(DATE, query.get("p.DATE"), "the recoverable non-sensitive value must be carried: " + location);
        assertFalse(query.containsKey("p.TOKEN"), "a secret is never prefilled: " + location);
        assertFalse(query.containsKey("p.DATA"), "a file value is not carried in the URL: " + location);
        assertFalse(location.contains(SECRET) || location.contains(SECRET_DEFAULT), "no secret may appear in the redirect: " + location);
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertNoRerunLinked(incident);

        Page form = UsabilityFixtures.client(j, "u1").getPage(target);
        assertEquals(200, form.getWebResponse().getStatusCode(), "the Request Run form must open");
        assertTrue(form instanceof HtmlPage, "the Request Run form must be HTML");
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith((HtmlPage) form, job.getUrl() + "batch-control/submit");
        assertFalse(forms.isEmpty(), "the page must carry the Request Run form: " + UsabilityFixtures.formActions((HtmlPage) form));
        HtmlForm requestForm = forms.get(0);
        assertEquals(DATE, TypedParameterFixtures.valueOf(requestForm, "DATE"), "the form must be prefilled with DATE");
        String html = form.getWebResponse().getContentAsString();
        assertFalse(html.contains(SECRET), "the form must not carry the secret");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "opening the form stores nothing");
    }

    /**
     * T-11-10: the same incident through the service: {@code IncidentService.rerun} throws
     * {@code RerunNeedsFormException} whose prefill is exactly {@code {DATE=<value>}} (no secret,
     * no file); nothing is stored or linked.
     */
    @Test
    public void t_11_10_serviceRerunOfAnUnrecoverableValueThrowsWithThePrefill() throws Exception {
        failedStashRun("rerun-stash-svc");
        Incident incident = incidentFor("rerun-stash-svc#1");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        try (ACLContext ignored = as("u1")) {
            IncidentService.get().rerun(incident.getId(), "a1");
            fail("a rerun whose stashed file cannot be recovered must not create a request");
        } catch (RerunNeedsFormException expected) {
            assertEquals(Map.of("DATE", DATE), expected.getPrefill(),
                    "the prefill holds the recoverable non-sensitive simple values only");
        }
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertNoRerunLinked(incident);
    }

    /**
     * T-11-11: a failed Freestyle run with a core file whose copy is still under the build directory:
     * the rerun creates the request directly (shown as {@code [file] data.csv}) and its approved run
     * receives exactly the original bytes (in its workspace after the first run's copy was removed,
     * and in its own build directory).
     */
    @Test
    public void t_11_11_recoverableCoreFileIsCarriedIntoTheRerun() throws Exception {
        byte[] content = payload("rerun-core-marker-Ux52", 3500);
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rerun-file"));
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new CaptureEnv(true, "DATE"));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(
                new FileParameterValue("UPLOAD", uploadFile("data.csv", content), "data.csv"),
                new StringParameterValue("DATE", DATE))));
        j.waitUntilNoActivity();
        FreeStyleBuild first = job.getBuildByNumber(1);
        assertArrayEquals(content, Files.readAllBytes(first.getRootDir().toPath().resolve("fileParameters").resolve("UPLOAD")),
                "premise: the failed build keeps its copy of the file");
        first.getWorkspace().child("UPLOAD").delete();
        assertFalse(first.getWorkspace().child("UPLOAD").exists(), "premise: the workspace no longer has the first run's file");
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = incidentFor("rerun-file#1");
        assertEquals(fileDisplay("data.csv"), incident.getParameters().get("UPLOAD"), "the incident shows the file by name");

        Set<String> before = ApproverFormFixtures.runRequestIds();
        WebResponse response = postRerun(incident);
        ApproverFormFixtures.assertSuccess(response, "the rerun POST");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "a recoverable file must not send the user to the form; created " + created
                + ", answer " + response.getStatusCode() + " " + response.getResponseHeaderValue("Location"));
        String id = created.iterator().next();
        assertEquals(fileDisplay("data.csv"), RunRequestService.get().load(id).getParameters().get("UPLOAD"));

        approve(id);
        j.waitUntilNoActivity();
        FreeStyleBuild second = job.getBuildByNumber(2);
        assertNotNull(second, "the approved rerun must run as #2");
        j.assertBuildStatusSuccess(second);
        assertArrayEquals(content, bytes(second.getWorkspace().child("UPLOAD")), "the rerun must receive the original bytes");
        assertArrayEquals(content, Files.readAllBytes(second.getRootDir().toPath().resolve("fileParameters").resolve("UPLOAD")),
                "the rerun's own copy must hold the original bytes");
        assertEquals(DATE, CaptureEnv.seen("rerun-file", 2, "DATE"));
    }

    /**
     * T-11-12: a failed Freestyle run with a password and a string whose build was then deleted: the
     * rerun answers 302 to the Request Run form with {@code p.DATE} only (no secret); no request is
     * created.
     */
    @Test
    public void t_11_12_deletedBuildLeadsToThePrefilledFormWithoutTheSecret() throws Exception {
        FreeStyleProject job = failedSecretRun("rerun-deleted");
        Incident incident = incidentFor("rerun-deleted#1");
        job.getBuildByNumber(1).delete();
        assertTrue(job.getBuilds().isEmpty(), "premise: the failed build is deleted");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse response = postRerun(incident);
        assertEquals(302, response.getStatusCode(), "a deleted build must lead to the Request Run form: "
                + UsabilityFixtures.excerpt(response.getContentAsString()));
        String location = response.getResponseHeaderValue("Location");
        assertNotNull(location);
        URL target = new URL(new URL(j.getURL(), "batch-control/incidents/" + incident.getId() + "/rerun"), location);
        assertEquals(new URL(j.getURL(), job.getUrl() + "batch-control/").getPath(), target.getPath());
        Map<String, String> query = query(target);
        assertEquals(DATE, query.get("p.DATE"), "the non-sensitive value recorded on the incident must be carried: " + location);
        assertFalse(query.containsKey("p.TOKEN"), "a secret is never prefilled: " + location);
        assertFalse(location.contains(SECRET) || location.contains(SECRET_DEFAULT), "no secret may appear in the redirect");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created");
        assertNoRerunLinked(incident);
    }

    /**
     * T-11-21 (S-35-01, rerun path): a failed run whose build carries TARGET twice ({@code staging},
     * {@code production}): "Request Rerun" creates no request (it refuses, or falls back to the
     * form) and links none, over HTTP (no server error) and through {@code IncidentService.rerun};
     * nothing runs. Guard: the incident of a run with TARGET once creates a request showing it.
     */
    @Test
    public void t_11_21_rerunNeverCreatesARequestWithARepeatedName() throws Exception {
        FreeStyleProject dup = failedTargetRun("rerun-dup", new StringParameterValue("TARGET", "staging"),
                new StringParameterValue("TARGET", "production"));
        long targets = dup.getBuildByNumber(1).getAction(ParametersAction.class).getParameters().stream()
                .filter(v -> "TARGET".equals(v.getName())).count();
        assertEquals(2, targets, "premise: the failed build carries TARGET twice");
        Incident incident = incidentFor("rerun-dup#1");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse response = postRerun(incident, "a1");
        assertTrue(response.getStatusCode() < 500, "the rerun must be answered without a server error, got HTTP "
                + response.getStatusCode() + ": " + UsabilityFixtures.excerpt(response.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the rerun of a run with a repeated name", response.getContentAsString(), null);
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created from a repeated name");
        assertNoRerunLinked(incident);

        try (ACLContext ignored = as("u1")) {
            IncidentService.get().rerun(incident.getId(), "a1");
            fail("the service must not create a rerun request that repeats a parameter name");
        } catch (IllegalArgumentException | RerunNeedsFormException | hudson.model.Failure refused) {
            // a refusal, or the form fallback: either way nothing is created
        }
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "no request may be created through the service either");
        assertNoRerunLinked(incident);
        j.waitUntilNoActivity();
        assertEquals(2, dup.getNextBuildNumber(), "nothing may run after the failed build");

        failedTargetRun("rerun-once", new StringParameterValue("TARGET", "staging"));
        Incident once = incidentFor("rerun-once#1");
        ApproverFormFixtures.assertSuccess(postRerun(once, "a1"), "guard: the rerun of a run with TARGET once");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "guard: the rerun creates exactly one request, got " + created);
        assertEquals("staging", RunRequestService.get().load(created.iterator().next()).getParameters().get("TARGET"));
    }

    /**
     * T-11-22 (S-35-06): job X fails as {@code X#1} (TARGET {@code original-target}, a password);
     * X is deleted and re-created, and the new {@code X#1} fails with other values. Rerunning the
     * first incident does not reuse the new build's values: no request is created (form fallback
     * or refusal); a redirect carries neither the new value nor a secret, and if it carries TARGET
     * it is the incident's own; the service creates nothing and its prefill holds no new value.
     * Guard: the same incident on a job that was not re-created reruns with its own values.
     */
    @Test
    public void t_11_22_rerunAfterTheJobWasRecreatedDoesNotReuseTheNewBuild() throws Exception {
        FreeStyleProject original = failedTargetRun("rerun-recreated", new StringParameterValue("TARGET", "original-target"),
                new PasswordParameterValue("TOKEN", SECRET));
        Incident incident = incidentFor("rerun-recreated#1");
        original.delete();
        String intruderSecret = "intruder-s3cr3t-d72b-Wm2";
        FreeStyleProject recreated = failedTargetRun("rerun-recreated", new StringParameterValue("TARGET", "intruder-target"),
                new PasswordParameterValue("TOKEN", intruderSecret));
        assertEquals("intruder-target", CaptureEnv.seen("rerun-recreated", 1, "TARGET"), "premise: the new #1 ran with other values");
        assertNotNull(recreated.getBuildByNumber(1), "premise: the re-created job has its own #1");
        Set<String> before = ApproverFormFixtures.runRequestIds();

        WebResponse response = postRerun(incident, "a1");
        assertTrue(response.getStatusCode() < 500, "the rerun must be answered without a server error, got HTTP " + response.getStatusCode());
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the rerun must not create a request from another build's values");
        assertNoRerunLinked(incident);
        if (response.getStatusCode() == 302) {
            String location = response.getResponseHeaderValue("Location");
            assertNotNull(location);
            assertFalse(location.contains("intruder"), "the fallback must not carry the new build's value: " + location);
            assertFalse(location.contains(SECRET) || location.contains(intruderSecret), "no secret may appear in the redirect");
            URL target = new URL(new URL(j.getURL(), "batch-control/incidents/" + incident.getId() + "/rerun"), location);
            String carried = query(target).get("p.TARGET");
            assertTrue(carried == null || "original-target".equals(carried), "a carried TARGET must be the incident's own: " + location);
        } else {
            UsabilityFixtures.assertPlainRefusal("the rerun after the job was re-created", response.getContentAsString(), null);
        }

        try (ACLContext ignored = as("u1")) {
            IncidentService.get().rerun(incident.getId(), "a1");
            fail("the service must not create a request from the re-created job's build");
        } catch (RerunNeedsFormException fallback) {
            assertFalse(fallback.getPrefill().containsValue("intruder-target"), "the prefill must not hold the new build's value: "
                    + fallback.getPrefill());
        } catch (RuntimeException refused) {
            // a refusal is equally acceptable
        }
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "nothing may be created through the service either");

        failedTargetRun("rerun-kept", new StringParameterValue("TARGET", "kept-target"), new PasswordParameterValue("TOKEN", SECRET));
        Incident kept = incidentFor("rerun-kept#1");
        ApproverFormFixtures.assertSuccess(postRerun(kept, "a1"), "guard: the rerun of the job's own run");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "guard: the job's own run is reused directly, got " + created);
        assertEquals("kept-target", RunRequestService.get().load(created.iterator().next()).getParameters().get("TARGET"));
    }

    /**
     * T-11-23 (S7 m-10 (b)): a failed Pipeline run with a {@code base64File} B64 and DATE: the value
     * is recoverable from the build, so "Request Rerun" creates the request directly (shown as
     * {@code [file] payload.bin}), and its approved run receives exactly the original bytes (the
     * first run's copy was removed from the workspace).
     */
    @Test
    public void t_11_23_rerunReusesABase64FileValue() throws Exception {
        byte[] content = payload("rerun-b64-marker-Ud23", 2200);
        WorkflowJob job = uncontrolled(j.createProject(WorkflowJob.class, "rerun-b64"));
        job.setDefinition(new CpsFlowDefinition("node {\n"
                + "  withFileParameter('B64') {\n"
                + "    writeFile file: 'b64-copy.bin', text: readFile(file: env.B64, encoding: 'Base64'), encoding: 'Base64'\n"
                + "  }\n"
                + "}\n"
                + "if (currentBuild.number == 1) { error 'the batch failed' }\n", true));
        job.addProperty(new ParametersDefinitionProperty(new Base64FileParameterDefinition("B64"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        BatchControlFixtures.activateAsAdmin(job);
        Base64FileParameterValue value = new Base64FileParameterValue("B64");
        value.setFile(TypedParameterFixtures.fileItem("payload.bin", content));
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(value, new StringParameterValue("DATE", DATE))));
        j.waitUntilNoActivity();
        FilePath copy = j.jenkins.getWorkspaceFor(job).child("b64-copy.bin");
        assertArrayEquals(content, bytes(copy), "premise: the failed run received the file");
        copy.delete();
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = incidentFor("rerun-b64#1");
        assertEquals(fileDisplay("payload.bin"), incident.getParameters().get("B64"), "the incident shows the file by name");

        Set<String> before = ApproverFormFixtures.runRequestIds();
        WebResponse response = postRerun(incident, "a1");
        ApproverFormFixtures.assertSuccess(response, "the rerun POST");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "a base64File value is recoverable; the rerun must create the request directly, got " + created
                + ", answer " + response.getStatusCode() + " " + response.getResponseHeaderValue("Location"));
        String id = created.iterator().next();
        assertEquals(fileDisplay("payload.bin"), RunRequestService.get().load(id).getParameters().get("B64"));
        assertEquals(DATE, RunRequestService.get().load(id).getParameters().get("DATE"));

        approve(id);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(job.getBuildByNumber(2));
        assertArrayEquals(content, bytes(copy), "the rerun must receive exactly the original bytes");
    }

    /**
     * T-11-24 (S7 m-10 (c)): the rerun of a failed core-file run is refused (the designated approver
     * is not on the approver list): 4xx, no request, and no copy of the file is left under core's
     * temporary directory. Guard: the accepted rerun's request does hold a recreated copy there, so
     * the detector sees such copies.
     */
    @Test
    public void t_11_24_refusedRerunLeavesNoRecreatedFileCopy() throws Exception {
        byte[] content = payload("rerun-refused-marker-Hj24", 2600);
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rerun-refused-file"));
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new CaptureEnv(true, "DATE"));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(
                new FileParameterValue("UPLOAD", uploadFile("data.csv", content), "data.csv"),
                new StringParameterValue("DATE", DATE))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = incidentFor("rerun-refused-file#1");
        Set<String> before = ApproverFormFixtures.runRequestIds();
        Set<java.nio.file.Path> temp = TypedParameterFixtures.tempFiles(j);

        ApproverFormFixtures.assertClientError(postRerun(incident, "nobody-on-the-list"), "a rerun designating a non-approver");
        assertEquals(before, ApproverFormFixtures.runRequestIds(), "the refused rerun must create no request");
        assertEquals(Set.of(), TypedParameterFixtures.added(temp, TypedParameterFixtures.tempFiles(j)),
                "the refused rerun must leave no copy of the file behind");
        assertNoRerunLinked(incident);

        ApproverFormFixtures.assertSuccess(postRerun(incident, "a1"), "guard: the rerun designating a1");
        Set<String> created = ApproverFormFixtures.runRequestIds();
        created.removeAll(before);
        assertEquals(1, created.size(), "guard: the accepted rerun creates one request");
        assertFalse(TypedParameterFixtures.under(j, TypedParameterFixtures.added(temp, TypedParameterFixtures.tempFiles(j)),
                TypedParameterFixtures.CORE_TMP_DIR).isEmpty(), "guard: the accepted rerun's request holds a recreated copy under "
                + TypedParameterFixtures.CORE_TMP_DIR);
    }

    // ---------------------------------------------------------------- helpers

    /** An activated, uncontrolled Freestyle job (TARGET, Password TOKEN) whose first run with {@code values} fails; then approval-required. */
    private FreeStyleProject failedTargetRun(String name, hudson.model.ParameterValue... values) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TARGET", "default-target"),
                new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token")));
        job.getBuildersList().add(new CaptureEnv(true, "TARGET"));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(values)));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** The rerun POST as u1 designating {@code approver} (redirects not followed). */
    private WebResponse postRerun(Incident incident, String approver) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("approvers", approver));
        params.add(new NameValuePair("approver", approver));
        params.add(new NameValuePair("reason", "rerun after the fix"));
        return ApproverFormFixtures.post(j, "u1", "batch-control/incidents/" + incident.getId() + "/rerun", params);
    }

    /** An activated, uncontrolled Freestyle job whose first run (TOKEN, DATE) fails; then put under approval. */
    private FreeStyleProject failedSecretRun(String name) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        job.addProperty(new ParametersDefinitionProperty(
                new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new CaptureEnv(true, "TOKEN", "DATE"));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(
                new PasswordParameterValue("TOKEN", SECRET), new StringParameterValue("DATE", DATE))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    /** An activated, uncontrolled Pipeline whose first run (stashed DATA, DATE, TOKEN) fails; then put under approval. */
    private WorkflowJob failedStashRun(String name) throws Exception {
        WorkflowJob job = uncontrolled(j.createProject(WorkflowJob.class, name));
        job.setDefinition(new CpsFlowDefinition("node {\n  unstash 'DATA'\n}\nerror 'the batch failed'\n", true));
        job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01"),
                new PasswordParameterDefinition("TOKEN", Secret.fromString(SECRET_DEFAULT), "token")));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(
                new StashedFileParameterValue("DATA", TypedParameterFixtures.fileItem("report.bin", payload("rerun-stash-marker-Fd09", 1500))),
                new StringParameterValue("DATE", DATE),
                new PasswordParameterValue("TOKEN", SECRET))));
        j.waitUntilNoActivity();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private Incident incidentFor(String runId) {
        Incident incident = IncidentService.get().list(YearMonth.now(BatchClock.clock())).stream()
                .filter(i -> runId.equals(i.getRunId())).findFirst().orElse(null);
        assertNotNull(incident, "fixture: the failed run " + runId + " must have opened an incident");
        return incident;
    }

    /** The rerun POST as u1 (redirects not followed); the approver under both field names. */
    private WebResponse postRerun(Incident incident) throws Exception {
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("approvers", "a1"));
        params.add(new NameValuePair("approver", "a1"));
        params.add(new NameValuePair("reason", "rerun after the fix"));
        return ApproverFormFixtures.post(j, "u1", "batch-control/incidents/" + incident.getId() + "/rerun", params);
    }

    private void assertNoRerunLinked(Incident incident) {
        Incident reloaded = IncidentService.get().load(incident.getId());
        assertTrue(reloaded.getRerunRequestIds() == null || reloaded.getRerunRequestIds().isEmpty(),
                "no rerun request may be linked to the incident");
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "rerun approved");
        }
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
