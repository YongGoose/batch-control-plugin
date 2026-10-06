package io.jenkins.plugins.batchcontrol;

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
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.fallbackTarget;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.noticeText;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.openForm;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.postRerun;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.rerunIds;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-16: incident rerun recovery corner cases. Matrix rows T-GAP-163 ..
 * T-GAP-167 (note 276).
 *
 * <p>Basis: SPEC 11 "When a value cannot be recovered (... a deleted build), the rerun does not
 * create the request directly: it opens the job's Request Run form prefilled with the recoverable
 * non-sensitive values (secret values are never prefilled)"; LIMITATIONS 16 "a core file whose
 * copy is gone ... Nothing can be recovered from a deleted build, so for it the form is filled in
 * with the non-sensitive values recorded on the incident instead; secrets and files are recorded
 * only masked, so they are never filled in"; LIMITATIONS 48 (a value the job's definition no longer
 * accepts is dropped); ARCHITECTURE 5 ({@code incidents/<id>.xml}). A rerun with no value to
 * recover has nothing missing, so it creates the request directly.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-72/D-72a/D-72b, docs/LIMITATIONS.md and
 * docs/ARCHITECTURE.md section 5 only (no src/main knowledge).
 */
@WithJenkins
public class IncidentRerunGapTest {

    private static final String SECRET = "gap-rerun-s3cr3t-Pz7";

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
    }

    /**
     * T-GAP-163 (L1-16 case 1): the incident of a parameterless job whose failed build was deleted:
     * "Request rerun" creates the request directly (no detour to the form), linked to the incident.
     */
    @Test
    public void t_gap_163_parameterlessRerunOfADeletedBuildCreatesTheRequest() throws Exception {
        FreeStyleProject job = failed("gap-rerun-noparams", null);
        Incident incident = incidentFor("gap-rerun-noparams#1");
        job.getBuildByNumber(1).delete();
        WebResponse rerun = postRerun(j, "u1", incident);
        assertTrue(rerun.getStatusCode() < 400, "the rerun is accepted, got " + rerun.getStatusCode() + ": " + excerpt(rerun.getContentAsString()));
        String location = String.valueOf(rerun.getResponseHeaderValue("Location"));
        assertFalse(location.contains(job.getUrl() + "batch-control"), "no detour to the Request Run form: " + location);
        assertEquals(1, rerunIds(incident.getId()).size(), "one rerun request is created directly");
    }

    /**
     * T-GAP-164 (L1-16 case 2; LIMITATIONS 16 "a core file whose copy is gone"): the incident of a
     * job with a core file parameter, whose build's {@code fileParameters/} copy was deleted: the
     * rerun opens the Request Run form, whose rerun notice names the file parameter; no request is
     * created.
     */
    @Test
    public void t_gap_164_coreFileCopyGoneOpensTheFormNamingIt() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("gap-rerun-corefile"));
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job);
        File upload = uploadFile("input.csv", payload("gap-rerun-corefile", 1024));
        FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(
                new FileParameterValue("UPLOAD", upload, "input.csv"), new StringParameterValue("DATE", "2026-09-29"))));
        j.waitUntilNoActivity();
        job.getBuildersList().clear();
        setBatchControl(job, new BatchControlJobProperty(true));
        Path copies = build.getRootDir().toPath().resolve("fileParameters");
        assertTrue(Files.isDirectory(copies), "fixture: the build keeps the core file's copy under " + copies);
        deleteTree(copies);
        Incident incident = incidentFor("gap-rerun-corefile#1");

        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        HtmlForm form = openForm(UsabilityFixtures.client(j, "u1"), job, target);
        String notice = noticeText((HtmlPage) form.getPage(), "rerun");
        assertTrue(notice.contains("UPLOAD"), "the rerun form names the file parameter to provide again: " + notice);
        assertTrue(rerunIds(incident.getId()).isEmpty(), "no request is created before the form is submitted");
    }

    /**
     * T-GAP-165 (L1-16 case 3; LIMITATIONS 16 and 48): the incident's build was deleted and the job's
     * parameters were removed since: the rerun opens the form with no prefilled value (no
     * {@code p.} key in the redirect) and the form renders.
     */
    @Test
    public void t_gap_165_deletedBuildAndRemovedParametersPrefillNothing() throws Exception {
        FreeStyleProject job = failed("gap-rerun-noparamsnow", "2026-09-29");
        Incident incident = incidentFor("gap-rerun-noparamsnow#1");
        assertEquals("2026-09-29", incident.getParameters().get("DATE"), "premise: the incident recorded DATE");
        job.getBuildByNumber(1).delete();
        job.removeProperty(ParametersDefinitionProperty.class);

        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertTrue(query.keySet().stream().noneMatch(k -> k.startsWith("p.")), "no value is prefilled: " + target);
        openForm(UsabilityFixtures.client(j, "u1"), job, target);
    }

    /**
     * T-GAP-166 (L1-16 case 4; SPEC 11 "secret values are never prefilled", LIMITATIONS 16): a
     * deleted build whose incident recorded a password and a core file value: the rerun's form
     * prefills the string DATE but neither TOKEN nor UPLOAD, and the secret appears nowhere in the
     * redirect or on the form.
     */
    @Test
    public void t_gap_166_deletedBuildNeverPrefillsSecretsOrFiles() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("gap-rerun-secret"));
        job.addProperty(new ParametersDefinitionProperty(new PasswordParameterDefinition("TOKEN", Secret.fromString("tok-default"), "t"),
                new FileParameterDefinition("UPLOAD", "input"), new StringParameterDefinition("DATE", "2000-01-01")));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null, new ParametersAction(
                new PasswordParameterValue("TOKEN", SECRET), new FileParameterValue("UPLOAD", uploadFile("in.csv",
                        payload("gap-rerun-secret", 512)), "in.csv"), new StringParameterValue("DATE", "2026-09-29"))));
        j.waitUntilNoActivity();
        job.getBuildersList().clear();
        setBatchControl(job, new BatchControlJobProperty(true));
        Incident incident = incidentFor("gap-rerun-secret#1");
        job.getBuildByNumber(1).delete();

        URL target = fallbackTarget(j, incident, postRerun(j, "u1", incident));
        Map<String, String> query = TypedParameterFixtures.query(target);
        assertEquals("2026-09-29", query.get("p.DATE"), "the recorded non-sensitive value is prefilled: " + target);
        assertFalse(query.containsKey("p.TOKEN"), "the password is never prefilled: " + target);
        assertFalse(query.containsKey("p.UPLOAD"), "the file is never prefilled: " + target);
        assertFalse(target.toExternalForm().contains(SECRET), "the secret is not in the redirect");
        HtmlForm form = openForm(UsabilityFixtures.client(j, "u1"), job, target);
        assertFalse(form.getPage().getWebResponse().getContentAsString().contains(SECRET), "the secret is not on the form");
    }

    /**
     * T-GAP-167 (L1-16 case 5 (F); ARCHITECTURE 5 {@code incidents/<id>.xml}): the stored run id of
     * an incident is edited on disk to the job name without {@code #}, and on another incident to
     * {@code <job>#x}: the rerun of each opens the Request Run form (302 to the form) instead of
     * failing.
     */
    @Test
    public void t_gap_167_editedRunIdOpensTheFormInsteadOfFailing() throws Exception {
        String[][] cases = {{"gap-rerun-nohash", ""}, {"gap-rerun-badnum", "#x"}};
        for (String[] c : cases) {
            FreeStyleProject job = failed(c[0], "2026-09-29");
            Incident incident = incidentFor(c[0] + "#1");
            Path file = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("incidents").resolve(incident.getId() + ".xml");
            assertTrue(Files.isRegularFile(file), "fixture: the incident is stored at " + file);
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            String original = "<runId>" + c[0] + "#1</runId>";
            assertTrue(xml.contains(original), "fixture: the stored incident names its run id: " + excerpt(xml));
            Files.writeString(file, xml.replace(original, "<runId>" + c[0] + c[1] + "</runId>"), StandardCharsets.UTF_8);

            WebResponse rerun = postRerun(j, "u1", incident);
            assertTrue(rerun.getStatusCode() < 500, c[0] + ": the rerun must not fail, got " + rerun.getStatusCode() + ": "
                    + excerpt(rerun.getContentAsString()));
            URL target = fallbackTarget(j, incident, rerun);
            openForm(UsabilityFixtures.client(j, "u1"), job, target);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** An activated job taken out of run control whose first run fails (DATE when given), then approval-required. */
    private FreeStyleProject failed(String name, String date) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        if (date != null) {
            job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DATE", "2000-01-01")));
        }
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job);
        if (date == null) {
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        } else {
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, (Cause) null,
                    new ParametersAction(new StringParameterValue("DATE", date))));
        }
        j.waitUntilNoActivity();
        job.getBuildersList().clear();
        setBatchControl(job, new BatchControlJobProperty(true));
        return job;
    }

    private static void deleteTree(Path dir) throws Exception {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
        assertFalse(Files.exists(dir), "fixture: " + dir + " is gone");
    }
}
