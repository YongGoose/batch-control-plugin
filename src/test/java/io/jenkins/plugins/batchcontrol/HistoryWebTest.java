package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.CauseType;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.IncidentStatus;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.UnstableBuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 12 (history query, monthly aggregation, CSV export, retention) plus the
 * D-18 CSV sanitization criterion and the remaining T-SEC-06 sub-cases.
 * Matrix rows T-12-01 .. T-12-05, T-RT-11 and T-SEC-06 (incident-transition GET,
 * switch-toggle GET).
 *
 * Endpoint contract (fixed by the orchestrator): GET batch-control/history/ with
 * from/to/job/user/result/status query filters, GET batch-control/history/summary?month=,
 * GET batch-control/history/{runs,incidents,changes,requests}.csv, all gated by
 * ViewHistory. Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class HistoryWebTest {

    private static final String[] CSV_PATHS = {
            "batch-control/history/runs.csv",
            "batch-control/history/incidents.csv",
            "batch-control/history/changes.csv",
            "batch-control/history/requests.csv"};

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private BatchControlGlobalConfiguration cfg;

    @Before
    public void setUp() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer")
                .grant(Jenkins.READ, Item.READ).everywhere().to("nohist")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @After
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-12-01: without ViewHistory every query screen answers 403; with it, 200. */
    @Test
    public void t_12_01_historyWithoutViewHistoryIs403() throws Exception {
        JenkinsRule.WebClient noHistory = webClient("nohist");
        assertEquals("GET /batch-control/history without ViewHistory must be 403",
                403, get(noHistory, "batch-control/history/").getStatusCode());
        // SPEC item 12: EVERY query screen is gated, the incident screens included
        assertEquals("GET /batch-control/incidents without ViewHistory must be 403",
                403, get(noHistory, "batch-control/incidents/").getStatusCode());

        JenkinsRule.WebClient viewer = webClient("viewer");
        assertEquals("with ViewHistory the history screen must render",
                200, get(viewer, "batch-control/history/").getStatusCode());
    }

    /** T-12-02: the retention work deletes month files past retentionMonths and records it. */
    @Test
    public void t_12_02_retentionDeletesOldMonthFilesAndLeavesRecord() throws Exception {
        cfg.setRetentionMonths(1);
        cfg.save();

        YearMonth oldMonth = YearMonth.now().minusMonths(3);
        // mid-month noon UTC keeps the instant inside the same month in every zone
        Instant oldInstant = oldMonth.atDay(15).atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();
        BatchClock.setForTest(Clock.fixed(oldInstant, ZoneOffset.UTC));

        // an old run record, an old change record and an old incident
        FileStore.get().appendRunRecord(new RunRecord("old-x#1", "old-x", 1,
                CauseType.USER, "SUCCESS", oldInstant, 1234L));
        j.createFreeStyleProject("old-created-job"); // ChangeRecord(CREATE) at the old time
        FreeStyleProject oldFail = j.createFreeStyleProject("old-fail");
        oldFail.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, oldFail.scheduleBuild2(0));
        j.waitUntilNoActivity();

        assertFalse("fixture: the old month must hold run records",
                FileStore.get().listRunRecords(oldMonth).isEmpty());
        assertFalse("fixture: the old month must hold change records",
                FileStore.get().listChangeRecords(oldMonth).isEmpty());
        assertFalse("fixture: the old month must hold incidents",
                IncidentService.get().list(oldMonth).isEmpty());

        BatchClock.reset(); // retention judges against the real current time
        ExtensionList.lookupSingleton(RetentionPeriodicWork.class).doRun();

        assertTrue("run records past retentionMonths must be deleted",
                FileStore.get().listRunRecords(oldMonth).isEmpty());
        assertTrue("change records past retentionMonths must be deleted",
                FileStore.get().listChangeRecords(oldMonth).isEmpty());
        assertTrue("incidents past retentionMonths must no longer be listed",
                IncidentService.get().list(oldMonth).isEmpty());

        List<ChangeRecord> retention = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(rec -> rec.getType() == ChangeType.RETENTION)
                .collect(Collectors.toList());
        assertFalse("the deletion itself must be recorded as ChangeRecord(RETENTION)",
                retention.isEmpty());
        String retentionText = retention.stream()
                .map(rec -> rec.getTarget() + " " + rec.getDetail())
                .collect(Collectors.joining(" "));
        assertTrue("the RETENTION record must identify the deleted month",
                retentionText.contains(oldMonth.toString()));
    }

    /** T-12-03: date filters and CSV export work for all four data sets. */
    @Test
    public void t_12_03_dateFiltersAndCsvExportsForAllFourDataSets() throws Exception {
        // one marker per data set
        FreeStyleProject histX = j.createFreeStyleProject("hist-x");     // run + change marker
        j.buildAndAssertSuccess(histX);
        FreeStyleProject histFail = j.createFreeStyleProject("hist-fail"); // incident marker
        histFail.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, histFail.scheduleBuild2(0));
        FreeStyleProject histReq = j.createFreeStyleProject("hist-req");  // request marker
        histReq.addProperty(new BatchControlJobProperty(true));
        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().create(histReq, new LinkedHashMap<>(),
                    "history filter probe", "a1");
        }
        j.waitUntilNoActivity();

        LocalDate today = LocalDate.now();
        String inclusive = "?from=" + today.minusDays(1) + "&to=" + today.plusDays(1);
        String exclusive = "?from=2020-01-01&to=2020-01-02";
        String[] markers = {"hist-x", "hist-fail", "hist-x", "hist-req"};

        JenkinsRule.WebClient viewer = webClient("viewer");
        for (int i = 0; i < CSV_PATHS.length; i++) {
            WebResponse in = get(viewer, CSV_PATHS[i] + inclusive);
            assertEquals(CSV_PATHS[i] + ": CSV download must succeed",
                    200, in.getStatusCode());
            assertTrue(CSV_PATHS[i] + ": the response must be served as CSV, was "
                    + in.getContentType(),
                    in.getContentType().toLowerCase(Locale.ROOT).contains("csv"));
            assertTrue(CSV_PATHS[i] + ": an in-range record must be exported",
                    in.getContentAsString().contains(markers[i]));

            WebResponse out = get(viewer, CSV_PATHS[i] + exclusive);
            assertEquals(200, out.getStatusCode());
            assertFalse(CSV_PATHS[i] + ": a record outside the date filter must not be exported",
                    out.getContentAsString().contains(markers[i]));
        }

        // the history screen honors the same date filter
        WebResponse screenIn = get(viewer, "batch-control/history/" + inclusive);
        assertEquals(200, screenIn.getStatusCode());
        assertTrue("the in-range run must be listed on the history screen",
                screenIn.getContentAsString().contains("hist-x"));
        WebResponse screenOut = get(viewer, "batch-control/history/" + exclusive);
        assertEquals(200, screenOut.getStatusCode());
        assertFalse("an out-of-range run must not be listed on the history screen",
                screenOut.getContentAsString().contains("hist-x"));
    }

    /** T-12-04: the monthly summary reports every count exactly. */
    @Test
    public void t_12_04_monthlySummaryCountsAreExact() throws Exception {
        // 2 SUCCESS (one via an approved request), 1 FAILURE, 1 UNSTABLE
        FreeStyleProject sumA = j.createFreeStyleProject("sum-a");
        sumA.addProperty(new BatchControlJobProperty(true));
        RunRequest approved;
        try (ACLContext ignored = as("u1")) {
            approved = RunRequestService.get().create(sumA, new LinkedHashMap<>(),
                    "summary success run", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(approved.getId(), "ok");
        }
        j.waitUntilNoActivity();
        j.assertBuildStatusSuccess(sumA.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));

        FreeStyleProject sumF = j.createFreeStyleProject("sum-f");
        sumF.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, sumF.scheduleBuild2(0)); // incident stays OPEN

        FreeStyleProject sumU = j.createFreeStyleProject("sum-u");
        sumU.getBuildersList().add(new UnstableBuilder());
        j.assertBuildStatus(Result.UNSTABLE, sumU.scheduleBuild2(0));
        j.waitUntilNoActivity();

        // resolve the UNSTABLE incident -> OPEN 1 / RESOLVED 1
        Incident unstableIncident = IncidentService.get().list(YearMonth.now()).stream()
                .filter(incident -> "sum-u#1".equals(incident.getRunId()))
                .findFirst().orElse(null);
        assertNotNull("fixture: the UNSTABLE completion must have opened an incident",
                unstableIncident);
        try (ACLContext ignored = as("viewer")) {
            IncidentService.get().acknowledge(unstableIncident.getId(), "known flake");
            IncidentService.get().resolve(unstableIncident.getId(), "flake confirmed");
        }
        assertEquals(IncidentStatus.RESOLVED,
                IncidentService.get().load(unstableIncident.getId()).getStatus());

        // 1 approved + 1 rejected request
        RunRequest rejected;
        try (ACLContext ignored = as("u1")) {
            rejected = RunRequestService.get().create(sumA, new LinkedHashMap<>(),
                    "summary rejected run", "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().reject(rejected.getId(), "not this month");
        }

        WebResponse summary = get(webClient("viewer"),
                "batch-control/history/summary?month=" + YearMonth.now());
        assertEquals("the monthly summary must render for a ViewHistory holder",
                200, summary.getStatusCode());
        String body = summary.getContentAsString();
        assertSummaryCount(body, "runs", 4);
        assertSummaryCount(body, "success", 2);
        assertSummaryCount(body, "failure", 1);
        assertSummaryCount(body, "unstable", 1);
        assertSummaryCount(body, "incidentsOpen", 1);
        assertSummaryCount(body, "incidentsResolved", 1);
        assertSummaryCount(body, "requestsApproved", 1);
        assertSummaryCount(body, "requestsRejected", 1);
    }

    /** T-12-05: every CSV export answers 403 without ViewHistory. */
    @Test
    public void t_12_05_csvExportsWithoutViewHistoryAre403() throws Exception {
        JenkinsRule.WebClient noHistory = webClient("nohist");
        for (String path : CSV_PATHS) {
            assertEquals(path + " without ViewHistory must be 403",
                    403, get(noHistory, path).getStatusCode());
        }
    }

    /** T-RT-11 (D-18): CSV cells starting with = + - @ are neutralized with a leading apostrophe. */
    @Test
    public void t_rt_11_csvFormulaCellsAreSanitized() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("csv-x");
        job.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("P1", "safe"),
                new StringParameterDefinition("P2", "safe"),
                new StringParameterDefinition("P3", "safe")));
        job.addProperty(new BatchControlJobProperty(true));

        final String reasonPayload = "=1+1";
        final String[] parameterPayloads = {"+HYPERLINK(1,2)", "@SUM(1,2)", "-2+3"};
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("P1", parameterPayloads[0]);
        parameters.put("P2", parameterPayloads[1]);
        parameters.put("P3", parameterPayloads[2]);
        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, parameters, reasonPayload, "a1");
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(request.getId(), "ok");
        }
        j.waitUntilNoActivity();
        assertEquals("the approved request must have produced the run record",
                1, job.getBuilds().size());

        JenkinsRule.WebClient viewer = webClient("viewer");
        String requestsCsv = get(viewer, "batch-control/history/requests.csv").getContentAsString();
        String runsCsv = get(viewer, "batch-control/history/runs.csv").getContentAsString();

        // the reason is a cell of its own: it must be exported AND carry the apostrophe
        assertPresentAndApostrophePrefixed(requestsCsv, reasonPayload);

        // the parameter payloads must reach the run export, and no CSV may expose any
        // payload at the start of a cell without the neutralizing apostrophe
        for (String payload : parameterPayloads) {
            assertTrue("runs.csv must export the parameter value " + payload,
                    runsCsv.contains(payload));
        }
        String changesCsv = get(viewer, "batch-control/history/changes.csv").getContentAsString();
        String incidentsCsv = get(viewer, "batch-control/history/incidents.csv").getContentAsString();
        for (String csv : new String[] {requestsCsv, runsCsv, changesCsv, incidentsCsv}) {
            assertNoUnsanitizedFormulaCellStart(csv, reasonPayload);
            for (String payload : parameterPayloads) {
                assertNoUnsanitizedFormulaCellStart(csv, payload);
            }
        }
    }

    /** T-SEC-06 (remainder): GET on the incident transition endpoints never changes state. */
    @Test
    public void t_sec_06_getOnIncidentTransitionEndpointsIsRejected() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("sec-fail");
        job.getBuildersList().add(new FailureBuilder());
        j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
        j.waitUntilNoActivity();
        Incident incident = IncidentService.get().list(YearMonth.now()).stream()
                .filter(i -> "sec-fail#1".equals(i.getRunId()))
                .findFirst().orElse(null);
        assertNotNull(incident);
        int requestsBefore = RunRequestService.get().list().size();

        // the callers hold the right permissions, only the verb is wrong
        JenkinsRule.WebClient viewer = webClient("viewer");
        for (String action : new String[] {"acknowledge", "resolve"}) {
            int code = get(viewer,
                    "batch-control/incidents/" + incident.getId() + "/" + action
                            + "?comment=via-get").getStatusCode();
            assertTrue("GET must never " + action + " an incident (got " + code + ")",
                    code >= 400);
        }
        JenkinsRule.WebClient requester = webClient("u1");
        int rerunCode = get(requester,
                "batch-control/incidents/" + incident.getId() + "/rerun?approver=a1")
                .getStatusCode();
        assertTrue("GET must never create a rerun request (got " + rerunCode + ")",
                rerunCode >= 400);

        Incident reloaded = IncidentService.get().load(incident.getId());
        assertEquals("the incident must still be OPEN after every rejected GET",
                IncidentStatus.OPEN, reloaded.getStatus());
        assertTrue("no rerun request id may have been linked",
                reloaded.getRerunRequestIds() == null
                        || reloaded.getRerunRequestIds().isEmpty());
        assertEquals("no request may have been created by a GET",
                requestsBefore, RunRequestService.get().list().size());
    }

    /** T-SEC-06 (remainder): GET on the global configure paths never flips a switch. */
    @Test
    public void t_sec_06_getOnConfigurePathsDoesNotToggleSwitches() throws Exception {
        assertTrue("precondition from setUp", cfg.isRunControlEnabled());
        assertFalse("precondition from setUp", cfg.isChangeControlEnabled());

        JenkinsRule.WebClient admin = webClient("admin");
        assertEquals("the global configure form itself renders on GET",
                200, get(admin, "configure").getStatusCode());
        int submitCode = get(admin, "configSubmit").getStatusCode();
        assertTrue("GET on the configure submission endpoint must be rejected (got "
                + submitCode + ")", submitCode >= 400);

        BatchControlGlobalConfiguration reloaded = BatchControlGlobalConfiguration.get();
        assertTrue("no GET may flip runControlEnabled", reloaded.isRunControlEnabled());
        assertFalse("no GET may flip changeControlEnabled", reloaded.isChangeControlEnabled());
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private WebResponse get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** The summary contract: a JSON body carrying "key": value pairs (matrix note 23). */
    private static void assertSummaryCount(String body, String key, int expected) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + expected + "\\b");
        assertTrue("the monthly summary must report " + key + "=" + expected
                + " but the body was: " + body, pattern.matcher(body).find());
    }

    /** D-18 strict form for a payload that is a cell of its own (e.g. the reason column). */
    private static void assertPresentAndApostrophePrefixed(String csv, String payload) {
        int idx = csv.indexOf(payload);
        assertTrue("the payload must be exported: " + payload, idx >= 0);
        while (idx >= 0) {
            assertTrue("every export of '" + payload
                    + "' must carry the leading apostrophe (D-18)",
                    idx > 0 && csv.charAt(idx - 1) == '\'');
            idx = csv.indexOf(payload, idx + 1);
        }
    }

    /**
     * D-18 general form: wherever the payload appears, it must never sit at the raw start
     * of a CSV cell (position 0, right after a delimiter, or right after an opening quote)
     * without the neutralizing apostrophe. Occurrences embedded mid-cell (e.g. inside a
     * "key=value" aggregate) are not formula-injectable and are allowed.
     */
    private static void assertNoUnsanitizedFormulaCellStart(String csv, String payload) {
        int idx = csv.indexOf(payload);
        while (idx >= 0) {
            boolean atCellStart;
            if (idx == 0) {
                atCellStart = true;
            } else {
                char before = csv.charAt(idx - 1);
                if (before == ',' || before == '\n' || before == '\r') {
                    atCellStart = true;
                } else if (before == '"') {
                    atCellStart = idx == 1 || isCellDelimiter(csv.charAt(idx - 2));
                } else {
                    atCellStart = false;
                }
            }
            assertFalse("CSV cell must not start with the unescaped formula payload '"
                    + payload + "' (D-18) around index " + idx, atCellStart);
            idx = csv.indexOf(payload, idx + 1);
        }
    }

    private static boolean isCellDelimiter(char c) {
        return c == ',' || c == '\n' || c == '\r';
    }
}
