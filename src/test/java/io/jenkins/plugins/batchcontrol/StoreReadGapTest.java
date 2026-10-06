package io.jenkins.plugins.batchcontrol;

import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.failedFreestyle;
import static io.jenkins.plugins.batchcontrol.RerunFallbackFixtures.incidentFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-09 and L3-10: incident index lines the fast reader cannot scan, and month
 * files read by the CSV exports, the monthly summary and the dashboard. Matrix rows T-GAP-333 .. T-GAP-338
 * (note 279).
 *
 * <p>Basis: SPEC 4 line 76 ("A record the fast reader cannot scan is read with the reference parser before
 * it is ever skipped, so no valid record disappears from a screen"; "A line longer than 1 MiB is skipped on
 * every read path, including CSV exports"; "screens and exports never silently disagree"; "the CSV export,
 * which is complete"); SPEC 12 (CSV exports, "월별 집계에 실행 수 ..."); SPEC 10 "페이지당 50건" and D-67 "The run
 * dashboard shows at most the 50 most recent runs"; ARCHITECTURE 5 ({@code incidents/index/YYYY-MM.jsonl},
 * {@code runs/YYYY-MM.jsonl}, {@code changes/YYYY-MM.jsonl}, JSONL one record per line). Valid variants are
 * the same record in another spelling that a standard JSON reader accepts (RFC 8259); lines are derived
 * from what the store itself wrote (test code may read the store).
 *
 * <p>Run control on; viewer holds Overall/Read, Item/Read and ViewHistory. The plugin clock stays real
 * (the dashboard shows the last seven days up to its now, note 276 (d)).
 *
 * <p>Written from docs/SPEC.md items 4, 10 and 12, docs/DECISIONS.md D-67 and docs/ARCHITECTURE.md section
 * 5 only (no src/main knowledge).
 */
@WithJenkins
public class StoreReadGapTest {

    private JenkinsRule j;
    private YearMonth month;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        month = YearMonth.now(BatchClock.clock());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY, BatchControlPermissions.REQUEST).everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    // ------------------------------------------------------------------ L3-09

    /**
     * T-GAP-333 (L3-09; SPEC 4 line 76): two failed builds open the incidents of {@code l3-ia#1} and
     * {@code l3-ib#1}. The index line of {@code l3-ia}'s incident is replaced, one at a time, by three
     * spellings a standard JSON reader accepts: whitespace around every {@code :} and {@code ,}; every string
     * value written with {@code \}{@code u00XX} escapes; the creation time written as a string of digits. For
     * each, the Incidents screen and {@code incidents.csv} list that incident exactly once, and still list
     * {@code l3-ib}'s.
     */
    @Test
    public void t_gap_333_unusualButValidIndexLinesListTheIncidentOnce() throws Exception {
        failedFreestyle(j, "l3-ia");
        failedFreestyle(j, "l3-ib");
        Incident a = incidentFor("l3-ia#1");
        Path index = indexFile();
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        String original = lines.stream().filter(l -> l.contains(a.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("premise: the index names the incident " + a.getId()));
        Matcher time = Pattern.compile("\"createdAt\"\\s*:\\s*(\\d+)").matcher(original);
        assertTrue(time.find(), "premise (ARCHITECTURE 5 index fields id, runId, jobFullName, result, createdAt): the line carries its"
                + " time as a number: " + original);
        String[][] variants = {
            {"spaces around : and ,", MonthFileGapTest.spaced(original)},
            {"\\u00XX escapes in every string", escapeStrings(original)},
            {"the time as a string of digits", original.replace(time.group(0), "\"createdAt\":\"" + time.group(1) + "\"")}
        };
        for (String[] variant : variants) {
            assertFalse(variant[1].equals(original), "fixture: the variant '" + variant[0] + "' differs from the stored line");
            List<String> rewritten = new ArrayList<>();
            for (String l : lines) {
                rewritten.add(l.equals(original) ? variant[1] : l);
            }
            Files.write(index, rewritten, StandardCharsets.UTF_8);
            HtmlPage screen = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/incidents/");
            assertEquals(1, rowsNaming(screen, "l3-ia"), variant[0] + ": the Incidents screen lists l3-ia's incident exactly once: "
                    + excerpt(WindowStateFixtures.mainPanel(screen).asNormalizedText()));
            assertEquals(1, rowsNaming(screen, "l3-ib"), variant[0] + ": guard: l3-ib's incident is listed once");
            String csv = get("batch-control/history/incidents.csv");
            assertEquals(1, csvRowsNaming(csv, a.getId()), variant[0] + ": incidents.csv lists l3-ia's incident exactly once: " + excerpt(csv));
        }
    }

    /**
     * T-GAP-334 (L3-09; SPEC 4 line 76): two incidents ({@code l3-ic#1}, {@code l3-id#1}); two lines are
     * appended to the index: a copy of {@code l3-ic}'s line without its {@code id}, and the same copy with
     * spaces around every {@code :} and {@code ,}. The Incidents screen renders, lists both real incidents
     * once each and no further row; {@code incidents.csv} lists each real incident once.
     */
    @Test
    public void t_gap_334_indexLinesWithoutAnIdAddNoRow() throws Exception {
        failedFreestyle(j, "l3-ic");
        failedFreestyle(j, "l3-id");
        Incident c = incidentFor("l3-ic#1");
        Incident d = incidentFor("l3-id#1");
        Path index = indexFile();
        String line = Files.readAllLines(index, StandardCharsets.UTF_8).stream().filter(l -> l.contains(c.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("premise: the index names the incident " + c.getId()));
        String withoutId = MonthFileGapTest.removeMember(line, "id");
        assertFalse(withoutId.contains(c.getId()), "fixture: the copy carries no id: " + withoutId);
        Files.writeString(index, withoutId + "\n" + MonthFileGapTest.spaced(withoutId) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        HtmlPage screen = UsabilityFixtures.htmlPage(j, "viewer", "batch-control/incidents/");
        assertEquals(200, screen.getWebResponse().getStatusCode(), "the Incidents screen renders");
        assertEquals(1, rowsNaming(screen, "l3-ic"), "l3-ic's incident is listed once, the id-less copies add no row: "
                + excerpt(WindowStateFixtures.mainPanel(screen).asNormalizedText()));
        assertEquals(1, rowsNaming(screen, "l3-id"), "l3-id's incident is listed once");
        String csv = get("batch-control/history/incidents.csv");
        assertEquals(1, csvRowsNaming(csv, c.getId()), "incidents.csv lists l3-ic's incident once");
        assertEquals(1, csvRowsNaming(csv, d.getId()), "incidents.csv lists l3-id's incident once");
    }

    // ------------------------------------------------------------------ L3-10

    /**
     * T-GAP-335 (L3-10; SPEC 4 line 76 "the CSV export, which is complete", ARCHITECTURE 5 JSONL):
     * {@code runs/<month>.jsonl} gets three records with an empty line between the first two and no line end
     * after the last; {@code runs.csv} contains all three. The same for {@code changes/<month>.jsonl} (three
     * copies of a stored CREATE line under fresh ids and targets) and {@code changes.csv}.
     */
    @Test
    public void t_gap_335_emptyLineAndMissingLineEndLoseNoRecord() throws Exception {
        StoreDataFixtures.RunLine run = StoreDataFixtures.runLineTemplate();
        Instant now = Instant.now();
        Path runs = StoreDataFixtures.runsFile(month);
        Files.createDirectories(runs.getParent());
        String runText = run.render("l3-r1#1", "l3-r1", 1, now.minusSeconds(300)) + "\n\n"
                + run.render("l3-r2#1", "l3-r2", 1, now.minusSeconds(200)) + "\n"
                + run.render("l3-r3#1", "l3-r3", 1, now.minusSeconds(100));
        Files.writeString(runs, runText, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String runsCsv = get("batch-control/history/runs.csv");
        for (String job : new String[] {"l3-r1", "l3-r2", "l3-r3"}) {
            assertTrue(runsCsv.contains(job), "runs.csv contains " + job + ": " + excerpt(runsCsv));
        }

        j.createFreeStyleProject("l3-chg-tpl");
        Path changes = j.jenkins.getRootDir().toPath().resolve("batch-control/changes/" + StoreDataFixtures.monthName(month) + ".jsonl");
        String template = Files.readAllLines(changes, StandardCharsets.UTF_8).stream().filter(l -> l.contains("\"l3-chg-tpl\""))
                .findFirst().orElseThrow(() -> new AssertionError("premise: a stored change line names l3-chg-tpl"));
        Matcher id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(template);
        assertTrue(id.find(), "premise: a change line carries its id: " + template);
        StringBuilder changeText = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            changeText.append(template.replace(id.group(1), java.util.UUID.randomUUID().toString()).replace("l3-chg-tpl", "l3-c" + i));
            changeText.append(i == 1 ? "\n\n" : i == 2 ? "\n" : "");
        }
        Files.writeString(changes, changeText.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        String changesCsv = get("batch-control/history/changes.csv");
        for (String target : new String[] {"l3-c1", "l3-c2", "l3-c3", "l3-chg-tpl"}) {
            assertTrue(changesCsv.contains(target), "changes.csv contains " + target + ": " + excerpt(changesCsv));
        }
    }

    /**
     * T-GAP-336 (L3-10; SPEC 12 monthly summary, SPEC 4 line 76): the monthly summary of the current month
     * is read; then two valid run records, an empty line, a malformed line and one more valid run record are
     * appended to {@code runs/<month>.jsonl}: the summary's run count grows by exactly three.
     */
    @Test
    public void t_gap_336_monthlySummaryCountsOnlyValidRunLines() throws Exception {
        StoreDataFixtures.RunLine run = StoreDataFixtures.runLineTemplate();
        int before = summaryRuns();
        Instant now = Instant.now();
        Path runs = StoreDataFixtures.runsFile(month);
        Files.createDirectories(runs.getParent());
        String text = run.render("l3-s1#1", "l3-s1", 1, now.minusSeconds(30)) + "\n"
                + run.render("l3-s2#1", "l3-s2", 1, now.minusSeconds(20)) + "\n"
                + "\n"
                + "{\"runId\":\"l3-bad#1\",\"jobFullName\":\"l3-bad\n"
                + run.render("l3-s3#1", "l3-s3", 1, now.minusSeconds(10)) + "\n";
        Files.writeString(runs, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        assertEquals(before + 3, summaryRuns(), "SPEC 12: the monthly summary counts exactly the three valid runs appended");
    }

    /**
     * T-GAP-337 (L3-10; SPEC 4 line 76 "A line longer than 1 MiB is skipped on every read path, including CSV
     * exports"): two valid run records, then a last run record longer than 1 MiB without a line end;
     * {@code runs.csv} leaves the long record out and contains the two others.
     */
    @Test
    public void t_gap_337_overlongLastLineWithoutLineEndIsLeftOutOfTheCsv() throws Exception {
        StoreDataFixtures.RunLine run = StoreDataFixtures.runLineTemplate();
        Instant now = Instant.now();
        Path runs = StoreDataFixtures.runsFile(month);
        Files.createDirectories(runs.getParent());
        String huge = "l3-huge-" + "p".repeat(1_100_000);
        String text = run.render("l3-k1#1", "l3-k1", 1, now.minusSeconds(30)) + "\n"
                + run.render("l3-k2#1", "l3-k2", 1, now.minusSeconds(20)) + "\n"
                + run.render(huge + "#1", huge, 1, now.minusSeconds(10));
        Files.writeString(runs, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String csv = get("batch-control/history/runs.csv");
        assertFalse(csv.contains("l3-huge-"), "SPEC 4 line 76: runs.csv leaves out the record longer than 1 MiB");
        assertTrue(csv.contains("l3-k1") && csv.contains("l3-k2"), "runs.csv contains the other records: " + excerpt(csv));
    }

    /**
     * T-GAP-338 (L3-10; D-67 "The run dashboard shows at most the 50 most recent runs", SPEC 10): 50 run
     * records with start times T+1 .. T+50 minutes (T two hours ago), then one more record appended last in
     * the file with start time T. The dashboard shows the 50 runs with the newest start times and not the one
     * started at T.
     */
    @Test
    public void t_gap_338_dashboardShowsTheFiftyNewestRunsWhateverTheFileOrder() throws Exception {
        StoreDataFixtures.RunLine run = StoreDataFixtures.runLineTemplate();
        Instant t = Instant.now().minus(Duration.ofHours(2));
        Path runs = StoreDataFixtures.runsFile(month);
        Files.createDirectories(runs.getParent());
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            String job = String.format(Locale.ROOT, "l3d-%02d", i);
            text.append(run.render(job + "#1", job, 1, t.plus(Duration.ofMinutes(i)))).append('\n');
        }
        text.append(run.render("l3d-oldest#1", "l3d-oldest", 1, t)).append('\n');
        Files.writeString(runs, text.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String dashboard = get("batch-control/dashboard/");
        for (int i = 1; i <= 50; i++) {
            String job = String.format(Locale.ROOT, "l3d-%02d", i);
            assertTrue(dashboard.contains(job), "D-67: the dashboard shows " + job + ", one of the 50 newest runs");
        }
        assertFalse(dashboard.contains("l3d-oldest"), "D-67: the run started at T, the 51st newest, is not shown");
    }

    // ------------------------------------------------------------------ helpers

    private Path indexFile() {
        Path index = j.jenkins.getRootDir().toPath().resolve("batch-control/incidents/index/" + StoreDataFixtures.monthName(month) + ".jsonl");
        assertTrue(Files.isRegularFile(index), "premise (ARCHITECTURE 5): the incident index is " + index);
        return index;
    }

    /** Every string value of a flat JSON line written as backslash-u escapes, one per character. */
    private static String escapeStrings(String json) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean key = true;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (!inString) {
                out.append(ch);
                if (ch == '"') {
                    inString = true;
                } else if (ch == ':') {
                    key = false;
                } else if (ch == ',' || ch == '{') {
                    key = true;
                }
                continue;
            }
            if (ch == '\\') {
                out.append(ch).append(json.charAt(++i));
            } else if (ch == '"') {
                inString = false;
                out.append(ch);
            } else if (key) {
                out.append(ch);
            } else {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            }
        }
        return out.toString();
    }

    private static int rowsNaming(HtmlPage page, String job) {
        int count = 0;
        for (DomNode row : WindowStateFixtures.mainPanel(page).querySelectorAll("tbody tr")) {
            if (row.asNormalizedText().contains(job + "#") || row.asNormalizedText().contains(job + " ")) {
                count++;
            }
        }
        return count;
    }

    private static int csvRowsNaming(String csv, String needle) {
        int count = 0;
        for (String row : csv.split("\n")) {
            if (row.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private int summaryRuns() throws Exception {
        JSONObject summary = JSONObject.fromObject(get("batch-control/history/summary?month=" + StoreDataFixtures.monthName(month)));
        assertTrue(summary.has("runs"), "premise: the summary carries a run count: " + summary);
        return summary.getInt("runs");
    }

    private String get(String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "viewer");
        wc.getOptions().setJavaScriptEnabled(false);
        WebResponse response = wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
        assertEquals(200, response.getStatusCode(), "GET " + path + " answers 200: " + excerpt(response.getContentAsString()));
        return response.getContentAsString();
    }
}
