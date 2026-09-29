package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 6, "a refused timer, upstream or Replay submission writes a {@code TRIGGER_BLOCKED}
 * change record (job, cause kind, the switch that blocked it), coalesced so that one job and
 * cause kind produce at most one record per hour; the record appears in the history and in
 * {@code changes.csv}. (#21)". Matrix rows T-06-43 .. T-06-48 (note 85).
 *
 * <p>Frozen contract used here: {@code ChangeType.TRIGGER_BLOCKED}; {@code target} is the job's
 * full name; {@code detail} names the cause kind ({@code TIMER}, {@code UPSTREAM},
 * {@code REPLAY}) and the switch that blocked it ({@code blockTimer}, {@code blockUpstream},
 * and for Replay, which only approval blocks, {@code approvalRequired}).
 *
 * <p>The plugin clock is fixed (SPEC: no timer dependency), so "within the hour" and "after the
 * hour" are set by the test and not waited for.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md, issue #21 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class TriggerBlockedAuditTest {

    private static final Instant T0 = Instant.parse("2025-06-10T10:00:00Z");
    private static final YearMonth MONTH = YearMonth.of(2025, 6);
    private static final String PERIOD = "from=2025-06-01&to=2025-06-30";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("viewer"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin"));
        cfg.save();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /** T-06-43 (#21): a refused timer run leaves one TRIGGER_BLOCKED record naming TIMER and blockTimer. */
    @Test
    public void t_06_43_refusedTimerRunWritesOneTriggerBlockedRecord() throws Exception {
        FreeStyleProject job = timerLocked("tb-timer");
        clockAt(T0);
        assertTrue(triggerBlocked("tb-timer").isEmpty(), "fixture: no record before the refusal");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                "fixture: the timer run must be refused (blockTimer=true)");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "fixture: no build may exist");

        List<ChangeRecord> records = triggerBlocked("tb-timer");
        assertEquals(1, records.size(), "one refused timer run must write one TRIGGER_BLOCKED record: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals("tb-timer", record.getTarget(), "target must be the job full name");
        assertNotNull(record.getAt(), "the record must carry its time");
        assertNotNull(record.getDetail(), "the detail must name the cause kind and the switch");
        assertTrue(record.getDetail().contains("TIMER"), "detail must name the cause kind TIMER: " + record.getDetail());
        assertTrue(record.getDetail().contains("blockTimer"), "detail must name the switch blockTimer: " + record.getDetail());
        assertFalse(record.getDetail().contains("blockUpstream"), "detail must not name the other switch: " + record.getDetail());
    }

    /** T-06-44 (#21): a refused upstream run leaves one record naming UPSTREAM and blockUpstream. */
    @Test
    public void t_06_44_refusedUpstreamRunWritesOneTriggerBlockedRecord() throws Exception {
        FreeStyleBuild upstreamBuild = upstreamBuild("tb-up-src");
        FreeStyleProject job = upstreamLocked("tb-upstream");
        clockAt(T0);

        assertNull(job.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)),
                "fixture: the upstream run must be refused (blockUpstream=true, no allow list)");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "fixture: no build may exist");

        List<ChangeRecord> records = triggerBlocked("tb-upstream");
        assertEquals(1, records.size(), "one refused upstream run must write one TRIGGER_BLOCKED record: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals("tb-upstream", record.getTarget(), "target must be the job full name");
        assertNotNull(record.getDetail());
        assertTrue(record.getDetail().contains("UPSTREAM"), "detail must name the cause kind UPSTREAM: " + record.getDetail());
        assertTrue(record.getDetail().contains("blockUpstream"), "detail must name the switch blockUpstream: " + record.getDetail());
        assertFalse(record.getDetail().contains("blockTimer"), "detail must not name the other switch: " + record.getDetail());
    }

    /** T-06-45 (#21): a refused Replay leaves one record naming REPLAY and approvalRequired. */
    @Test
    public void t_06_45_refusedReplayWritesOneTriggerBlockedRecord() throws Exception {
        WorkflowJob pipeline = uncontrolled(j.createProject(WorkflowJob.class, "tb-replay"));
        pipeline.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        BatchControlFixtures.activate(pipeline, "admin", "admin"); // D-46: a cause-less submission needs an activation (note 109)
        j.buildAndAssertSuccess(pipeline);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(pipeline, property);
        clockAt(T0);

        ReplayAction replay = pipeline.getBuildByNumber(1).getAction(ReplayAction.class);
        assertNotNull(replay, "fixture: a completed pipeline build exposes Replay");
        assertNull(replay.run("echo 'replayed'", Collections.<String, String>emptyMap()),
                "fixture: Replay of an approval-required job must be refused");
        j.waitUntilNoActivity();
        assertEquals(1, pipeline.getBuilds().size(), "fixture: no second build may exist");

        List<ChangeRecord> records = triggerBlocked("tb-replay");
        assertEquals(1, records.size(), "one refused Replay must write one TRIGGER_BLOCKED record: " + describe(records));
        ChangeRecord record = records.get(0);
        assertEquals("tb-replay", record.getTarget(), "target must be the job full name");
        assertNotNull(record.getDetail());
        assertTrue(record.getDetail().contains("REPLAY"), "detail must name the cause kind REPLAY: " + record.getDetail());
        assertTrue(record.getDetail().contains("approvalRequired"),
                "detail must name the switch that blocks a Replay (approvalRequired): " + record.getDetail());
    }

    /**
     * T-06-46 (#21): two timer refusals of one job within the hour produce one record; a refusal
     * more than an hour after the recorded one produces a second.
     */
    @Test
    public void t_06_46_refusalsOfOneJobAndKindAreCoalescedPerHour() throws Exception {
        FreeStyleProject job = timerLocked("tb-coalesce");
        clockAt(T0);
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused");
        clockAt(T0.plusSeconds(20 * 60));
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused");
        j.waitUntilNoActivity();
        List<ChangeRecord> withinHour = triggerBlocked("tb-coalesce");
        assertEquals(1, withinHour.size(),
                "two refusals of one job and cause kind within an hour must leave one record: " + describe(withinHour));

        clockAt(T0.plusSeconds(61 * 60));
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused");
        j.waitUntilNoActivity();
        List<ChangeRecord> afterHour = triggerBlocked("tb-coalesce");
        assertEquals(2, afterHour.size(),
                "a refusal more than an hour after the recorded one must be recorded again: " + describe(afterHour));
        assertTrue(job.getBuilds().isEmpty(), "fixture: no refused run may have built");
    }

    /**
     * T-06-47 (#21, false-positive guards): coalescing is per job and cause kind, so a timer and
     * an upstream refusal of the same job in the same hour are two records, a second job is
     * recorded separately, and a timer run that passes (blockTimer off) writes no record.
     */
    @Test
    public void t_06_47_coalescingIsPerJobAndKindAndPassingRunsWriteNothing() throws Exception {
        FreeStyleBuild upstreamBuild = upstreamBuild("tb-up-src2");
        FreeStyleProject both = j.createFreeStyleProject("tb-both");
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(true);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Collections.emptyList());
        setBatchControl(both, property);
        activateAsAdmin(both);
        FreeStyleProject other = timerLocked("tb-other");
        FreeStyleProject open = j.createFreeStyleProject("tb-open");
        BatchControlJobProperty openProperty = new BatchControlJobProperty(true);
        openProperty.setBlockTimer(false);
        openProperty.setBlockUpstream(false);
        setBatchControl(open, openProperty);
        activateAsAdmin(open);
        clockAt(T0);

        assertNull(both.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: timer refused");
        assertNull(both.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)), "fixture: upstream refused");
        assertNull(other.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: timer refused");
        j.assertBuildStatusSuccess(open.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()));
        j.waitUntilNoActivity();

        List<ChangeRecord> onBoth = triggerBlocked("tb-both");
        assertEquals(2, onBoth.size(), "a timer and an upstream refusal are different cause kinds: " + describe(onBoth));
        assertTrue(onBoth.stream().anyMatch(r -> r.getDetail() != null && r.getDetail().contains("TIMER")), describe(onBoth));
        assertTrue(onBoth.stream().anyMatch(r -> r.getDetail() != null && r.getDetail().contains("UPSTREAM")), describe(onBoth));
        assertEquals(1, triggerBlocked("tb-other").size(), "another job's refusal is recorded on its own");
        assertTrue(triggerBlocked("tb-open").isEmpty(), "a timer run that passed must write no TRIGGER_BLOCKED record");
        assertEquals(1, open.getBuilds().size(), "fixture: the passing timer run built once");
    }

    /**
     * T-06-48 (#21): the record is listed on the history screen under {@code ?kind=changes} and
     * exported in {@code changes.csv}; the same views before the refusal do not carry it.
     */
    @Test
    public void t_06_48_triggerBlockedRecordIsInHistoryAndChangesCsv() throws Exception {
        FreeStyleProject job = timerLocked("tb-visible");
        clockAt(T0);
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("viewer");

        String csvBefore = get(wc, "batch-control/history/changes.csv?" + PERIOD).getContentAsString();
        assertTrue(linesWith(csvBefore, "TRIGGER_BLOCKED").isEmpty(), "fixture: no TRIGGER_BLOCKED row before the refusal");

        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused");
        j.waitUntilNoActivity();
        assertEquals(1, triggerBlocked("tb-visible").size(), "fixture: the refusal was recorded");

        WebResponse history = get(wc, "batch-control/history/?kind=changes&" + PERIOD);
        assertEquals(200, history.getStatusCode());
        String html = history.getContentAsString();
        assertTrue(html.contains("TRIGGER_BLOCKED"), "the history change list must show the TRIGGER_BLOCKED record");
        assertTrue(html.contains("tb-visible"), "the history change list must show the blocked job");

        WebResponse csv = get(wc, "batch-control/history/changes.csv?" + PERIOD);
        assertEquals(200, csv.getStatusCode());
        List<String> rows = linesWith(csv.getContentAsString(), "TRIGGER_BLOCKED");
        assertEquals(1, rows.size(), "changes.csv must export the record once: " + rows);
        assertTrue(rows.get(0).contains("tb-visible"), "the exported row must name the job: " + rows);
        assertTrue(rows.get(0).contains("TIMER") && rows.get(0).contains("blockTimer"),
                "the exported row must carry the detail (cause kind and switch): " + rows);
    }

    /** The coalescing bound, made configurable by core-dev for this row (matrix note 99). */
    private static final String MAX_KEYS_PROPERTY =
            "io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit.maxCoalescedKeys";

    /**
     * T-06-52 (security-12 S-12-01, P1): the coalescing memory is bounded but its bound must never
     * suppress a record. The bound is lowered to 50 through its system property, set before the
     * first refusal and cleared afterwards; 51 distinct locked jobs (one more than the bound) are
     * each refused once within one hour: every job has exactly one TRIGGER_BLOCKED record and no
     * submission throws. Guard: the most recently refused job refused again in the same hour
     * still coalesces (one record). The jobs are FreeStyle, created under run control (so locked,
     * D-34, and not activated, SPEC 6a) and never built.
     */
    @Test
    public void t_06_52_coalescingBoundNeverSuppressesARecord() throws Exception {
        String previous = System.getProperty(MAX_KEYS_PROPERTY);
        System.setProperty(MAX_KEYS_PROPERTY, "50");
        try {
            clockAt(T0);
            int count = 51;
            List<FreeStyleProject> jobs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                jobs.add(j.jenkins.createProject(FreeStyleProject.class, "tb-many-" + i));
            }
            for (FreeStyleProject job : jobs) {
                assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                        "fixture: " + job.getName() + " must be refused");
            }
            j.waitUntilNoActivity();

            java.util.Map<String, Long> perJob = FileStore.get().listChangeRecords(MONTH).stream()
                    .filter(r -> r.getType() == ChangeType.TRIGGER_BLOCKED)
                    .filter(r -> r.getTarget() != null && r.getTarget().startsWith("tb-many-"))
                    .collect(Collectors.groupingBy(ChangeRecord::getTarget, Collectors.counting()));
            assertEquals(count, perJob.size(), "every refused job must have a TRIGGER_BLOCKED record");
            List<String> notOne = perJob.entrySet().stream().filter(e -> e.getValue() != 1L)
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toList());
            assertTrue(notOne.isEmpty(), "each job must have exactly one record: " + notOne);

            FreeStyleProject last = jobs.get(count - 1);
            assertNull(last.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused again");
            assertEquals(1, triggerBlocked(last.getName()).size(), "a recent key must still coalesce within the hour");
            assertEquals(0, j.jenkins.getQueue().getItems().length, "no refused run may be queued");
        } finally {
            if (previous == null) {
                System.clearProperty(MAX_KEYS_PROPERTY);
            } else {
                System.setProperty(MAX_KEYS_PROPERTY, previous);
            }
        }
    }

    /**
     * T-06-53 (security-12 S-12-01, P0 — run blocking): the refusal fails closed when the audit
     * write fails. The month's change file is replaced by a directory of the same name, so no
     * append can succeed; the timer cause is still refused quietly (no exception) with the
     * blocking baseline. Guard: once the file is writable again, a refusal an hour later is
     * recorded, so the first refusal really went through the failing write path.
     */
    @Test
    public void t_06_53_refusalHoldsWhenTheAuditWriteFails() throws Exception {
        clockAt(T0);
        FreeStyleProject job = timerLocked("tb-failing-write");
        java.io.File month = new java.io.File(j.jenkins.getRootDir(), "batch-control/changes/" + MONTH + ".jsonl");
        if (month.isFile()) {
            assertTrue(month.delete(), "fixture: remove the month file");
        }
        assertTrue(month.mkdirs(), "fixture: a directory now stands where the month file is written");

        try {
            assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()),
                    "a refused timer must stay refused when its audit record cannot be written");
        } catch (RuntimeException e) {
            throw new AssertionError("a failed audit write must not surface from the queue gate", e);
        }
        assertEquals(0, j.jenkins.getQueue().getItems().length, "the queue must stay empty");
        j.waitUntilNoActivity();
        assertEquals(1, job.getNextBuildNumber(), "nextBuildNumber must not move");
        assertTrue(job.getBuilds().isEmpty(), "no build may have run");

        assertTrue(month.delete(), "fixture: restore a writable month file");
        clockAt(T0.plusSeconds(2 * 3600));
        assertNull(job.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "fixture: refused again");
        assertEquals(1, triggerBlocked("tb-failing-write").size(),
                "guard: with the file writable the next refusal (a new hour) is recorded");
        assertTrue(job.getBuilds().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private FreeStyleProject timerLocked(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(true);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        activateAsAdmin(job);
        assertTrue(property.isBlockTimer(), "fixture: blockTimer on");
        assertFalse(property.isBlockUpstream(), "fixture: blockUpstream off");
        return job;
    }

    private FreeStyleProject upstreamLocked(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(true);
        property.setAllowedUpstreamJobs(Collections.emptyList());
        setBatchControl(job, property);
        activateAsAdmin(job);
        assertTrue(property.isBlockUpstream(), "fixture: blockUpstream on");
        assertFalse(property.isBlockTimer(), "fixture: blockTimer off");
        return job;
    }

    /**
     * SPEC item 6a: the rows here pin that the <em>job switch</em> is named as the reason, so
     * the job is activated first and the switch is the only thing that refuses (note 91). The
     * administrator is the only approver of this class and may approve their own request
     * (allowAdminSelfApproval, default true).
     */
    private static void activateAsAdmin(FreeStyleProject job) throws Exception {
        BatchControlFixtures.activate(job, "admin", "admin");
    }

    private FreeStyleBuild upstreamBuild(String name) throws Exception {
        FreeStyleProject upstream = uncontrolled(j.createFreeStyleProject(name));
        activateAsAdmin(upstream); // D-46: a cause-less submission needs an activation (note 109)
        return j.buildAndAssertSuccess(upstream);
    }

    private static void clockAt(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static List<ChangeRecord> triggerBlocked(String target) {
        return FileStore.get().listChangeRecords(MONTH).stream()
                .filter(r -> r.getType() == ChangeType.TRIGGER_BLOCKED)
                .filter(r -> target.equals(r.getTarget()))
                .collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + "/" + r.getTarget() + "/" + r.getAt() + "/" + r.getDetail())
                .collect(Collectors.toList()).toString();
    }

    private static List<String> linesWith(String body, String needle) {
        List<String> out = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            if (line.contains(needle)) {
                out.add(line);
            }
        }
        return out;
    }

    private WebResponse get(JenkinsRule.WebClient wc, String path) throws Exception {
        return wc.getPage(new WebRequest(new URL(j.getURL(), path), HttpMethod.GET)).getWebResponse();
    }
}
