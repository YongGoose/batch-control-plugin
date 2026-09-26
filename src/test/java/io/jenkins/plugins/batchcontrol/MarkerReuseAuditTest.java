package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.Failure;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-30 — where a blocked re-use of an approved-run marker is recorded.
 * Matrix rows T-06-17 (the attempt lands as its own audit record and is queryable on the
 * dashboard and in the CSV export), T-06-18 (a clean approved run produces no such record —
 * the false-positive guard) and T-06-19 (the record is not exposed without ViewHistory).
 *
 * SPEC basis: item 6, "동일 마커의 재사용 시도는 감사 이력에 별도 레코드로 남고 대시보드·CSV에서
 * 조회된다. (D-30)", read together with item 12 (the four query/CSV data sets, all gated by
 * ViewHistory) and the D-23 criterion one line above it (the marker is bound to the request id
 * and consumed by one queue submission).
 *
 * Division of labour with the existing coverage: the refusal itself (same job re-queue and
 * cross-job re-submission both blocked, build count unchanged) is T-RT-02 in
 * {@link RequestIntegrityTest} and is NOT re-asserted here as a row — the re-queue refusal
 * appears below only as a fixture control, so that the record this class measures is provably
 * the record of a *blocked* attempt.
 *
 * Accounts are deliberately distinct so that "who attempted it" is falsifiable:
 * requester {@code u1}, designated approver {@code a1}, a second Request holder {@code u2}
 * who never requested or ran anything and only tries to revive u1's consumed approval,
 * {@code viewer} with ViewHistory, and {@code nohist} without it.
 *
 * Derivation note (no src/main was read): the audit history that SPEC item 12 gives a screen
 * and a CSV export to, and that is neither a run, an incident nor a request, is the change
 * record set ({@code batch-control/history/changes.csv}). These rows therefore read the audit
 * history through {@link FileStore#listChangeRecords(YearMonth)} and that export. A record is
 * recognised as the re-use record by its text referring to the consumed request, and is
 * required to carry a type of its own (not one of the eight ChangeRecord types SPEC section 3
 * lists), which is what "별도 레코드" asks for. See matrix note 37.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MarkerReuseAuditTest {

    private static final String REQUESTER = "u1";
    private static final String APPROVER = "a1";
    private static final String REUSER = "u2";
    private static final String VIEWER = "viewer";
    private static final String NO_HISTORY = "nohist";
    private static final String ADMIN = "admin";

    private static final String CHANGES_CSV = "batch-control/history/changes.csv";
    private static final String HISTORY_SCREEN = "batch-control/history/";

    /** The ChangeRecord types SPEC section 3 already defines; the re-use record needs its own. */
    private static final Set<ChangeType> SPEC_DEFINED_TYPES = EnumSet.of(
            ChangeType.CREATE, ChangeType.CONFIGURE, ChangeType.DELETE, ChangeType.RENAME,
            ChangeType.MOVE, ChangeType.CONFIG_TOGGLE, ChangeType.RETENTION,
            ChangeType.GRANT_REVOKE);

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to(ADMIN)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(REQUESTER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE)
                        .everywhere().to(APPROVER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(REUSER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to(VIEWER)
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST)
                        .everywhere().to(NO_HISTORY));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList(APPROVER));
        cfg.save();
    }

    /**
     * T-06-17 (D-30): after the approved run consumed the marker, two further submissions of
     * the same marker — one by the requester (rebuild), one by another user (revival) — are
     * each recorded as an independent audit record that names what was attempted, by whom and
     * when, and both records are queryable on the dashboard and in the CSV export.
     */
    @Test
    public void t_06_17_blockedMarkerReuseIsRecordedAndQueryableOnDashboardAndCsv() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reuse-x");
        job.addProperty(new BatchControlJobProperty(true));

        RunRequest request = createAs(REQUESTER, job);
        approveAs(APPROVER, request.getId());
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved submission must run exactly once");

        FreeStyleBuild approvedBuild = job.getBuildByNumber(1);
        ApprovedRunAction marker = approvedBuild.getAction(ApprovedRunAction.class);
        assertNotNull(marker, "fixture: the executed run must carry the marker action");
        assertEquals(request.getId(), marker.getRequestId(), "fixture: the marker must be bound to the request id");
        assertTrue(reuseRecords(request.getId()).isEmpty(), "fixture: no re-use record may exist before a re-use is attempted");

        // Two separate re-use attempts by two different accounts.
        // The refusal itself is T-RT-02's row; here it is only the precondition that makes the
        // records below records of *blocked* attempts.
        as(REQUESTER, () -> assertScheduleRefused(
                "fixture: the requester's rebuild with the consumed marker must be refused",
                () -> job.scheduleBuild2(0, new Cause.UserIdCause(), marker)));
        as(REUSER, () -> assertScheduleRefused(
                "fixture: another user's revival of the consumed marker must be refused",
                () -> job.scheduleBuild2(0, new Cause.UserIdCause(), marker)));
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: no refused attempt may have produced a build");

        List<ChangeRecord> records = reuseRecords(request.getId());
        assertEquals(2, records.size(), "each blocked re-use attempt must leave one audit record of its own"
                + " (D-30) - found " + describe(records));

        for (ChangeRecord record : records) {
            String text = textOf(record);
            assertNotNull(record.getId(), "the re-use record must carry its own id");
            assertNotNull(record.getAt(), "the re-use record must record when it happened");
            assertTrue(text.contains(request.getId()), "the re-use record must name what was attempted - the consumed request"
                    + " id (" + request.getId() + "): " + text);
            assertTrue(text.contains("reuse-x"), "the re-use record must name the job the marker was replayed on: " + text);
            assertFalse(SPEC_DEFINED_TYPES.contains(record.getType()), "the re-use record must be a record of its own kind, not one of the"
                    + " eight ChangeRecord types SPEC section 3 defines (was "
                    + record.getType() + ")");
        }

        List<String> actors = records.stream().map(ChangeRecord::getUser).collect(Collectors.toList());
        assertTrue(actors.contains(REQUESTER), "the requester's own re-use attempt must be attributed to " + REQUESTER
                + " but the records were attributed to " + actors);
        assertTrue(actors.contains(REUSER), "the other user's re-use attempt must be attributed to " + REUSER
                + " (the actor of the attempt, not the owner of the approval) but the records"
                + " were attributed to " + actors);

        // CSV export: every attempt must be exported as its own row carrying request id and actor
        JenkinsRule.WebClient viewer = webClient(VIEWER);
        WebResponse csv = get(viewer, CHANGES_CSV);
        assertEquals(200, csv.getStatusCode(), "the change export must be served to a ViewHistory holder");
        List<String> exported = linesContaining(csv.getContentAsString(), request.getId());
        assertEquals(2, exported.size(), "both blocked re-use attempts must appear in changes.csv, one row each"
                + " - matched rows: " + exported);
        assertTrue(exported.stream().anyMatch(line -> hasCell(line, REQUESTER)), "one exported re-use row must carry " + REQUESTER + " as a cell: " + exported);
        assertTrue(exported.stream().anyMatch(line -> hasCell(line, REUSER)), "one exported re-use row must carry " + REUSER + " as a cell: " + exported);

        // Dashboard: the attempt is queryable by the account that made it. u2 never requested,
        // approved or ran anything, so the request id can only reach a user=u2 view through the
        // re-use record itself.
        WebResponse byActor = get(viewer, HISTORY_SCREEN + "?user=" + REUSER);
        assertEquals(200, byActor.getStatusCode(), "the history screen must render for a ViewHistory holder");
        assertTrue(byActor.getContentAsString().contains(request.getId()), "the blocked re-use attempt must be findable on the dashboard by its actor"
                + " (user=" + REUSER + "): the consumed request id " + request.getId()
                + " is missing from the rendered screen");
    }

    /**
     * T-06-18 (D-30, false-positive guard): a request that is approved and runs once, with no
     * re-use attempted, must leave NO re-use record anywhere — otherwise an implementation that
     * records on every approved submission would satisfy T-06-17 while telling the operator
     * nothing.
     */
    @Test
    public void t_06_18_cleanApprovedRunLeavesNoReuseRecord() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reuse-clean");
        job.addProperty(new BatchControlJobProperty(true));

        RunRequest request = createAs(REQUESTER, job);
        approveAs(APPROVER, request.getId());
        j.waitUntilNoActivity();
        assertEquals(1, job.getBuilds().size(), "fixture: the approved request must have run once");
        assertNotNull(RunRequestService.get().load(request.getId()).getExecutedRunId(), "fixture: the run must be linked back to the request");

        assertTrue(reuseRecords(request.getId()).isEmpty(), "one approved submission must not be recorded as a marker re-use"
                + " - found " + describe(reuseRecords(request.getId())));
        List<ChangeRecord> foreignTypes = FileStore.get().listChangeRecords(YearMonth.now())
                .stream()
                .filter(record -> !SPEC_DEFINED_TYPES.contains(record.getType()))
                .collect(Collectors.toList());
        assertTrue(foreignTypes.isEmpty(), "no record of the re-use kind may exist at all after a clean run"
                + " - found " + describe(foreignTypes));

        WebResponse csv = get(webClient(VIEWER), CHANGES_CSV);
        assertEquals(200, csv.getStatusCode());
        String body = csv.getContentAsString();
        assertTrue(body.contains("reuse-clean"), "fixture control: the change export must not be empty - the job creation"
                + " itself is recorded, so 'reuse-clean' must be exported");
        assertFalse(body.contains(request.getId()), "a clean approved run must not export any row referring to the request as a"
                + " blocked re-use: " + linesContaining(body, request.getId()));
    }

    /**
     * T-06-19 (D-30 x P-09): the re-use record is audit history, so it follows the item 12 gate.
     * A user without ViewHistory must not reach it through the dashboard or the CSV export, and
     * must not learn its content from the refusal either.
     */
    @Test
    public void t_06_19_reuseRecordIsNotExposedWithoutViewHistory() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reuse-guarded");
        job.addProperty(new BatchControlJobProperty(true));

        RunRequest request = createAs(REQUESTER, job);
        approveAs(APPROVER, request.getId());
        j.waitUntilNoActivity();
        FreeStyleBuild approvedBuild = job.getBuildByNumber(1);
        assertNotNull(approvedBuild, "fixture: the approved request must have run once");
        ApprovedRunAction marker = approvedBuild.getAction(ApprovedRunAction.class);
        assertNotNull(marker, "fixture: the executed run must carry the marker action");

        as(NO_HISTORY, () -> assertScheduleRefused(
                "fixture: the re-use attempt must be refused",
                () -> job.scheduleBuild2(0, new Cause.UserIdCause(), marker)));
        j.waitUntilNoActivity();

        List<ChangeRecord> records = reuseRecords(request.getId());
        assertEquals(1, records.size(), "fixture: the blocked attempt must have been recorded"
                + " - found " + describe(records));
        String recordId = records.get(0).getId();

        JenkinsRule.WebClient blind = webClient(NO_HISTORY);
        for (String path : new String[] {
                HISTORY_SCREEN,
                HISTORY_SCREEN + "?user=" + NO_HISTORY,
                CHANGES_CSV,
                CHANGES_CSV + "?user=" + NO_HISTORY}) {
            WebResponse response = get(blind, path);
            assertEquals(403, response.getStatusCode(), path + " must be 403 without ViewHistory");
            String body = response.getContentAsString();
            assertFalse(body.contains(request.getId()), path + " must not disclose the consumed request id");
            assertFalse(body.contains(recordId), path + " must not disclose the re-use record id");
        }

        // control: with ViewHistory the very same record is readable, so the assertions above
        // measure the gate and not an absent record
        WebResponse allowed = get(webClient(VIEWER), CHANGES_CSV);
        assertEquals(200, allowed.getStatusCode());
        assertTrue(allowed.getContentAsString().contains(request.getId()), "control: a ViewHistory holder must see the re-use row");
    }

    // ---------------------------------------------------------------- helpers

    /** Audit records that refer to the consumed request; see the class derivation note. */
    private List<ChangeRecord> reuseRecords(String requestId) {
        return FileStore.get().listChangeRecords(YearMonth.now()).stream()
                .filter(record -> textOf(record).contains(requestId))
                .collect(Collectors.toList());
    }

    private static String textOf(ChangeRecord record) {
        StringBuilder text = new StringBuilder();
        append(text, record.getTarget());
        append(text, record.getDetail());
        append(text, record.getDiff());
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (value != null) {
            text.append(value).append(' ');
        }
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream()
                .map(record -> record.getType() + "/" + record.getUser() + "/" + textOf(record))
                .collect(Collectors.toList())
                .toString();
    }

    private static List<String> linesContaining(String csv, String needle) {
        List<String> matched = new ArrayList<>();
        for (String line : csv.split("\r?\n")) {
            if (line.contains(needle)) {
                matched.add(line);
            }
        }
        return matched;
    }

    /** True when the value occupies a cell of the CSV row (not a fragment of another cell). */
    private static boolean hasCell(String line, String value) {
        for (String cell : line.split(",")) {
            String trimmed = cell.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (trimmed.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }

    private void as(String userId, Action action) throws Exception {
        try (ACLContext ignored = as(userId)) {
            action.run();
        }
    }

    private RunRequest createAs(String userId, FreeStyleProject target) {
        try (ACLContext ignored = as(userId)) {
            return RunRequestService.get()
                    .create(target, new LinkedHashMap<>(), "marker re-use audit run", APPROVER);
        }
    }

    private void approveAs(String userId, String requestId) {
        try (ACLContext ignored = as(userId)) {
            RunRequestService.get().approve(requestId, "ok");
        }
    }

    private JenkinsRule.WebClient webClient(String userId) throws Exception {
        return j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(userId);
    }

    private WebResponse get(JenkinsRule.WebClient wc, String relative) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), relative), HttpMethod.GET))
                .getWebResponse();
    }

    /** A refused schedule may either return null (silent refusal) or throw Failure (guidance). */
    private static void assertScheduleRefused(String message, ScheduleAttempt attempt)
            throws Exception {
        try {
            Future<?> future = attempt.call();
            assertNull(future, message);
        } catch (Failure expectedGuidance) {
            // throwing the guidance Failure is equally acceptable
        }
    }

    @FunctionalInterface
    private interface ScheduleAttempt {
        Future<?> call() throws Exception;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
