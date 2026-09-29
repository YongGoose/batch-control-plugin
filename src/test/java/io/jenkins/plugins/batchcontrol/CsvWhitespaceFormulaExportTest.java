package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-SEC-30 and T-SEC-31 — the end-to-end halves of security-03 finding S-22. The unit
 * rows T-SEC-27..29 pin the encoder; these two pin the two cells an attacker actually controls,
 * because a unit test alone cannot show that the hostile bytes survive the whole path from the
 * HTTP request through storage into the export.
 *
 * <ul>
 *   <li>T-SEC-30 — the approver's decision comment. Nothing on its path to {@code requests.csv}
 *       trims it, so a {@code BatchControl/Approve} holder controls its leading bytes exactly.</li>
 *   <li>T-SEC-31 — the job name. This vector was <b>not</b> in the original security report and was
 *       found while fixing it: {@code Jenkins.checkGoodName} rejects ISO control characters and its
 *       own unsafe set, neither of which contains a leading space, so {@code " =1+1"} is a
 *       creatable job name that reaches three exports.</li>
 * </ul>
 *
 * <p>The existing D-18 row T-RT-11 (leading {@code = + - @} with no whitespace in front) stays
 * exactly as it was and is not duplicated here.
 */
@WithJenkins
public class CsvWhitespaceFormulaExportTest {

    /** A job name whose first non-whitespace character starts a formula. */
    private static final String HOSTILE_JOB_NAME = " =1+1";

    /** A decision comment whose first non-whitespace character starts a formula. */
    private static final String HOSTILE_COMMENT = "\t=2+3";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST) // D-38 (#24): requesters need Item/Build
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to("a1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /**
     * T-SEC-30: an approver rejects a request with a decision comment beginning with a TAB and a
     * formula. The stored comment keeps the attacker's bytes (asserted, because otherwise this row
     * would pass for an implementation that simply trimmed the input) and the exported cell is
     * neutralised with a leading apostrophe.
     */
    @Test
    public void t_sec_30_rejectionCommentLedByATabIsNeutralisedInRequestsCsv() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("csv-decision");
        BatchControlFixtures.setBatchControl(job, new BatchControlJobProperty(true));

        RunRequest request;
        try (ACLContext ignored = as("u1")) {
            request = RunRequestService.get().create(job, new LinkedHashMap<>(),
                    "quarter close", "a1");
        }

        JenkinsRule.WebClient approver = webClient("a1");
        WebRequest reject = new WebRequest(
                approver.createCrumbedUrl("batch-control/requests/" + request.getId() + "/reject"),
                HttpMethod.POST);
        reject.setRequestParameters(Arrays.asList(new NameValuePair("comment", HOSTILE_COMMENT)));
        int status = approver.getPage(reject).getWebResponse().getStatusCode();
        assertTrue(status < 400, "the rejection itself must be accepted (the payload is a legitimate comment), got "
                + status);

        RunRequest stored = RunRequestService.get().load(request.getId());
        assertEquals(RequestStatus.REJECTED, stored.getStatus(), "fixture: the request must really have been rejected");
        assertEquals(HOSTILE_COMMENT, stored.getDecisionComment(), "premise: the comment must be stored byte for byte, leading TAB included -"
                + " if it were trimmed on the way in, this row would measure nothing");

        String csv = body("viewer", "batch-control/history/requests.csv");
        assertTrue(csv.contains("'" + HOSTILE_COMMENT), "requests.csv must export the comment with the neutralising apostrophe, but"
                + " the export was: " + describe(csv));
        assertCellStartsWithApostrophe(csv, "requests.csv", HOSTILE_COMMENT);
    }

    /**
     * T-SEC-31: a job whose name begins with a space and then a formula. First that such a job can
     * be created at all — the whole vector rests on it — then that its name is neutralised in
     * {@code requests.csv}, {@code runs.csv} and the target column of {@code changes.csv}.
     */
    @Test
    public void t_sec_31_jobNameBeginningWithASpaceIsNeutralisedInEveryExport() throws Exception {
        // 1. The premise: Jenkins core accepts this name.
        FreeStyleProject hostile = j.createFreeStyleProject(HOSTILE_JOB_NAME);
        assertEquals(HOSTILE_JOB_NAME, hostile.getFullName(), "premise: a job name beginning with a space and a formula must be creatable -"
                + " core's name check rejects ISO control characters and its own unsafe set,"
                + " neither of which covers a leading space. If this ever starts failing, the"
                + " S-22 job-name vector is closed by core and this row needs re-deciding.");

        // 2. A run record and a run request naming that job.
        BatchControlFixtures.uncontrolled(hostile);
        j.buildAndAssertSuccess(hostile);
        j.waitUntilNoActivity();
        try (ACLContext ignored = as("u1")) {
            RunRequestService.get().create(hostile, new LinkedHashMap<>(),
                    "nightly close of the oddly named job", "a1");
        }

        // 3. The CREATE record's target column, which is where changes.csv carries the name.
        ChangeRecord created = FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(r -> r.getType() == ChangeType.CREATE
                        && HOSTILE_JOB_NAME.equals(r.getTarget()))
                .findFirst().orElse(null);
        assertNotNull(created, "fixture: creating the job must have left a CREATE record whose target is the"
                + " hostile name; records were "
                + FileStore.get().listChangeRecords(YearMonth.now()).stream()
                        .map(r -> r.getType() + ":" + describe(r.getTarget()))
                        .collect(Collectors.toList()));

        // 4. The three exports.
        String requestsCsv = body("viewer", "batch-control/history/requests.csv");
        String runsCsv = body("viewer", "batch-control/history/runs.csv");
        String changesCsv = body("viewer", "batch-control/history/changes.csv");

        assertTrue(requestsCsv.contains(",'" + HOSTILE_JOB_NAME + ","), "requests.csv must neutralise the job-name cell, but the export was: "
                + describe(requestsCsv));
        assertTrue(runsCsv.contains(",'" + HOSTILE_JOB_NAME + ","), "runs.csv must neutralise the job-name cell, but the export was: "
                + describe(runsCsv));
        assertTrue(Pattern.compile("(?m)^[^,]*,CREATE,'" + Pattern.quote(HOSTILE_JOB_NAME) + ",")
                        .matcher(changesCsv).find(), "changes.csv must neutralise the target cell of the CREATE record, but the"
                        + " export was: " + describe(changesCsv));

        // No occurrence anywhere may sit at a raw cell start - the run id cell of runs.csv starts
        // with the same bytes and is the first column of its line.
        for (String[] csv : new String[][] {
                {"requests.csv", requestsCsv}, {"runs.csv", runsCsv}, {"changes.csv", changesCsv}}) {
            assertCellStartsWithApostrophe(csv[1], csv[0], HOSTILE_JOB_NAME);
        }
    }

    // ---------------------------------------------------------------- assertions

    /**
     * Wherever the payload appears at the start of a CSV cell — position 0, right after a delimiter,
     * or right after an opening quote — it must be preceded by the neutralising apostrophe.
     * Occurrences embedded mid-cell are not formula-injectable and are allowed. Same shape as the
     * D-18 helper in {@code HistoryWebTest}, extended because the payload here starts with
     * whitespace.
     */
    private static void assertCellStartsWithApostrophe(String csv, String where, String payload) {
        int found = 0;
        int idx = csv.indexOf(payload);
        assertTrue(idx >= 0, where + " must export the payload at all: " + describe(payload));
        while (idx >= 0) {
            char before = idx == 0 ? '\n' : csv.charAt(idx - 1);
            boolean atCellStart;
            if (idx == 0 || before == ',' || before == '\n' || before == '\r') {
                atCellStart = true;
            } else if (before == '"') {
                atCellStart = idx == 1 || isDelimiter(csv.charAt(idx - 2));
            } else {
                atCellStart = false;
            }
            assertFalse(atCellStart, where + ": a cell must not start with the unescaped payload "
                    + describe(payload) + " (S-22) around index " + idx);
            if (before == '\'') {
                found++;
            }
            idx = csv.indexOf(payload, idx + 1);
        }
        assertTrue(found > 0, where + ": at least one occurrence of " + describe(payload)
                + " must be an apostrophe-prefixed cell, otherwise this assertion passes for an"
                + " export that does not contain the value as a cell at all");
    }

    private static boolean isDelimiter(char c) {
        return c == ',' || c == '\n' || c == '\r';
    }

    // ---------------------------------------------------------------- helpers

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private String body(String userId, String relative) throws Exception {
        WebResponse response = webClient(userId)
                .getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
        assertEquals(200, response.getStatusCode(), relative + " must be downloadable by a ViewHistory holder");
        return response.getContentAsString();
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    /** Renders control characters visibly so a failure message can be read. */
    private static String describe(String value) {
        String visible = value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
        return "\"" + (visible.length() <= 800 ? visible : visible.substring(0, 800) + "...") + "\"";
    }
}
