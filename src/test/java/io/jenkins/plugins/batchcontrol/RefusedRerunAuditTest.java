package io.jenkins.plugins.batchcontrol;

import com.chikli.hudson.plugin.naginator.FixedDelay;
import com.chikli.hudson.plugin.naginator.NaginatorPublisher;
import com.chikli.hudson.plugin.naginator.NaginatorRetryAction;
import com.sonyericsson.rebuild.RebuildAction;
import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.htmlunit.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.token;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertApprovedRunQueuedExactlyOnce;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.get;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.post;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.requestAndApprove;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.secureWithRunControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6 (e2e-03 DEF-03): "every refused retry, rebuild or re-queue of an approved or manual
 * run is recorded in the history (MARKER_REUSE_BLOCKED when it presents a consumed marker,
 * otherwise TRIGGER_BLOCKED with the cause kind), not only logged." Matrix rows T-06-61 ..
 * T-06-65 (note 118).
 *
 * <p>The refusals themselves are T-06-24/25/33/34; these rows add the record. Every job is
 * activated first (SPEC 6a), so the only reason left to refuse is the approval rule — a record
 * for a missing activation (T-06-54) cannot stand in for the one these rows ask for. Whether
 * naginator or rebuild copies the approval marker onto the new submission is the other
 * plugin's business, so for a re-run of an approved run either record type is accepted; a
 * MARKER_REUSE_BLOCKED record must then name the consumed request (D-30), and for a re-run of a
 * manual run (no marker exists) the record is TRIGGER_BLOCKED. Each job is used for one refusal
 * only, so the per-hour coalescing of TRIGGER_BLOCKED cannot hide a record.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class RefusedRerunAuditTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        secureWithRunControl(j);
    }

    /**
     * T-06-61 (DEF-03, PR-05): naginator's automatic retry of a failed approved run on an
     * activated job is refused and recorded.
     */
    @Test
    public void t_06_61_refusedAutomaticRetryOfApprovedRunIsRecorded() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-auto");
        job.getBuildersList().add(new FailureBuilder());
        job.getPublishersList().add(new NaginatorPublisher("", false, false, false, 1, new FixedDelay(0)));
        assertTrue(rerunRecords(job).isEmpty(), "fixture: no refusal record before the approved run");

        RunRequest request = requestAndApprove(job);
        j.waitUntilNoActivity();

        assertBlocked(j, job, 2, 1);
        j.assertBuildStatus(Result.FAILURE, job.getBuildByNumber(1));
        assertRecorded(job, request, null);
    }

    /**
     * T-06-62 (DEF-03, PR-05): u1 presses naginator's Retry on the failed approved run; the
     * refusal is recorded, and a MARKER_REUSE_BLOCKED record names u1 as the actor.
     */
    @Test
    public void t_06_62_refusedManualRetryOfApprovedRunIsRecorded() throws Exception {
        FreeStyleProject job = approvalRequired("rr-nag-manual");
        job.getBuildersList().add(new FailureBuilder());

        RunRequest request = requestAndApprove(job);
        FreeStyleBuild failed = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        j.assertBuildStatus(Result.FAILURE, failed);
        assertNotNull(failed.getAction(NaginatorRetryAction.class), "fixture: naginator must offer Retry");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: a clean approved run leaves no refusal record");

        post(j, "u1", failed.getUrl() + "retry/");

        assertBlocked(j, job, 2, 1);
        assertRecorded(job, request, "u1");
    }

    /**
     * T-06-63 (DEF-03, PR-02): u1 clicks Rebuild on an approved run; the refusal is recorded and
     * the record is in {@code changes.csv}.
     */
    @Test
    public void t_06_63_refusedRebuildOfApprovedRunIsRecordedAndExported() throws Exception {
        FreeStyleProject job = approvalRequired("rr-rebuild-approved");

        RunRequest request = requestAndApprove(job);
        FreeStyleBuild approved = (FreeStyleBuild) assertApprovedRunQueuedExactlyOnce(j, job);
        assertNotNull(approved.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: a clean approved run leaves no refusal record");

        post(j, "u1", approved.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
        ChangeRecord record = assertRecorded(job, request, "u1");

        Page csv = get(j, "admin", "batch-control/history/changes.csv");
        assertEquals(200, csv.getWebResponse().getStatusCode(), "the administrator must download changes.csv");
        String body = csv.getWebResponse().getContentAsString();
        assertTrue(body.lines().anyMatch(line -> line.contains(record.getType().name()) && line.contains("rr-rebuild-approved")),
                "changes.csv must carry the " + record.getType() + " row for rr-rebuild-approved:\n" + body);
    }

    /**
     * T-06-64 (DEF-03): a Rebuild of a manual run that ran before the job required approval (no
     * marker exists) is refused and recorded as TRIGGER_BLOCKED naming a cause kind.
     */
    @Test
    public void t_06_64_refusedRebuildOfManualRunIsRecordedAsTriggerBlocked() throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject("rr-rebuild-manual"));
        BatchControlFixtures.activate(job);
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatusSuccess(job.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        setBatchControl(job, new BatchControlJobProperty(true));
        FreeStyleBuild manual = job.getBuildByNumber(1);
        assertNotNull(manual.getAction(RebuildAction.class), "fixture: the rebuild plugin must offer its action");
        assertTrue(rerunRecords(job).isEmpty(), "fixture: no refusal record before the rebuild");

        post(j, "u1", manual.getUrl() + "rebuild/");

        assertBlocked(j, job, 2, 1);
        List<ChangeRecord> records = rerunRecords(job);
        assertEquals(1, records.size(), "the refused rebuild of a manual run must leave exactly one record: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals(ChangeType.TRIGGER_BLOCKED, record.getType(), "no marker was presented, so the record is TRIGGER_BLOCKED");
        assertNotNull(record.getDetail(), "the TRIGGER_BLOCKED record must name the cause kind");
        assertTrue(!record.getDetail().isBlank(), "the TRIGGER_BLOCKED record must name the cause kind");
    }

    /**
     * T-06-65 (DEF-03, negative twin): an admitted Rebuild (uncontrolled, activated job) and a
     * clean approved run leave no refusal record.
     */
    @Test
    public void t_06_65_admittedRerunsLeaveNoRefusalRecord() throws Exception {
        FreeStyleProject free = uncontrolled(j.createFreeStyleProject("rr-free"));
        BatchControlFixtures.activate(free);
        try (ACLContext ignored = ACL.as2(token("u1"))) {
            j.assertBuildStatusSuccess(free.scheduleBuild2(0, new Cause.UserIdCause()));
        }
        post(j, "u1", free.getBuildByNumber(1).getUrl() + "rebuild/");
        j.waitUntilNoActivity();
        assertEquals(2, free.getBuilds().size(), "fixture: the rebuild of an uncontrolled job must run");
        assertTrue(rerunRecords(free).isEmpty(), "an admitted rebuild must leave no refusal record: " + describe(rerunRecords(free)));

        FreeStyleProject approvedJob = approvalRequired("rr-clean");
        requestAndApprove(approvedJob);
        assertApprovedRunQueuedExactlyOnce(j, approvedJob);
        assertTrue(rerunRecords(approvedJob).isEmpty(), "a clean approved run must leave no refusal record: "
                + describe(rerunRecords(approvedJob)));
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject approvalRequired(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        setBatchControl(job, new BatchControlJobProperty(true));
        BatchControlFixtures.activate(job);
        return job;
    }

    private static List<ChangeRecord> rerunRecords(FreeStyleProject job) {
        List<ChangeRecord> out = new ArrayList<>();
        out.addAll(ActivationFixtures.recordsFor(ChangeType.MARKER_REUSE_BLOCKED, job.getFullName()));
        out.addAll(ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, job.getFullName()));
        return out;
    }

    /**
     * At least one refusal record for {@code job}; a MARKER_REUSE_BLOCKED one names the consumed
     * request and, when {@code actor} is given, that actor. Returns the first record.
     */
    private static ChangeRecord assertRecorded(FreeStyleProject job, RunRequest request, String actor) {
        List<ChangeRecord> records = rerunRecords(job);
        assertTrue(!records.isEmpty(), job.getFullName() + ": the refused re-run must be recorded in the history"
                + " (MARKER_REUSE_BLOCKED or TRIGGER_BLOCKED), not only logged");
        for (ChangeRecord record : records) {
            if (record.getType() == ChangeType.MARKER_REUSE_BLOCKED) {
                String text = record.getTarget() + " " + record.getDetail();
                assertTrue(text.contains(request.getId()), "a MARKER_REUSE_BLOCKED record must name the consumed request "
                        + request.getId() + ": " + describe(records));
                if (actor != null) {
                    assertEquals(actor, record.getUser(), "a MARKER_REUSE_BLOCKED record names the account that attempted"
                            + " the re-use");
                }
            } else {
                assertNotNull(record.getDetail(), "a TRIGGER_BLOCKED record must name the cause kind");
            }
        }
        return records.get(0);
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream()
                .map(r -> r.getType() + " user=" + r.getUser() + " target=" + r.getTarget() + " detail=" + r.getDetail())
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
