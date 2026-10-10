package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4 (the store; "approved but not yet queued at a restart is queued automatically after
 * it, with no double submission") and item 7 (an APPROVED request not queued within
 * {@code approvedRunTimeoutMinutes} becomes EXPIRED, judged from the recovery time after a restart),
 * with the SPEC 4절 state machine {@code PENDING -> APPROVED -> EXECUTED}: a run that started stays
 * EXECUTED although the store could not record it at that moment. Matrix rows T-04-22, T-04-23 and
 * T-04-24 (note 293).
 *
 * <p>The contract was frozen from core-dev's report (the Windows CI defect of note 292): when the
 * approved build starts and the store refuses the EXECUTED write, the state stays in effect in
 * memory ({@code RunRequestService.get().load(id).getStatus()} is EXECUTED), the approved-run
 * expiry does not end the request as "approved run did not start", and
 * {@code RunRequestService.retryUnsavedExecutions()} (called by {@code ExpiryPeriodicWork} every
 * minute) writes it once the store accepts writes again. At startup, an APPROVED request whose
 * build exists on its job is recorded as EXECUTED instead of only being skipped.
 *
 * <p>The refused write is built with the storage-fault fixture of note 291: the documented directory
 * {@code JENKINS_HOME/batch-control/requests/run/} (ARCHITECTURE section 5) is made read-only, so no
 * temporary file can be created or renamed into it; the row is skipped where the platform cannot
 * refuse the owner a write ({@link PlatformFixtures#assumeCanMakeUnwritable()}, Windows or root). The
 * approved run is held in the queue by a label no agent has until the fault is in place, and released
 * by removing the label. The approved-run timeout is moved past with the plugin clock
 * ({@link BatchClock}); the periodic work is run by calling {@link ExpiryPeriodicWork#doRun()}.
 * "What the store holds" is read from the stored file itself ({@code <status>...</status>}, the shape
 * T-GAP-124 relies on), never from memory.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, docs/TEST-MATRIX.md and the frozen
 * contract only (no src/main knowledge).
 */
@Tag("core")
public class UnsavedExecutionRecoveryTest {

    private static final String JOB = "unsaved-exec-j";
    private static final String NOWHERE = "unsaved-exec-no-such-agent";
    private static final Instant T0 = Instant.parse("2026-10-09T09:00:00Z");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /** Records every notification, so that an EXPIRED notice for the request would be seen. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    @BeforeEach
    public void clearNotifications() {
        NotificationCapture.clear();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-04-22: the approved build starts while {@code requests/run/} refuses writes -> the build
     * runs once, the request is EXECUTED in memory while its stored file still says APPROVED
     * (premise: the write was refused); past the approved-run timeout the periodic work, run while
     * writes are still refused, throws nothing and does not expire it; once writes are accepted the
     * periodic work stores EXECUTED, with no EXPIRED status, no "not start" text and no EXPIRED
     * notice. Guards: one build, next build number 2, nothing queued after every step; a second
     * approval is refused; after a restart the request is still EXECUTED on disk and in memory and
     * the job still has one build.
     */
    @Test
    public void t_04_22_executedStateSurvivesARefusedWriteAndThePeriodicWorkStoresIt() throws Throwable {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Path> file = new AtomicReference<>();
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            FreeStyleProject job = prepare(r);
            id.set(approveQueued(r, job));
            file.set(requestFile(r, id.get()));
            assertStoredStatus(file.get(), "APPROVED", "premise: the approval is stored");

            startWhileWritesAreRefused(r, job, file.get(), () -> {
                assertEquals(RequestStatus.EXECUTED, status(id.get()),
                        "the EXECUTED state stays in effect in memory although the store refused to write it");
                assertStoredStatus(file.get(), "APPROVED", "premise: the store refused the EXECUTED write");
                assertOneBuild(r, job, "after the approved run started");

                // the approved-run timeout passes while the store still refuses writes
                BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
                assertDoesNotThrow(UnsavedExecutionRecoveryTest::runExpiryWork,
                        "the periodic work throws nothing while the EXECUTED write is still refused");
                assertEquals(RequestStatus.EXECUTED, status(id.get()),
                        "past the approved-run timeout the request whose run started is still EXECUTED, not EXPIRED");
                assertNotExpiredOnDisk(file.get(), "while writes are refused");
                assertOneBuild(r, job, "after the periodic work ran while writes were refused");
            });

            // writes are accepted again
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(62)), ZoneOffset.UTC));
            runExpiryWork();
            assertStoredStatus(file.get(), "EXECUTED",
                    "the periodic work stores the EXECUTED state once the store accepts writes again");
            assertEquals(RequestStatus.EXECUTED, status(id.get()), "the request is EXECUTED");
            assertNoExpiry(file.get(), id.get(), "after the periodic work stored the EXECUTED state");
            assertOneBuild(r, job, "after the periodic work stored the EXECUTED state");

            // guard: the request is decided once
            assertRefused(() -> approveAsA1(id.get()), "a second approval of the executed request is refused");
            r.waitUntilNoActivity();
            assertEquals(RequestStatus.EXECUTED, status(id.get()), "the request stays EXECUTED after the refused approval");
            assertOneBuild(r, job, "after the refused second approval");
        });
        session.then(r -> {
            secure(r);
            r.waitUntilNoActivity();
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertNotNull(job, "the job survives the restart");
            assertStoredStatus(file.get(), "EXECUTED", "after the restart the stored request still says EXECUTED");
            assertEquals(RequestStatus.EXECUTED, status(id.get()), "after the restart the request is EXECUTED");
            assertOneBuild(r, job, "after the restart");
        });
    }

    /**
     * T-04-23: as T-04-22 up to the refused write; {@code retryUnsavedExecutions()} called while
     * writes are still refused throws nothing, stores nothing and the request stays EXECUTED in
     * memory; once writes are accepted (the stored file still says APPROVED: premise) one call
     * stores EXECUTED. Guards: a further call changes nothing; one build, next build number 2,
     * nothing queued.
     */
    @Test
    public void t_04_23_retryUnsavedExecutionsStoresTheExecutedState() throws Throwable {
        session.then(r -> {
            FreeStyleProject job = prepare(r);
            String id = approveQueued(r, job);
            Path file = requestFile(r, id);
            assertStoredStatus(file, "APPROVED", "premise: the approval is stored");

            startWhileWritesAreRefused(r, job, file, () -> {
                assertEquals(RequestStatus.EXECUTED, status(id),
                        "the EXECUTED state stays in effect in memory although the store refused to write it");
                assertStoredStatus(file, "APPROVED", "premise: the store refused the EXECUTED write");
                assertDoesNotThrow(UnsavedExecutionRecoveryTest::retryUnsavedExecutions,
                        "a retry while the store still refuses writes throws nothing");
                assertEquals(RequestStatus.EXECUTED, status(id), "after a refused retry the request is still EXECUTED in memory");
                assertStoredStatus(file, "APPROVED", "premise: the refused retry stored nothing");
            });

            assertStoredStatus(file, "APPROVED", "premise: nothing has stored the EXECUTED state yet");
            retryUnsavedExecutions();
            assertStoredStatus(file, "EXECUTED", "retryUnsavedExecutions stores the EXECUTED state once writes are accepted");
            assertEquals(RequestStatus.EXECUTED, status(id), "the request is EXECUTED");
            assertOneBuild(r, job, "after the retry");

            // guard: a further retry changes nothing
            retryUnsavedExecutions();
            r.waitUntilNoActivity();
            assertStoredStatus(file, "EXECUTED", "a further retry leaves the stored EXECUTED state");
            assertEquals(RequestStatus.EXECUTED, status(id), "a further retry leaves the request EXECUTED");
            assertOneBuild(r, job, "after a further retry");
        });
    }

    /**
     * T-04-24: an approved request ran (EXECUTED, build #1 carrying the request); while Jenkins is
     * stopped its stored file is rewritten to APPROVED without the executed run id (a lost EXECUTED
     * write) -> after the restart the request is EXECUTED in memory and on disk (startup recovery
     * recorded it), no second build, next build number still 2, nothing queued; past the approved-run
     * timeout (judged from the recovery) the periodic work does not expire it: no EXPIRED status, no
     * "not start" text, no EXPIRED notice, still one build.
     */
    @Test
    public void t_04_24_startupRecoveryRecordsExecutedForARequestWhoseBuildExists() throws Throwable {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<Path> file = new AtomicReference<>();
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            FreeStyleProject job = prepare(r);
            id.set(approveQueued(r, job));
            job.setAssignedLabel(null);
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            FreeStyleBuild build = job.getBuildByNumber(1);
            assertNotNull(build, "premise: the approved run started");
            ApprovedCause cause = build.getCause(ApprovedCause.class);
            assertNotNull(cause, "premise: the build carries the approved request");
            assertEquals(id.get(), cause.getRequestId(), "premise: the build carries this request");
            assertEquals(RequestStatus.EXECUTED, status(id.get()), "premise: the request ran");
            assertOneBuild(r, job, "premise");
            file.set(requestFile(r, id.get()));
            assertStoredStatus(file.get(), "EXECUTED", "premise: the EXECUTED state is stored");
        });

        // a lost EXECUTED write: the stored file goes back to the approval's content
        String xml = Files.readString(file.get(), StandardCharsets.UTF_8);
        String edited = xml.replace("<status>EXECUTED</status>", "<status>APPROVED</status>")
                .replaceAll("(?s)<executedRunId>.*?</executedRunId>", "")
                .replaceAll("<executedRunId\\s*/>", "");
        assertTrue(edited.contains("<status>APPROVED</status>") && !edited.contains("<status>EXECUTED</status>"),
                "fixture: the stored request says APPROVED: " + edited);
        assertFalse(edited.contains("executedRunId"), "fixture: the executed run id is removed: " + edited);
        Files.writeString(file.get(), edited, StandardCharsets.UTF_8);
        Instant recovery = T0.plus(Duration.ofMinutes(5));
        BatchClock.setForTest(Clock.fixed(recovery, ZoneOffset.UTC));

        session.then(r -> {
            secure(r);
            r.waitUntilNoActivity();
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertNotNull(job, "the job survives the restart");
            assertEquals(RequestStatus.EXECUTED, status(id.get()),
                    "startup recovery records EXECUTED for an APPROVED request whose build exists");
            assertStoredStatus(file.get(), "EXECUTED", "startup recovery writes the EXECUTED state");
            assertOneBuild(r, job, "after the restart (no second build for a request whose build exists)");

            // past the approved-run timeout, judged from the recovery
            BatchClock.setForTest(Clock.fixed(recovery.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
            runExpiryWork();
            assertEquals(RequestStatus.EXECUTED, status(id.get()), "past the approved-run timeout the request is still EXECUTED");
            assertStoredStatus(file.get(), "EXECUTED", "past the approved-run timeout the stored request still says EXECUTED");
            assertNoExpiry(file.get(), id.get(), "past the approved-run timeout");
            assertOneBuild(r, job, "after the periodic work past the approved-run timeout");
        });
    }

    // ---------------------------------------------------------------- helpers

    private interface Step {
        void run() throws Exception;
    }

    /**
     * Makes {@code requests/run/} read-only (skipped where the platform cannot refuse the owner a
     * write, note 291), releases the queued approved run, waits until it has finished, runs
     * {@code whileRefused}, and always makes the directory writable again.
     */
    private static void startWhileWritesAreRefused(JenkinsRule r, FreeStyleProject job, Path file, Step whileRefused)
            throws Exception {
        Path dir = file.getParent();
        assertTrue(job.getBuilds().isEmpty(), "premise: no build before the fault is in place");
        assertEquals(1, r.jenkins.getQueue().getItems().length, "premise: the approved run waits in the queue");
        PlatformFixtures.assumeCanMakeUnwritable();
        try {
            assertTrue(dir.toFile().setWritable(false, false), "fixture: requests/run/ made read-only");
            Assumptions.assumeTrue(writesRefused(dir), "the file system does not refuse writes to a read-only directory for this process");
            job.setAssignedLabel(null); // the job's own directory, not the store
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            FreeStyleBuild build = job.getBuildByNumber(1);
            assertNotNull(build, "the approved run starts although the store refuses writes");
            r.assertBuildStatusSuccess(build);
            whileRefused.run();
        } finally {
            dir.toFile().setWritable(true, false);
        }
        assertFalse(writesRefused(dir), "fixture: requests/run/ accepts writes again");
    }

    private static boolean writesRefused(Path dir) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    private static void secure(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
    }

    /** The approval-required job, created before run control is switched on and held on a label no agent has. */
    private static FreeStyleProject prepare(JenkinsRule r) throws Exception {
        secure(r);
        FreeStyleProject job = r.createFreeStyleProject(JOB);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        setBatchControl(job, new BatchControlJobProperty(true));
        job.setAssignedLabel(r.jenkins.getLabel(NOWHERE));
        return job;
    }

    /** u1 requests, a1 approves; the approved run waits in the queue. */
    private static String approveQueued(JenkinsRule r, FreeStyleProject job) {
        String id;
        try (ACLContext ignored = as("u1")) {
            id = RunRequestService.get().create(job, new LinkedHashMap<>(), "unsaved executed state", "a1").getId();
        }
        approveAsA1(id);
        assertEquals(1, r.jenkins.getQueue().getItems().length, "premise: the approved run waits in the queue");
        assertEquals(RequestStatus.APPROVED, status(id), "premise: APPROVED, queued");
        assertTrue(job.getBuilds().isEmpty(), "premise: no build yet");
        return id;
    }

    private static void approveAsA1(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
    }

    private static RequestStatus status(String id) {
        return RunRequestService.get().load(id).getStatus();
    }

    private static void retryUnsavedExecutions() {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // as the periodic work calls it
            RunRequestService.get().retryUnsavedExecutions();
        }
    }

    private static void runExpiryWork() throws Exception {
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static Path requestFile(JenkinsRule r, String id) {
        Path file = r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("requests").resolve("run")
                .resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the request is stored at the documented location " + file);
        return file;
    }

    private static void assertStoredStatus(Path file, String status, String message) throws IOException {
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<status>" + status + "</status>"), message + " (the stored file says " + status + "): " + xml);
    }

    private static void assertNotExpiredOnDisk(Path file, String when) throws IOException {
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(xml.contains("<status>EXPIRED</status>"), "the stored request is not EXPIRED " + when + ": " + xml);
        // "not start" covers both "did not start" and "not started" (the unfixed store wrote
        // "Expired: approved but not started within 60 minutes.")
        assertFalse(xml.toLowerCase(Locale.ROOT).contains("not start"),
                "the stored request carries no \"approved run did not start\" expiry " + when + ": " + xml);
    }

    private static void assertNoExpiry(Path file, String id, String when) throws Exception {
        assertNotExpiredOnDisk(file, when);
        List<NotificationCapture> expired = NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRED, id);
        assertTrue(expired.isEmpty(), "no EXPIRED notice is sent for a request whose run started (" + when + "): " + expired);
    }

    private static void assertOneBuild(JenkinsRule r, FreeStyleProject job, String when) {
        assertEquals(1, job.getBuilds().size(), "the approved run ran exactly once (" + when + ")");
        assertEquals(2, job.getNextBuildNumber(), "exactly one build number consumed (" + when + ")");
        assertTrue(r.jenkins.getQueue().isEmpty(), "nothing is queued (" + when + ")");
    }

    private static void assertRefused(Runnable action, String message) {
        boolean refused = false;
        try {
            action.run();
        } catch (RuntimeException expected) {
            refused = true;
        }
        assertTrue(refused, message);
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
