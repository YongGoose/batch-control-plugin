package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-11: month files with unusual but valid JSON, and malformed lines.
 * Matrix rows T-GAP-145 .. T-GAP-148 (note 276).
 *
 * <p>Basis: SPEC 4 (#13, security-11) "A record the fast reader cannot scan is read with the
 * reference parser before it is ever skipped, so no valid record disappears from a screen" and
 * "screens and exports never silently disagree"; SPEC 12 (history, CSV export); SPEC 3 RunRecord
 * fields ({@code runId, jobFullName, number, causeType, user?, parameters, result, startedAt,
 * durationMs}); ARCHITECTURE 5 ({@code runs/YYYY-MM.jsonl}, {@code changes/YYYY-MM.jsonl}). The
 * line variants are derived from one real line the plugin wrote for a build (test code may read
 * the store), so every valid variant is the same record in a different but valid JSON spelling;
 * the expected value of each varied field is the one the JSON standard gives (RFC 8259).
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and the existing store fixtures
 * only (no src/main knowledge).
 */
@WithJenkins
public class MonthFileGapTest {

    private static final String TEMPLATE_JOB = "gap-tpl-job";
    private static final String TEMPLATE_USER = "gaptpluser";
    private static final String TEMPLATE_VALUE = "gaptplvalue";

    private JenkinsRule j;
    private YearMonth month;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        // the plugin clock stays the real one: the dashboard shows the last 7 days up to the clock's
        // now, and a clock fixed before the builds start would hide them (the month is still known)
        month = YearMonth.now(BatchClock.clock());
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin", TEMPLATE_USER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY).everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-145 (L1-11 (T)): a job runs with the string value {@code a<TAB>b<CR>c–d} (U+2013): the
     * dashboard, the history screen and {@code runs.csv} show exactly that value; a job whose name
     * holds an en dash appears with that name on the change list and in {@code changes.csv}.
     */
    @Test
    public void t_gap_145_controlCharactersAndEnDashSurviveTheRoundTrip() throws Exception {
        String value = "a\tb\rc–d";
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("gap-odd-value"));
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("ODD", "plain")));
        BatchControlFixtures.activateAsAdmin(job);
        j.assertBuildStatusSuccess(job.scheduleBuild2(0, (Cause) null, new ParametersAction(new StringParameterValue("ODD", value))));
        j.waitUntilNoActivity();
        for (String path : new String[] {"batch-control/dashboard/", "batch-control/history/"}) {
            String body = decoded(get(path));
            assertTrue(body.contains(value), path + " must show the value exactly (TAB, CR and the en dash kept): "
                    + visible(around(body, "a\tb")) + " | around the job: " + visible(around(body, "gap-odd-value"))
                    + " | around the value's tail: " + visible(around(body, "c\u2013d")));
        }
        String csv = get("batch-control/history/runs.csv");
        assertTrue(csv.contains(value), "runs.csv must carry the value exactly: " + visible(around(csv, "gap-odd-value")));

        String dashed = "gap–dash";
        j.createFreeStyleProject(dashed);
        for (String path : new String[] {"batch-control/changes/", "batch-control/history/?kind=changes"}) {
            assertTrue(decoded(get(path)).contains(dashed), path + " must list the job named with an en dash");
        }
        assertTrue(get("batch-control/history/changes.csv").contains(dashed), "changes.csv must carry the en dash name");
    }

    /**
     * T-GAP-146 (L1-11 (F) valid variants, runs): copies of a real run line are appended to
     * {@code runs/<month>.jsonl}, each rewritten in a different valid JSON spelling (spaces around
     * every {@code :} and {@code ,} and after {@code {}; numbers as strings of digits; a negative
     * duration; a user written with the escapes backslash-b, backslash-f
     * and backslash-u0041; a parameter
     * map with a null and a numeric value; a string field holding a bare number). Each appears on
     * the history screen and in {@code runs.csv}, with the value the JSON standard gives for the
     * varied field. Guard: the unchanged template record is listed too.
     */
    @Test
    public void t_gap_146_validButUnusualRunLinesAreListed() throws Exception {
        String template = runTemplate();
        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("gap-v-spaces", spaced(rename(template, "gap-v-spaces")));
        variants.put("gap-v-strnum", quoteNumbers(rename(template, "gap-v-strnum")));
        variants.put("gap-v-negdur", replaceMember(rename(template, "gap-v-negdur"), "durationMs", "-5"));
        variants.put("gap-v-escapes", replaceMember(rename(template, "gap-v-escapes"), "user", "\"\\b\\f\\u0041gapesc\""));
        variants.put("gap-v-params", replaceMember(rename(template, "gap-v-params"), "parameters",
                "{\"P1\":null,\"P2\":51234}"));
        variants.put("gap-v-baredigits", replaceMember(rename(template, "gap-v-baredigits"), "user", "98765"));
        append(runsFile(), new ArrayList<>(variants.values()));

        String history = decoded(get("batch-control/history/"));
        String csv = get("batch-control/history/runs.csv");
        assertTrue(history.contains(TEMPLATE_JOB) && csv.contains(TEMPLATE_JOB), "guard: the template record is listed");
        for (String job : variants.keySet()) {
            assertTrue(history.contains(job), "the history screen must list the valid record " + job + ": " + variants.get(job));
            assertTrue(csv.contains(job), "runs.csv must contain the valid record " + job + ": " + variants.get(job));
        }
        assertTrue(rowOf(csv, "gap-v-escapes").contains("Agapesc"), "\\u0041 reads as A in the user: " + rowOf(csv, "gap-v-escapes"));
        assertFalse(rowOf(csv, "gap-v-escapes").contains("\\u0041"), "the escape is decoded, not shown raw");
        assertTrue(rowOf(csv, "gap-v-params").contains("51234"), "the numeric parameter value is shown: " + rowOf(csv, "gap-v-params"));
        assertTrue(rowOf(csv, "gap-v-baredigits").contains("98765"), "the bare number is read as the user: " + rowOf(csv, "gap-v-baredigits"));
        assertTrue(rowOf(csv, "gap-v-negdur").contains("-5"), "the negative duration is kept: " + rowOf(csv, "gap-v-negdur"));
        assertTrue(rowOf(csv, "gap-v-strnum").contains(TEMPLATE_VALUE), "the record with numbers as strings keeps its parameters");
    }

    /**
     * T-GAP-147 (L1-11 (F) malformed, runs): between valid copies, malformed lines are appended to
     * {@code runs/<month>.jsonl} ({@code {}}, a missing comma, an unterminated string, an
     * unterminated object, {@code [1]}, a member without a value, a bad escape (backslash-x), a short
     * unicode escape (backslash, u, two digits), a fractional number, a line without the job name, a parameters member that is
     * not an object, a malformed nested object). The history screen, the dashboard and
     * {@code runs.csv} still answer 200, and the valid lines before and after are on the screen and
     * in the export alike.
     */
    @Test
    public void t_gap_147_malformedRunLinesAreSkippedWithoutLosingValidOnes() throws Exception {
        String template = runTemplate();
        List<String> lines = new ArrayList<>();
        lines.add(rename(template, "gap-ok-before"));
        lines.add("{}");
        lines.add("{\"a\":1 \"b\":2}");
        lines.add("{\"runId\":\"gap-bad#1\",\"jobFullName\":\"gap-bad");
        lines.add(template.substring(0, template.length() - 1).replace(TEMPLATE_JOB, "gap-bad-unterminated"));
        lines.add("[1]");
        lines.add("{\"a\":}");
        lines.add(replaceMember(rename(template, "gap-bad-escape"), "user", "\"bad\\xescape\""));
        lines.add(replaceMember(rename(template, "gap-bad-shortu"), "user", "\"bad\\u12\""));
        lines.add(replaceMember(rename(template, "gap-bad-fraction"), "number", "1.5"));
        lines.add(removeMember(rename(template, "gap-bad-missing"), "jobFullName"));
        lines.add(replaceMember(rename(template, "gap-bad-paramlist"), "parameters", "[\"P1\"]"));
        lines.add(replaceMember(rename(template, "gap-bad-nested"), "parameters", "{\"P1\":\"x\" \"P2\":\"y\"}"));
        lines.add(rename(template, "gap-ok-after"));
        append(runsFile(), lines);

        String history = decoded(get("batch-control/history/"));
        String dashboard = decoded(get("batch-control/dashboard/"));
        String csv = get("batch-control/history/runs.csv");
        for (String ok : new String[] {"gap-ok-before", "gap-ok-after", TEMPLATE_JOB}) {
            assertTrue(history.contains(ok), "the history screen keeps the valid record " + ok);
            assertTrue(dashboard.contains(ok), "the dashboard keeps the valid record " + ok + "; around the template job: "
                    + visible(around(dashboard, TEMPLATE_JOB)) + "; gap-ok-after shown: " + dashboard.contains("gap-ok-after"));
            assertTrue(csv.contains(ok), "runs.csv keeps the valid record " + ok);
        }
        for (String name : new String[] {"gap-bad-unterminated", "gap-bad-paramlist", "gap-bad-nested"}) {
            assertEquals(history.contains(name), csv.contains(name),
                    "the screen and the export must agree about " + name + " (SPEC 4: never silently disagree); shown on the"
                            + " history screen: " + history.contains(name) + ", in runs.csv: " + csv.contains(name)
                            + " (" + rowOf(csv, name) + ")");
        }
    }

    /**
     * T-GAP-148 (L1-11 (F), changes): copies of a real change line (the CREATE record of a job) are
     * appended to {@code changes/<month>.jsonl}: one with spaces around every {@code :} and
     * {@code ,}, one whose user starts with the escape backslash-u0041, and malformed lines between
     * them. The change list and {@code changes.csv} answer 200 and both valid copies appear in
     * each.
     */
    @Test
    public void t_gap_148_changeLinesWithUnusualJsonAreListed() throws Exception {
        j.createFreeStyleProject("gap-chg-tpl");
        Path changes = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("changes")
                .resolve(StoreDataFixtures.monthName(month) + ".jsonl");
        assertTrue(Files.isRegularFile(changes), "fixture: the CREATE record is stored at " + changes);
        String template = Files.readAllLines(changes, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("\"gap-chg-tpl\"")).findFirst().orElse(null);
        assertNotNull(template, "fixture: a change line names gap-chg-tpl: " + Files.readString(changes, StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        lines.add(spaced(template.replace("gap-chg-tpl", "gap-chg-spaces")));
        lines.add("{\"type\":\"CREATE\",\"target\":\"gap-chg-broken");
        lines.add("[\"not\",\"a\",\"record\"]");
        lines.add(template.replace("gap-chg-tpl", "gap-chg-escaped").replaceFirst("\"user\"\\s*:\\s*\"([^\"]*)\"",
                "\"user\":\"\\\\u0041$1\""));
        append(changes, lines);

        String list = decoded(get("batch-control/changes/"));
        String csv = get("batch-control/history/changes.csv");
        for (String name : new String[] {"gap-chg-tpl", "gap-chg-spaces", "gap-chg-escaped"}) {
            assertTrue(list.contains(name), "the change list must show " + name);
            assertTrue(csv.contains(name), "changes.csv must contain " + name);
        }
    }

    // ------------------------------------------------------------------ the template line

    /** Runs a build of {@link #TEMPLATE_JOB} (user cause, one string parameter) and returns its stored run line. */
    private String runTemplate() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(TEMPLATE_JOB));
        job.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("P1", "default")));
        BatchControlFixtures.activateAsAdmin(job);
        try (ACLContext ignored = ACL.as2(token(TEMPLATE_USER))) {
            j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause(),
                    new ParametersAction(new StringParameterValue("P1", TEMPLATE_VALUE))));
        }
        j.waitUntilNoActivity();
        Path file = runsFile();
        assertTrue(Files.isRegularFile(file), "fixture: the run record is stored at " + file);
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> l.contains("\"" + TEMPLATE_JOB + "\"")).findFirst().orElse(null);
        assertNotNull(line, "fixture: a run line names " + TEMPLATE_JOB);
        for (String member : new String[] {"runId", "jobFullName", "number", "user", "parameters", "durationMs"}) {
            assertTrue(line.contains("\"" + member + "\""), "fixture: the stored run line carries " + member
                    + " (SPEC 3 RunRecord): " + line);
        }
        assertTrue(line.contains(TEMPLATE_USER) && line.contains(TEMPLATE_VALUE), "fixture: user and parameter are in the line: " + line);
        return line;
    }

    private Path runsFile() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("runs")
                .resolve(StoreDataFixtures.monthName(month) + ".jsonl");
    }

    private static String rename(String line, String job) {
        return line.replace(TEMPLATE_JOB, job);
    }

    /** Inserts spaces around every {@code :} and {@code ,} and after every {@code {} outside strings. */
    static String spaced(String json) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        for (char c : json.toCharArray()) {
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case ':' -> out.append(" : ");
                case ',' -> out.append(" , ");
                case '{' -> out.append("{ ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Writes every JSON number outside strings as a string of its digits. */
    static String quoteNumbers(String json) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
            } else if (c == '-' || Character.isDigit(c)) {
                int start = i;
                while (i < json.length() && "-+.eE0123456789".indexOf(json.charAt(i)) >= 0) {
                    i++;
                }
                out.append('"').append(json, start, i).append('"');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Replaces the value of the top-level member {@code name} (a string, number, literal or flat object) with raw JSON. */
    static String replaceMember(String json, String name, String rawValue) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*").matcher(json);
        assertTrue(m.find(), "fixture: the line carries " + name + ": " + json);
        int start = m.end();
        int end = valueEnd(json, start);
        return json.substring(0, start) + rawValue + json.substring(end);
    }

    /** Removes the top-level member {@code name} with its comma. */
    static String removeMember(String json, String name) {
        Matcher m = Pattern.compile(",?\\s*\"" + Pattern.quote(name) + "\"\\s*:\\s*").matcher(json);
        assertTrue(m.find(), "fixture: the line carries " + name + ": " + json);
        int end = valueEnd(json, m.end());
        String out = json.substring(0, m.start()) + json.substring(end);
        return out.replace("{,", "{");
    }

    private static int valueEnd(String json, int start) {
        char first = json.charAt(start);
        if (first == '"') {
            int i = start + 1;
            while (json.charAt(i) != '"') {
                i += json.charAt(i) == '\\' ? 2 : 1;
            }
            return i + 1;
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            boolean inString = false;
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            throw new AssertionError("fixture: unterminated value in " + json);
        }
        int i = start;
        while (i < json.length() && ",}".indexOf(json.charAt(i)) < 0) {
            i++;
        }
        return i;
    }

    private static void append(Path file, List<String> lines) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    // ------------------------------------------------------------------ reading the screens

    private String get(String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        wc.login("viewer");
        WebResponse response = wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
        assertEquals(200, response.getStatusCode(), "GET " + path + " must answer 200: " + UsabilityFixtures.excerpt(response.getContentAsString()));
        return response.getContentAsString();
    }

    /** The body with HTML character references decoded (so an escaped TAB or CR compares as the character). */
    private static String decoded(String html) {
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            int code = ref.startsWith("x") ? Integer.parseInt(ref.substring(1), 16) : Integer.parseInt(ref);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String rowOf(String csv, String needle) {
        for (String row : csv.split("\n")) {
            if (row.contains(needle)) {
                return row;
            }
        }
        return "";
    }

    private static String around(String text, String needle) {
        int at = text.indexOf(needle);
        if (at < 0) {
            return "<" + needle + " not found>";
        }
        return text.substring(Math.max(0, at - 80), Math.min(text.length(), at + 160));
    }

    private static String visible(String text) {
        return text.replace("\t", "<TAB>").replace("\r", "<CR>").replace("\n", "<LF>").toLowerCase(Locale.ROOT);
    }
}
