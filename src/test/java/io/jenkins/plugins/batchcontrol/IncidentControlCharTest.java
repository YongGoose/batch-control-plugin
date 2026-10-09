package io.jenkins.plugins.batchcontrol;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug hunt A R1-01 (TEST-MATRIX note 300), the incident half: no store entity is ever written that cannot
 * be read back. A failed build whose log tail holds ANSI colour codes ({@code ESC[31m}) gets an incident
 * that is listed, opens, and is in {@code incidents.csv} and the monthly summary, with the escape sequences
 * and every XML-illegal character removed and the printable text kept; an acknowledge, resolve or comment
 * text holding an XML-illegal character (U+000B) is refused with a 4xx and a plain message, nothing is
 * stored, and the incident stays readable. Matrix rows T-11-30 and T-11-31; the other free-text fields
 * and the store's last line of defence are {@link StoreControlCharTest}.
 *
 * <p>Basis: SPEC 11 (FAILURE opens an incident; the last 100 console lines are kept as its log tail;
 * acknowledge/resolve/comment are POSTs needing ViewHistory), SPEC 12 (incidents CSV and monthly summary),
 * SPEC 4 (the store; a record that cannot be read never silently disappears), SPEC 5 (stored values contain
 * only characters XML can store), ARCHITECTURE 5 ({@code incidents/<id>.xml}, XStream).
 *
 * <p>Written from docs/SPEC.md items 4, 5, 11 and 12 and docs/ARCHITECTURE.md section 5 only (no src/main
 * knowledge).
 */
@WithJenkins
public class IncidentControlCharTest {

    /** The console line of the defect: red "ERROR", reset, " failed". */
    static final String ANSI_LINE = "\u001B[31mERROR\u001B[0m failed";
    /** A second console line with a bell and a vertical tab inside printable text. */
    static final String CONTROL_LINE = "bell\u0007 and\u000Bvt";
    static final String VT = "\u000B";
    private static final Pattern WHY = Pattern.compile("(?i)character|control|not allowed|invalid|unsupported");

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true); // the plugin records nothing while both switches are off (SPEC 1)
        cfg.setApprovers(List.of("admin"));
        cfg.save();
    }

    /**
     * T-11-30 (P0): a Freestyle build prints {@code ESC[31mERROR ESC[0m failed} and a line with a bell and a
     * vertical tab, then fails. Its incident is listed on {@code batch-control/incidents/}, its page answers
     * 200 and shows "ERROR failed"; {@code incidents.csv} answers 200 and names the job; the monthly summary
     * answers 200. The stored log tail keeps "ERROR failed", "bell" and "vt", holds no {@code [31m} remnant
     * and no character XML 1.0 cannot store. Guard: the incident file is found on disk first, so a listing
     * that misses it is the defect and not a missing incident.
     */
    @Test
    public void t_11_30_ansiLogTailIncidentIsListedAndReadable() throws Exception {
        FreeStyleProject job = failing("r101-color", ANSI_LINE, CONTROL_LINE);
        j.buildAndAssertStatus(Result.FAILURE, job);
        j.waitUntilNoActivity();
        String id = incidentFileId("r101-color");

        WebResponse list = ApproverFormFixtures.get(j, "viewer", "batch-control/incidents/");
        assertEquals(200, list.getStatusCode(), "the incident list opens");
        assertTrue(list.getContentAsString().contains(id), "R1-01: the incident " + id + " of the ANSI-coloured failure must be"
                + " listed on batch-control/incidents/: " + excerpt(list.getContentAsString()));
        WebResponse detail = ApproverFormFixtures.get(j, "viewer", "batch-control/incidents/" + id + "/");
        assertEquals(200, detail.getStatusCode(), "R1-01: the incident page must open: " + excerpt(detail.getContentAsString()));
        assertTrue(detail.getContentAsString().contains("ERROR failed"), "R1-01: the page shows the printable log text");
        WebResponse csv = ApproverFormFixtures.get(j, "viewer", "batch-control/history/incidents.csv");
        assertEquals(200, csv.getStatusCode(), "R1-01: incidents.csv must answer 200: " + excerpt(csv.getContentAsString()));
        assertTrue(csv.getContentAsString().contains("r101-color"), "R1-01: incidents.csv must name the job: " + excerpt(csv.getContentAsString()));
        String month = YearMonth.now(BatchClock.clock()).toString();
        WebResponse summary = ApproverFormFixtures.get(j, "viewer", "batch-control/history/summary?month=" + month);
        assertEquals(200, summary.getStatusCode(), "R1-01: the monthly summary must answer 200: " + excerpt(summary.getContentAsString()));

        Incident incident = IncidentService.get().load(id);
        assertNotNull(incident, "R1-01: the incident must load");
        String tail = String.join("\n", incident.getLogTail());
        assertTrue(tail.contains("ERROR failed"), "R1-01: the printable text of the coloured line is kept: " + printable(tail));
        assertTrue(tail.contains("bell") && tail.contains("vt"), "R1-01: the printable text around the control characters is kept: " + printable(tail));
        assertFalse(tail.contains("[31m") || tail.contains("[0m"), "R1-01: the whole escape sequence is removed, not only ESC: " + printable(tail));
        assertXmlStorable(tail, "the stored log tail");
    }

    /**
     * T-11-31 (P0): an OPEN incident of a plain failure. The viewer's acknowledge with a comment holding
     * U+000B is refused with a 4xx and a plain message; status and transitions are unchanged and the incident
     * still opens and is listed. Guard: the same acknowledge with a clean comment is accepted. Then resolve
     * and comment with U+000B are refused the same way on the ACKNOWLEDGED incident.
     */
    @Test
    public void t_11_31_controlCharacterInIncidentTextIsRefusedAndNothingStored() throws Exception {
        FreeStyleProject job = failing("r101-plain", "plain failure");
        j.buildAndAssertStatus(Result.FAILURE, job);
        j.waitUntilNoActivity();
        String id = incidentFileId("r101-plain");
        assertNotNull(IncidentService.get().load(id), "premise: the plain incident loads");

        assertRefused(id, "acknowledge", "a" + VT + "b acknowledged", IncidentStatus.OPEN);
        WebResponse ok = post(id, "acknowledge", "acknowledged by the viewer");
        assertTrue(ok.getStatusCode() < 400, "guard: a clean acknowledge comment is accepted, got HTTP " + ok.getStatusCode());
        assertEquals(IncidentStatus.ACKNOWLEDGED, IncidentService.get().load(id).getStatus(), "guard: the incident is ACKNOWLEDGED");
        assertRefused(id, "resolve", "fixed" + VT + "now", IncidentStatus.ACKNOWLEDGED);
        assertRefused(id, "comment", "note" + VT + "here", IncidentStatus.ACKNOWLEDGED);
    }

    // ---------------------------------------------------------------- helpers

    private void assertRefused(String id, String action, String comment, IncidentStatus status) throws Exception {
        Path file = incidentsDir().resolve(id + ".xml");
        String before = Files.readString(file, StandardCharsets.UTF_8);
        WebResponse response = post(id, action, comment);
        int code = response.getStatusCode();
        assertTrue(code >= 400 && code < 500, "R1-01: a " + action + " text holding U+000B must be refused with a 4xx, got HTTP " + code
                + ": " + excerpt(response.getContentAsString()));
        UsabilityFixtures.assertPlainRefusal("the refused " + action, response.getContentAsString(), WHY);
        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8), "R1-01: the refused " + action + " stores nothing");
        Incident reloaded = IncidentService.get().load(id);
        assertNotNull(reloaded, "R1-01: the incident stays readable after the refused " + action);
        assertEquals(status, reloaded.getStatus(), "R1-01: the refused " + action + " changes no status");
        assertEquals(200, ApproverFormFixtures.get(j, "viewer", "batch-control/incidents/" + id + "/").getStatusCode(),
                "R1-01: the incident page still opens after the refused " + action);
        assertTrue(ApproverFormFixtures.get(j, "viewer", "batch-control/incidents/").getContentAsString().contains(id),
                "R1-01: the incident is still listed after the refused " + action);
    }

    private WebResponse post(String id, String action, String comment) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "viewer");
        WebRequest request = new WebRequest(wc.createCrumbedUrl("batch-control/incidents/" + id + "/" + action), HttpMethod.POST);
        request.setCharset(StandardCharsets.UTF_8);
        List<NameValuePair> params = new ArrayList<>();
        params.add(new NameValuePair("comment", comment));
        request.setRequestParameters(params);
        return wc.getPage(request).getWebResponse();
    }

    /** An uncontrolled, activated Freestyle job whose build prints {@code lines} and fails. */
    private FreeStyleProject failing(String name, String... lines) throws Exception {
        FreeStyleProject job = BatchControlFixtures.uncontrolled(j.createFreeStyleProject(name));
        job.getBuildersList().add(new PrintAndFail(lines));
        BatchControlFixtures.activateAsAdmin(job);
        return job;
    }

    private Path incidentsDir() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("incidents");
    }

    /**
     * The id of the one incident file naming {@code job} (ARCHITECTURE 5: {@code incidents/<id>.xml}), found
     * on disk so that an incident the store cannot read back is still found.
     */
    private String incidentFileId(String job) throws Exception {
        List<String> ids = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (ids.isEmpty() && System.currentTimeMillis() < deadline) {
            if (Files.isDirectory(incidentsDir())) {
                try (Stream<Path> files = Files.list(incidentsDir())) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".xml")).collect(Collectors.toList())) {
                        if (Files.readString(f, StandardCharsets.UTF_8).contains("<jobFullName>" + job + "</jobFullName>")) {
                            String n = f.getFileName().toString();
                            ids.add(n.substring(0, n.length() - ".xml".length()));
                        }
                    }
                }
            }
            if (ids.isEmpty()) {
                Thread.sleep(50); // bounded wait for the finalization of the failed run, not for an expiry
            }
        }
        assertEquals(1, ids.size(), "premise (SPEC 11): the failed run of " + job + " opened exactly one incident file: " + ids);
        return ids.get(0);
    }

    /** Every character is allowed by XML 1.0: tab, LF, CR, U+0020..U+D7FF, U+E000..U+FFFD, paired surrogates. */
    static void assertXmlStorable(String text, String what) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok;
            if (Character.isHighSurrogate(c)) {
                ok = i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
                i++;
            } else {
                ok = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD);
            }
            assertTrue(ok, "R1-01: " + what + " holds a character XML 1.0 cannot store (U+" + String.format("%04X", (int) c)
                    + " at " + i + "): " + printable(text));
        }
    }

    static String printable(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 0x20 && c != '\n') {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Prints the given lines to the build log and fails the build. */
    public static final class PrintAndFail extends TestBuilder {
        private final String[] lines;

        PrintAndFail(String... lines) {
            this.lines = lines.clone();
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            for (String line : lines) {
                listener.getLogger().println(line);
            }
            build.setResult(Result.FAILURE);
            return true;
        }
    }
}
