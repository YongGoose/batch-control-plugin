package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FileParameterDefinition;
import hudson.model.FileParameterValue;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TextParameterDefinition;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.Base64FileParameterValue;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.assertAbsent;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileDisplay;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.fileItem;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.payload;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.readable;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.serverPaths;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.storeFilesContaining;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.submitRequest;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.uploadFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5 (D-72: "wherever a request's parameters are shown or written as text (request
 * screens, CSV, history, run records, incidents) ... a file value [appears] only as
 * {@code [file] <original file name>}, never its content, Base64 or a server path"), item 10 (every
 * build is recorded, with its parameters) and item 11 (incidents), for runs that did not come from
 * a run request, plus the CSV shape of item 12. Coverage inventory G-M6, G-L7 and G-L8; matrix rows
 * T-10-12, T-10-13, T-05-110 and T-12-15 (note 269).
 *
 * <p>Users: {@code u1} requester (Item/Read, BatchControl/Request), {@code a1} approver,
 * {@code viewer} (ViewHistory), {@code admin}.
 *
 * <p>Written from docs/SPEC.md items 5, 10, 11 and 12, docs/ARCHITECTURE.md section 5 (secret
 * masking) and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RunRecordFileValueTest {

    private static final String CORE_MARKER = "direct-core-marker-Gm6a";
    private static final String STASH_MARKER = "direct-stash-marker-Gm6b";
    private static final String B64_MARKER = "direct-b64-marker-Gm6c";
    private static final String PLAIN = "direct-plain-value-Gm6";

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
    }

    /**
     * T-10-12 (G-M6): an uncontrolled Freestyle job built directly by a person with a core file
     * UPLOAD ({@code data.csv}) and DATE: its run record is shown on the dashboard and in
     * {@code runs.csv} as {@code [file] data.csv} next to DATE; neither those nor the runs history
     * show the file's content, its Base64 or a server path, and no store file holds the content.
     */
    @Test
    public void t_10_12_directBuildWithACoreFileIsRecordedAsItsNameOnly() throws Exception {
        byte[] content = payload(CORE_MARKER, 4000);
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("direct-core"));
        job.addProperty(new ParametersDefinitionProperty(new FileParameterDefinition("UPLOAD", "input"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        BatchControlFixtures.activateAsAdmin(job);
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause("admin"), new ParametersAction(
                    new FileParameterValue("UPLOAD", uploadFile("data.csv", content), "data.csv"),
                    new StringParameterValue("DATE", PLAIN))));
        }
        j.waitUntilNoActivity();

        List<String> forbidden = forbidden(content, CORE_MARKER);
        String shown = fileDisplay("data.csv");
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(shown), surface + " must show the file value as " + shown + ": " + UsabilityFixtures.excerpt(body));
            assertTrue(body.contains(PLAIN), "guard: " + surface + " shows the plain value");
            assertAbsent(surface, body, forbidden);
        }
        assertAbsent("the runs history", readable(j, "viewer", "batch-control/history/?kind=runs"), forbidden);
        assertEquals(List.of(), storeFilesContaining(j, CORE_MARKER), "no store file may hold the file's content");
        assertFalse(storeFilesContaining(j, shown).isEmpty(), "guard: the run record holds the file value as " + shown);
    }

    /**
     * T-10-13 (G-M6): an uncontrolled Pipeline built directly by a person with a stashed file DATA
     * ({@code report.bin}) and a Base64 file B64 ({@code payload.bin}) fails, so an incident opens.
     * The dashboard and {@code runs.csv} show {@code [file] report.bin} and
     * {@code [file] payload.bin}; the incident's parameters hold the same two forms; neither the
     * runs history, the incident page nor {@code incidents.csv} shows content, Base64 or a server
     * path of a file value (the incident page and CSV keep the console log tail, which names the
     * workspace under JENKINS_HOME, so there the file-value paths checked are the temporary
     * directories and the upload area), and no store file holds either content or the Base64 text.
     */
    @Test
    public void t_10_13_directPipelineBuildWithFilesIsRecordedAsNamesOnlyAndSoIsItsIncident() throws Exception {
        byte[] stashed = payload(STASH_MARKER, 3000);
        byte[] b64 = payload(B64_MARKER, 2000);
        WorkflowJob job = uncontrolled(j.createProject(WorkflowJob.class, "direct-files"));
        job.setDefinition(new CpsFlowDefinition("node {\n  unstash 'DATA'\n}\nerror 'the batch failed'\n", true));
        job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                new Base64FileParameterDefinition("B64"), new StringParameterDefinition("DATE", "2000-01-01")));
        BatchControlFixtures.activateAsAdmin(job);
        Base64FileParameterValue b64Value = new Base64FileParameterValue("B64");
        b64Value.setFile(fileItem("payload.bin", b64));
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0, new ParametersAction(
                    new StashedFileParameterValue("DATA", fileItem("report.bin", stashed)), b64Value,
                    new StringParameterValue("DATE", PLAIN)), new hudson.model.CauseAction(new Cause.UserIdCause("admin"))));
        }
        j.waitUntilNoActivity();

        List<String> forbidden = new ArrayList<>(forbidden(stashed, STASH_MARKER));
        forbidden.addAll(forbidden(b64, B64_MARKER));
        for (String surface : new String[] {"batch-control/dashboard/", "batch-control/history/runs.csv"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(fileDisplay("report.bin")) && body.contains(fileDisplay("payload.bin")),
                    surface + " must show both file values as [file] <name>: " + UsabilityFixtures.excerpt(body));
            assertAbsent(surface, body, forbidden);
        }
        assertAbsent("the runs history", readable(j, "viewer", "batch-control/history/?kind=runs"), forbidden);

        Incident incident = RerunFallbackFixtures.incidentFor("direct-files#1");
        Map<String, String> parameters = incident.getParameters();
        assertNotNull(parameters, "the incident must keep the run's parameters");
        assertEquals(fileDisplay("report.bin"), parameters.get("DATA"), "the incident shows the stashed file as [file] <name>");
        assertEquals(fileDisplay("payload.bin"), parameters.get("B64"), "the incident shows the Base64 file as [file] <name>");
        assertEquals(PLAIN, parameters.get("DATE"), "guard: the incident keeps the plain value");
        // The incident keeps the console log tail (SPEC item 11), which names the build's workspace under JENKINS_HOME;
        // there the file-value paths are the temporary directories and the upload area, not JENKINS_HOME as such.
        List<String> logSurfaces = new ArrayList<>(forbidden);
        String home = j.jenkins.getRootDir().getAbsolutePath();
        logSurfaces.removeIf(p -> p.equals(home) || p.equals(tmpDir()));
        assertTrue(logSurfaces.contains(TypedParameterFixtures.STASH_TMP_DIR) && logSurfaces.contains(B64_MARKER),
                "fixture: the log-surface list still holds the file-value paths and contents");
        String incidentPage = readable(j, "viewer", "batch-control/incidents/" + incident.getId() + "/");
        assertTrue(incidentPage.contains("the batch failed"), "guard: the incident page shows the log tail");
        assertAbsent("the incident page", incidentPage, logSurfaces);
        assertAbsent("incidents.csv", readable(j, "viewer", "batch-control/history/incidents.csv"), logSurfaces);
        for (String marker : new String[] {STASH_MARKER, B64_MARKER, Base64.getEncoder().encodeToString(b64).substring(0, 32)}) {
            assertEquals(List.of(), storeFilesContaining(j, marker), "no store file may hold file content or Base64: " + marker);
        }
    }

    /**
     * T-05-110 (G-L8): an approved request with a stashed file DATA ({@code report.bin}) submitted
     * on the Request Run page runs; {@code requests.csv}, {@code runs.csv} and the dashboard show
     * {@code [file] report.bin}, and neither those nor the requests and runs history show the
     * content, its Base64 or a server path.
     */
    @Test
    public void t_05_110_approvedStashedFileRunIsShownAsItsNameOnEverySurface() throws Exception {
        byte[] content = payload(STASH_MARKER, 2500);
        WorkflowJob job = j.createProject(WorkflowJob.class, "approved-stash");
        job.setDefinition(new CpsFlowDefinition("node {\n  unstash 'DATA'\n}\n", true));
        job.addProperty(new ParametersDefinitionProperty(new StashedFileParameterDefinition("DATA"),
                new StringParameterDefinition("DATE", "2000-01-01")));
        setBatchControl(job, new BatchControlJobProperty(true));
        String id = submitRequest(j, "u1", job, Map.of("DATE", PLAIN), Map.of("DATA", uploadFile("report.bin", content)));
        DialogTypedParameterTest.approve(id);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(job.getBuildByNumber(1));

        List<String> forbidden = forbidden(content, STASH_MARKER);
        String shown = fileDisplay("report.bin");
        for (String surface : new String[] {"batch-control/history/requests.csv", "batch-control/history/runs.csv",
                "batch-control/dashboard/"}) {
            String body = readable(j, "viewer", surface);
            assertTrue(body.contains(shown), surface + " must show the stashed file as " + shown);
            assertAbsent(surface, body, forbidden);
        }
        for (String kind : new String[] {"requests", "runs"}) {
            assertAbsent("the " + kind + " history", readable(j, "viewer", "batch-control/history/?kind=" + kind), forbidden);
        }
    }

    /**
     * T-12-15 (G-L7): an approved request whose text parameter NOTE spans two lines ("line one",
     * "line two") runs; {@code requests.csv} and {@code runs.csv} still parse as RFC 4180 with every
     * record as wide as the header, the two lines stay in one record of the job, and the runs
     * history shows both lines.
     */
    @Test
    public void t_12_15_multiLineTextValueKeepsTheCsvExportsWellFormed() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("multi-line");
        job.addProperty(new ParametersDefinitionProperty(new TextParameterDefinition("NOTE", "", "a note"),
                new StringParameterDefinition("P", "p-default")));
        setBatchControl(job, new BatchControlJobProperty(true));
        String id = submitRequest(j, "u1", job, Map.of("NOTE", "line one\nline two", "P", PLAIN), Map.of());
        DialogTypedParameterTest.approve(id);
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(job.getBuildByNumber(1));

        for (String csv : new String[] {"batch-control/history/requests.csv", "batch-control/history/runs.csv"}) {
            List<List<String>> records = parseCsv(readable(j, "viewer", csv));
            assertFalse(records.isEmpty(), csv + " must have a header");
            int width = records.get(0).size();
            for (List<String> record : records) {
                assertEquals(width, record.size(), csv + ": every record must be as wide as the header (RFC 4180), got " + record);
            }
            List<List<String>> mine = records.stream().filter(r -> String.join("\u0000", r).contains(PLAIN)).toList();
            assertEquals(1, mine.size(), csv + " must hold exactly one record of the request's run: " + mine);
            String joined = String.join("\u0000", mine.get(0));
            assertTrue(joined.contains("line one") && joined.contains("line two"),
                    csv + ": both lines of the text value must stay in the job's record: " + mine.get(0));
        }
        String history = readable(j, "viewer", "batch-control/history/?kind=runs");
        assertTrue(history.contains("line one") && history.contains("line two"), "the runs history must show both lines");
    }

    // ---------------------------------------------------------------- helpers

    /** {@code java.io.tmpdir} without a trailing separator, as TypedParameterFixtures#serverPaths lists it. */
    private static String tmpDir() {
        String tmp = System.getProperty("java.io.tmpdir", "");
        return tmp.endsWith(java.io.File.separator) ? tmp.substring(0, tmp.length() - 1) : tmp;
    }

    private List<String> forbidden(byte[] content, String marker) {
        List<String> out = new ArrayList<>(serverPaths(j));
        out.add(marker);
        out.add(Base64.getEncoder().encodeToString(content).substring(0, 32));
        return out;
    }

    /** A minimal RFC 4180 reader: records of cells; quoted cells may hold commas, quotes ("") and line breaks. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                any = true;
            } else if (c == ',') {
                record.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                if (any || cell.length() > 0 || !record.isEmpty()) {
                    record.add(cell.toString());
                    records.add(record);
                }
                record = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        assertFalse(quoted, "the CSV must not end inside a quoted cell");
        if (any || cell.length() > 0 || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }
}
