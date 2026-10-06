package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Incident;
import io.jenkins.plugins.batchcontrol.model.RunRecord;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.IncidentService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage lane 1, scenario L1-12: run record isolation and non-user aborts. Matrix rows
 * T-GAP-149 .. T-GAP-152 (note 276).
 *
 * <p>Basis: SPEC 10 "ABORTED 빌드에 중단 사용자가 표시된다(Jenkins가 기록한 경우)"; SPEC 11 "실행 원인과
 * 무관하게(cron 포함) 해당 결과면 Incident가 생성된다", "연결된 재실행이 SUCCESS면 Incident에
 * resolvedByRunId가 자동 기록된다"; ARCHITECTURE 1 "기록은 이벤트 리스너에서 항상 남긴다" and 2 (run
 * records and incidents both come from {@code RunListener#onFinalized}); SPEC 6a / D-27 (nothing
 * the plugin records may change a build). Faults: a non-empty directory where a month file is
 * expected, and an unwritable incidents directory (skipped where it stays writable).
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 1, 2 and 5 only (no src/main
 * knowledge).
 */
@WithJenkins
public class RecordIsolationGapTest {

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
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST, BatchControlPermissions.VIEW_HISTORY)
                        .everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-149 (L1-12 (T); SPEC 10 "(Jenkins가 기록한 경우)"): a Pipeline build aborted by
     * {@code timeout(time: 1, unit: 'SECONDS') { sleep 10 }} is recorded as ABORTED with no aborting
     * user, and its dashboard row names no user as the aborter. Guard: a build aborted by the user
     * {@code gapaborter} records and shows that user.
     */
    @Test
    public void t_gap_149_timeoutAbortNamesNoUser() throws Exception {
        WorkflowJob timed = pipeline("gap-timeout", "timeout(time: 1, unit: 'SECONDS') { sleep 10 }");
        WorkflowRun timedRun = j.assertBuildStatus(Result.ABORTED, timed.scheduleBuild2(0));
        assertNotNull(timedRun);

        WorkflowJob manual = pipeline("gap-useraborted", "sleep 60");
        QueueTaskFuture<WorkflowRun> future = manual.scheduleBuild2(0);
        assertNotNull(future);
        WorkflowRun run = future.waitForStart();
        long deadline = System.currentTimeMillis() + 10_000;
        while (run.getExecutor() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // waiting for the build to occupy its executor, not for an expiry
        }
        assertNotNull(run.getExecutor(), "fixture: the running build exposes its executor");
        run.getExecutor().interrupt(Result.ABORTED, new CauseOfInterruption.UserInterruption("gapaborter"));
        j.waitForCompletion(run);
        j.assertBuildStatus(Result.ABORTED, run);
        j.waitUntilNoActivity();

        RunRecord timedRecord = record("gap-timeout#1");
        assertEquals("ABORTED", timedRecord.getResult(), "premise: the timeout aborted the build");
        assertNull(timedRecord.getAbortedBy(), "a timeout abort names no aborting user");
        RunRecord manualRecord = record("gap-useraborted#1");
        assertEquals("gapaborter", manualRecord.getAbortedBy(), "guard: a user's abort names that user");

        String dashboard = ApproverFormFixtures.get(j, "u1", "batch-control/dashboard/").getContentAsString();
        String timedRow = row(dashboard, "gap-timeout");
        assertFalse(timedRow.isEmpty(), "the dashboard lists the timed-out build");
        assertFalse(timedRow.contains("gapaborter") || timedRow.toUpperCase(Locale.ROOT).contains("SYSTEM"),
                "the timed-out build's row names no aborting user: " + timedRow);
        assertTrue(row(dashboard, "gap-useraborted").contains("gapaborter"), "guard: the user-aborted row names the user");
    }

    /**
     * T-GAP-150 (L1-12 (F); SPEC 11, ARCHITECTURE 1): {@code runs/<month>.jsonl} is replaced by a
     * non-empty directory, so no run record can be appended. A build that fails still ends FAILURE
     * and its incident is still created.
     */
    @Test
    public void t_gap_150_runRecordFailureDoesNotStopTheIncident() throws Exception {
        Path runs = store().resolve("runs").resolve(StoreDataFixtures.monthName(month) + ".jsonl");
        block(runs);
        try {
            FreeStyleProject job = failing("gap-norun");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.waitUntilNoActivity();
            Incident incident = IncidentService.get().list(month).stream()
                    .filter(i -> "gap-norun#1".equals(i.getRunId())).findFirst().orElse(null);
            assertNotNull(incident, "the incident is created although the run record could not be written");
        } finally {
            unblock(runs);
        }
    }

    /**
     * T-GAP-151 (L1-12 (F); ARCHITECTURE 1): {@code incidents/index/<month>.jsonl} is replaced by a
     * non-empty directory, so the incident cannot be indexed. A build that fails still ends FAILURE
     * and its run record is still written.
     */
    @Test
    public void t_gap_151_incidentIndexFailureDoesNotStopTheRunRecord() throws Exception {
        Path index = store().resolve("incidents").resolve("index").resolve(StoreDataFixtures.monthName(month) + ".jsonl");
        block(index);
        try {
            FreeStyleProject job = failing("gap-noindex");
            j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0));
            j.waitUntilNoActivity();
            RunRecord rec = record("gap-noindex#1");
            assertEquals("FAILURE", rec.getResult(), "the run record is written although the incident index could not be");
        } finally {
            unblock(index);
        }
    }

    /**
     * T-GAP-152 (L1-12 (F); SPEC 11 resolvedByRunId, ARCHITECTURE 1): a failed run's incident gets
     * a rerun request; before the approved rerun runs, the incidents directory and the incident's
     * file are made unwritable, so recording {@code resolvedByRunId} cannot be saved. The rerun
     * succeeds and its run record is still written.
     */
    @Test
    public void t_gap_152_unsavableIncidentDoesNotStopTheRerunRecord() throws Exception {
        FreeStyleProject job = RerunFallbackFixtures.failedFreestyle(j, "gap-rerun-iso");
        Incident incident = RerunFallbackFixtures.incidentFor("gap-rerun-iso#1");
        RunRequest rerun;
        try (ACLContext ignored = as("u1")) {
            rerun = IncidentService.get().rerun(incident.getId(), "a1");
        }
        Path dir = store().resolve("incidents");
        Path file = dir.resolve(incident.getId() + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the incident is stored at " + file);
        Set<PosixFilePermission> dirPerms = posix(dir);
        Set<PosixFilePermission> filePerms = posix(file);
        assumeTrue(dirPerms != null && filePerms != null, "POSIX permissions are needed to make the incident unwritable");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(dir) && !Files.isWritable(file), "the incident must really be unwritable (not as root)");
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().approve(rerun.getId(), "rerun approved");
            }
            j.waitUntilNoActivity();
            j.assertBuildStatusSuccess(job.getBuildByNumber(2));
            RunRecord rec = record("gap-rerun-iso#2");
            assertEquals("SUCCESS", rec.getResult(), "the rerun's run record is written although the incident could not be saved");
        } finally {
            Files.setPosixFilePermissions(dir, dirPerms);
            Files.setPosixFilePermissions(file, filePerms);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private WorkflowJob pipeline(String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        uncontrolled(job);
        BatchControlFixtures.activateAsAdmin(job);
        return job;
    }

    private FreeStyleProject failing(String name) throws Exception {
        FreeStyleProject job = uncontrolled(j.createFreeStyleProject(name));
        job.getBuildersList().add(new FailureBuilder());
        BatchControlFixtures.activateAsAdmin(job);
        return job;
    }

    private RunRecord record(String runId) {
        RunRecord rec = FileStore.get().listRunRecords(month).stream()
                .filter(r -> runId.equals(r.getRunId())).findFirst().orElse(null);
        assertNotNull(rec, "the run record of " + runId + " must be written; stored: "
                + FileStore.get().listRunRecords(month).stream().map(RunRecord::getRunId).collect(Collectors.toList()));
        return rec;
    }

    /** Puts a non-empty directory where the month file {@code file} is expected. */
    private static void block(Path file) throws Exception {
        assertFalse(Files.isRegularFile(file), "fixture: " + file + " must not exist yet");
        Files.createDirectories(file);
        Files.writeString(file.resolve("blocker.txt"), "a directory where a month file is expected");
    }

    private static void unblock(Path dir) throws Exception {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static Set<PosixFilePermission> posix(Path path) {
        try {
            return Files.getPosixFilePermissions(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** The table row (or line) of {@code html} that names {@code job}, or empty. */
    private static String row(String html, String job) {
        int at = html.indexOf(job);
        if (at < 0) {
            return "";
        }
        int start = html.lastIndexOf("<tr", at);
        int end = html.indexOf("</tr>", at);
        if (start < 0 || end < 0) {
            return html.substring(Math.max(0, at - 200), Math.min(html.length(), at + 400));
        }
        return html.substring(start, end);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
