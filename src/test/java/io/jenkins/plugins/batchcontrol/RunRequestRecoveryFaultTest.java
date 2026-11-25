package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import io.jenkins.plugins.batchcontrol.store.StoreWriteException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.uncontrolled;
import static io.jenkins.plugins.batchcontrol.PluginInteractionFixtures.as;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #163: startup recovery of approved run requests when it runs before Jenkins core restores the
 * build queue, and recovering one request fails. Matrix rows T-04-76 and T-04-77 (note 376).
 *
 * <p>Basis: SPEC item 4 (a request approved but not yet queued when the controller stops is queued
 * automatically after the restart, with no double submission), item 7 (the approved-run timeout is
 * judged from the recovery time after a restart), item 5 (a failed save leaves nothing behind) and
 * ARCHITECTURE section 1 (Batch Control controls only by refusing through extension points or by a
 * temporary permission; it does not touch Jenkins core). No SPEC acceptance criterion names the queue
 * items of other jobs; the row rests on ARCHITECTURE 1 for them (note 376).
 *
 * <p><b>The order of the two start-up steps.</b> ARCHITECTURE section 2 makes the restart recovery an
 * {@code @Initializer(after = InitMilestone.JOB_CONFIG_ADAPTED)} in {@code ops.StartupRecovery}
 * (section 3). Core restores the queue in {@link Queue#init(Jenkins)}, an initializer with the same
 * requirement, so the reactor may run either first. The rows replay a restart in the order where
 * Batch Control's step runs first: the queue is written to {@code queue.xml} with core's
 * {@link Queue#save()}, the in-memory queue is emptied with core's own {@link Queue#load()} while
 * {@code queue.xml} is set aside (no queue listener fires, so nothing is cancelled), then the plugin's
 * initializer is called the way core's init reactor calls one (as SYSTEM, static, its parameters
 * looked up like core's {@code TaskMethodFinder}), then {@link Queue#init(Jenkins)}. Approved requests
 * that were never queued come from {@link QueueRefusalFixtures} (note 265); the built-in node has no
 * executor during the replay, so nothing starts before core's step, as in a real start-up.
 *
 * <p><b>The failing store.</b> The fault must refuse the writes of one request only, so that the other
 * request shows whether it is still recovered, and only from the moment that request's run is
 * submitted to the queue (the claim), as in the issue. The read-only {@code requests/run/} directory
 * of note 291 refuses every request's write, and the store seam of security-24 S-24-04 replaces only
 * the store of {@code BlockedAttemptAudit}, so neither serves. The rows use the store's own refusal
 * pinned by T-04-30: the store refuses ({@link StoreWriteException}) to save a request holding a
 * character XML 1.0 cannot store. A test queue handler consulted before Batch Control's gate puts a
 * U+FFFE into the first request's stored reason when the recovery submits that request's run; from
 * then on the request still loads and every save of it is refused (premises asserted). The other
 * request is untouched.
 *
 * <p>Written from docs/SPEC.md, docs/ARCHITECTURE.md sections 1, 2, 3 and 5, docs/TEST-MATRIX.md and
 * issue #163's behaviour description only (no src/main knowledge).
 */
@Tag("core")
public class RunRequestRecoveryFaultTest {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    /** The controller was down longer than approvedRunTimeoutMinutes (60). */
    private static final Instant RECOVERY = T0.plus(Duration.ofMinutes(61));
    private static final Instant NEXT_START = T0.plus(Duration.ofMinutes(70));

    private static final String UNRELATED = "rqf-unrelated";
    private static final String FIRST = "rqf-first";
    private static final String SECOND = "rqf-second";
    private static final String FIRST_REASON = "recovery fault first request";
    private static final String SECOND_REASON = "recovery fault second request";
    /** A character XML 1.0 cannot store (T-04-30), put between "first" and "request". */
    private static final char UNSTORABLE = '\uFFFE';
    private static final String FIRST_REASON_FAULTED = FIRST_REASON.replace("first request", "first" + UNSTORABLE + "request");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures, note 265). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    /**
     * Puts the fault into the first request's stored file when a run of {@link #FIRST} is submitted
     * to the queue, once armed. It is consulted before Batch Control's queue gate (a nested class of a
     * test in this package sorts before the gate's sub-package, as {@link QueueRefusalFixtures}
     * relies on), so the request's writes up to its submission succeed and every write from the
     * claim on is refused. It lets every submission through.
     */
    @TestExtension
    public static final class FaultWhenSubmitted extends Queue.QueueDecisionHandler {
        static final AtomicReference<Path> ARMED = new AtomicReference<>();
        static final AtomicReference<byte[]> FAULTED = new AtomicReference<>();
        static final AtomicInteger FIRED = new AtomicInteger();

        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            Path file = p instanceof Item item && FIRST.equals(item.getFullName()) ? ARMED.getAndSet(null) : null;
            if (file != null) {
                FIRED.incrementAndGet();
                try {
                    String xml = Files.readString(file, StandardCharsets.UTF_8);
                    Files.writeString(file, xml.replace(FIRST_REASON, FIRST_REASON_FAULTED), StandardCharsets.UTF_8);
                    FAULTED.set(Files.readAllBytes(file));
                } catch (IOException e) {
                    throw new UncheckedIOException("fixture: the fault could not be put in place", e);
                }
            }
            return true;
        }
    }

    @BeforeEach
    public void disarm() {
        FaultWhenSubmitted.ARMED.set(null);
        FaultWhenSubmitted.FAULTED.set(null);
        FaultWhenSubmitted.FIRED.set(0);
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
        FaultWhenSubmitted.ARMED.set(null);
    }

    /**
     * T-04-76: one queued build of a job Batch Control has nothing to do with, two approved requests
     * that were never queued, and a store that refuses every save of the first one from the moment its
     * run is submitted. The restart is replayed with Batch Control's recovery before core's queue load,
     * 61 minutes after the approvals, and the expiry work runs then. After core's queue load: the
     * unrelated build is still queued; the second request is queued exactly once and still APPROVED
     * (judged from the recovery time, not expired); the first request's stored file is as the fault
     * left it and says APPROVED, with at most one queue item and no build; the recovery step threw
     * nothing. With executors back each queued build runs once and the second
     * request is EXECUTED. After a real restart with the first request writable again, it runs exactly
     * once; the second request and the unrelated job are not run again.
     */
    @Test
    public void t_04_76_aFailedRecoveryOfOneRequestKeepsTheQueueAndRecoversTheOthers() throws Throwable {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        AtomicReference<Path> firstFile = new AtomicReference<>();
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            int executors = prepare(r);
            first.set(approvedNeverQueued(r, FIRST, FIRST_REASON));
            second.set(approvedNeverQueued(r, SECOND, SECOND_REASON));
            firstFile.set(requestFile(r, first.get()));
            assertTrue(Files.readString(firstFile.get(), StandardCharsets.UTF_8).contains(FIRST_REASON),
                    "fixture: the stored first request holds its reason as text");
            FaultWhenSubmitted.ARMED.set(firstFile.get());

            Throwable thrown = restartWithRecoveryBeforeTheQueueLoad(r);

            byte[] faulted = assertFaultTookEffect(first.get(), firstFile.get());
            runExpiryWork(); // at the recovery time, 61 minutes after the approvals

            assertEquals(1, queued(r, UNRELATED), "the queued build of " + UNRELATED + ", a job Batch Control has nothing"
                    + " to do with, survives the restart although recovering another request failed: " + describeQueue(r));
            assertEquals(1, queued(r, SECOND), "the second approved request is queued exactly once although recovering the"
                    + " first one failed: " + describeQueue(r));
            assertEquals(RequestStatus.APPROVED, status(second.get()), "the second request waits in the queue, APPROVED: the"
                    + " expiry work at the recovery time did not expire it (judged from the recovery time, not the approval)");
            assertArrayEquals(faulted, Files.readAllBytes(firstFile.get()), "the refused save of the first request left its"
                    + " stored file as it was (a failed save leaves nothing behind)");
            assertStoredStatus(firstFile.get(), "APPROVED", "the first request is still stored as APPROVED, so a later"
                    + " restart can recover it");
            assertTrue(queued(r, FIRST) <= 1, "the first request is queued at most once: " + describeQueue(r));
            assertTrue(job(r, FIRST).getBuilds().isEmpty(), "no build of " + FIRST + " before executors are back");
            assertNull(thrown, "the recovery step completes although recovering one request failed (a failure affects only"
                    + " that request): " + thrown);

            releaseExecutors(r, executors);
            assertBuiltOnce(r, UNRELATED, "after the executors are back");
            assertRanOnce(r, SECOND, second.get(), "after the executors are back");
            assertStoredStatus(requestFile(r, second.get()), "EXECUTED", "the second request's run is stored as executed");
            assertTrue(job(r, FIRST).getBuilds().size() <= 1, "the first request ran at most once");
        });

        // the store accepts the first request's writes again (the character is gone), and the controller restarts
        String stored = Files.readString(firstFile.get(), StandardCharsets.UTF_8);
        assertTrue(stored.contains(FIRST_REASON_FAULTED), "fixture: the stored first request still holds the fault: " + stored);
        Files.writeString(firstFile.get(), stored.replace(FIRST_REASON_FAULTED, FIRST_REASON), StandardCharsets.UTF_8);
        BatchClock.setForTest(Clock.fixed(NEXT_START, ZoneOffset.UTC));

        session.then(r -> {
            r.waitUntilNoActivity();
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            assertRanOnce(r, FIRST, first.get(), "after the next restart (approved, not queued at the restart: queued after it)");
            assertStoredStatus(firstFile.get(), "EXECUTED", "the first request's run is stored as executed");
            assertRanOnce(r, SECOND, second.get(), "after the next restart (no second submission)");
            assertBuiltOnce(r, UNRELATED, "after the next restart");
        });
    }

    /**
     * T-04-77 (guard of T-04-76): the same restart without the fault. After core's queue load and the
     * expiry work at the recovery time, the unrelated build and both approved requests are queued
     * exactly once, both requests APPROVED (not expired 61 minutes after the approval); the recovery
     * step threw nothing. With executors back every job runs exactly once, each approved run carries
     * its request, both requests EXECUTED.
     */
    @Test
    public void t_04_77_recoveryBeforeTheQueueLoadKeepsTheQueueAndQueuesEveryApprovedRequest() throws Throwable {
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            int executors = prepare(r);
            String first = approvedNeverQueued(r, FIRST, FIRST_REASON);
            String second = approvedNeverQueued(r, SECOND, SECOND_REASON);

            Throwable thrown = restartWithRecoveryBeforeTheQueueLoad(r);
            runExpiryWork(); // at the recovery time, 61 minutes after the approvals

            assertEquals(1, queued(r, UNRELATED), "the queued build of " + UNRELATED + " survives the restart: "
                    + describeQueue(r));
            assertEquals(1, queued(r, FIRST), "the first approved request is queued exactly once: " + describeQueue(r));
            assertEquals(1, queued(r, SECOND), "the second approved request is queued exactly once: " + describeQueue(r));
            assertEquals(RequestStatus.APPROVED, status(first), "the first request waits in the queue, APPROVED: the expiry"
                    + " work at the recovery time did not expire it");
            assertEquals(RequestStatus.APPROVED, status(second), "the second request waits in the queue, APPROVED: the expiry"
                    + " work at the recovery time did not expire it");
            assertNull(thrown, "the recovery step completes: " + thrown);

            releaseExecutors(r, executors);
            assertBuiltOnce(r, UNRELATED, "after the executors are back");
            assertRanOnce(r, FIRST, first, "after the executors are back");
            assertRanOnce(r, SECOND, second, "after the executors are back");
        });
    }

    // ---------------------------------------------------------------- the replayed restart

    /**
     * Replays a controller restart in the order where Batch Control's recovery runs before core
     * restores the queue (class comment), with the plugin clock at {@link #RECOVERY}. Returns what
     * the recovery step threw, or null.
     */
    private static Throwable restartWithRecoveryBeforeTheQueueLoad(JenkinsRule r) throws Exception {
        Queue queue = r.jenkins.getQueue();
        Path queueXml = r.jenkins.getRootDir().toPath().resolve("queue.xml");
        Path aside = queueXml.resolveSibling("queue.xml.set-aside-by-test");

        // what a stopping controller leaves behind: its queue in queue.xml
        queue.save();
        assertTrue(Files.readString(queueXml, StandardCharsets.UTF_8).contains(UNRELATED),
                "premise: queue.xml holds the queued build of " + UNRELATED);
        // a starting controller has an empty queue until queue.xml is loaded
        Files.move(queueXml, aside);
        queue.load();
        Files.move(aside, queueXml);
        assertEquals(0, queue.getItems().length, "premise: the in-memory queue is empty, as at start-up: " + describeQueue(r));
        assertTrue(Files.readString(queueXml, StandardCharsets.UTF_8).contains(UNRELATED),
                "premise: queue.xml still holds the queued build of " + UNRELATED);

        BatchClock.setForTest(Clock.fixed(RECOVERY, ZoneOffset.UTC));
        Throwable thrown;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // the init reactor runs as SYSTEM
            thrown = runStartupRecovery(r);
            Queue.init(r.jenkins);
        }
        return thrown;
    }

    /**
     * Calls Batch Control's restart recovery the way core's init reactor calls an initializer: every
     * {@code @Initializer(after = JOB_CONFIG_ADAPTED)} method of {@code ops.StartupRecovery}
     * (ARCHITECTURE sections 2 and 3). Returns what it threw, or null.
     */
    private static Throwable runStartupRecovery(JenkinsRule r) throws Exception {
        Class<?> type = Class.forName("io.jenkins.plugins.batchcontrol.ops.StartupRecovery");
        List<Method> steps = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            Initializer initializer = method.getAnnotation(Initializer.class);
            if (initializer != null && initializer.after() == InitMilestone.JOB_CONFIG_ADAPTED) {
                steps.add(method);
            }
        }
        assertFalse(steps.isEmpty(), "fixture: ARCHITECTURE 2 makes the restart recovery an"
                + " @Initializer(after = JOB_CONFIG_ADAPTED) of ops.StartupRecovery");
        for (Method step : steps) {
            Class<?>[] types = step.getParameterTypes();
            Object[] arguments = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                arguments[i] = lookUp(r, types[i]);
            }
            step.setAccessible(true);
            try {
                step.invoke(Modifier.isStatic(step.getModifiers()) ? null : lookUp(r, type), arguments);
            } catch (InvocationTargetException e) {
                return e.getCause();
            }
        }
        return null;
    }

    /** As core's TaskMethodFinder: the Jenkins instance, otherwise what Jenkins' injector holds. */
    private static Object lookUp(JenkinsRule r, Class<?> type) {
        if (type.isInstance(r.jenkins)) {
            return r.jenkins;
        }
        return r.jenkins.getInjector().getInstance(type);
    }

    // ---------------------------------------------------------------- fixture

    /**
     * Security as in RestartRecoveryTest (persisted across the restart); the built-in node without
     * executors; {@link #UNRELATED} created and queued while run control is still off, with no Batch
     * Control property; then run control on (approver a1, approvedRunTimeoutMinutes 60) and the two
     * approval-required jobs. Returns the executor count to give back.
     */
    private static int prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy strategy = new GlobalMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);

        int executors = r.jenkins.getNumExecutors();
        assertTrue(executors > 0, "fixture: the built-in node has executors to give back");
        r.jenkins.setNumExecutors(0);

        assertFalse(BatchControlGlobalConfiguration.get().isRunControlEnabled(), "premise: run control is off at first");
        FreeStyleProject unrelated = uncontrolled(r.createFreeStyleProject(UNRELATED));
        assertNotNull(unrelated.scheduleBuild2(0, new Cause.UserIdCause("admin")), "fixture: the unrelated build is queued");
        assertEquals(1, queued(r, UNRELATED), "premise: the unrelated build waits in the queue (no executor)");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        for (String name : new String[] {FIRST, SECOND}) {
            setBatchControl(r.createFreeStyleProject(name), new BatchControlJobProperty(true));
        }
        return executors;
    }

    /** u1's request on {@code jobName}, approved by a1 while the queue refuses the run (never queued). */
    private static String approvedNeverQueued(JenkinsRule r, String jobName, String reason) throws Exception {
        FreeStyleProject job = job(r, jobName);
        String id;
        try (ACLContext ignored = as("u1")) {
            id = RunRequestService.get().create(job, new LinkedHashMap<>(), reason, "a1").getId();
        }
        QueueRefusalFixtures.refusedBeforeTheGate(job, () -> {
            try (ACLContext ignored = as("a1")) {
                RunRequestService.get().approve(id, "ok");
            }
        });
        assertEquals(RequestStatus.APPROVED, status(id), "premise: the request on " + jobName + " is APPROVED");
        assertEquals(0, queued(r, jobName), "premise: the approved run of " + jobName + " was never queued");
        assertTrue(job.getBuilds().isEmpty(), "premise: no build of " + jobName);
        return id;
    }

    /**
     * The premises of the fault: it was put in place when the recovery submitted the first request's
     * run (once); the request still loads, APPROVED, with U+FFFE in its reason; the store refuses to
     * save it (T-04-30) and the refused save leaves the file as it is. Returns the file's bytes as the
     * fault left them.
     */
    private static byte[] assertFaultTookEffect(String id, Path file) throws Exception {
        assertEquals(1, FaultWhenSubmitted.FIRED.get(), "fixture: the recovery submitted the first request's run once, and"
                + " the fault was put in place at that moment");
        byte[] faulted = FaultWhenSubmitted.FAULTED.get();
        assertNotNull(faulted, "fixture: the fault is in place");
        assertTrue(new String(faulted, StandardCharsets.UTF_8).contains(FIRST_REASON_FAULTED),
                "fixture: the stored first request holds U+FFFE in its reason");
        RunRequest loaded = RunRequestService.get().load(id);
        assertTrue(loaded.getReason() != null && loaded.getReason().indexOf(UNSTORABLE) >= 0,
                "fixture: the faulted request is read back with U+FFFE in its reason: " + loaded.getReason());
        assertThrows(StoreWriteException.class, () -> FileStore.get().saveRunRequest(loaded),
                "fixture: the store refuses to save the faulted request (T-04-30)");
        assertArrayEquals(faulted, Files.readAllBytes(file), "fixture: a refused save leaves the stored file as it was");
        return faulted;
    }

    /** The per-minute expiry work, run now (as UnsavedExecutionRecoveryTest does). */
    private static void runExpiryWork() throws Exception {
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static void releaseExecutors(JenkinsRule r, int executors) throws Exception {
        r.jenkins.setNumExecutors(executors);
        r.jenkins.getQueue().scheduleMaintenance();
        r.waitUntilNoActivity();
    }

    // ---------------------------------------------------------------- reads and assertions

    private static FreeStyleProject job(JenkinsRule r, String name) {
        FreeStyleProject job = r.jenkins.getItemByFullName(name, FreeStyleProject.class);
        assertNotNull(job, name + " exists");
        return job;
    }

    private static RequestStatus status(String id) {
        return RunRequestService.get().load(id).getStatus();
    }

    private static Path requestFile(JenkinsRule r, String id) {
        Path file = r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("requests").resolve("run")
                .resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the request is stored at the documented location " + file);
        return file;
    }

    /** The number of queue items of the job named {@code name} (read as SYSTEM). */
    private static long queued(JenkinsRule r, String name) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Arrays.stream(r.jenkins.getQueue().getItems())
                    .filter(i -> i.task instanceof Item item && name.equals(item.getFullName()))
                    .count();
        }
    }

    private static String describeQueue(JenkinsRule r) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Arrays.stream(r.jenkins.getQueue().getItems())
                    .map(i -> i.task instanceof Item item ? item.getFullName() : String.valueOf(i.task))
                    .collect(Collectors.joining(", ", "queue [", "]"));
        }
    }

    private static void assertStoredStatus(Path file, String status, String message) throws Exception {
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<status>" + status + "</status>"), message + " (the stored file says " + status + "): " + xml);
    }

    private static void assertBuiltOnce(JenkinsRule r, String name, String when) {
        FreeStyleProject job = job(r, name);
        assertEquals(1, job.getBuilds().size(), name + " built exactly once (" + when + ")");
        assertEquals(2, job.getNextBuildNumber(), name + ": exactly one build number consumed (" + when + ")");
        assertEquals(0, queued(r, name), name + ": nothing left in the queue (" + when + ")");
    }

    private static void assertRanOnce(JenkinsRule r, String name, String id, String when) {
        assertBuiltOnce(r, name, when);
        ApprovedCause cause = job(r, name).getBuildByNumber(1).getCause(ApprovedCause.class);
        assertNotNull(cause, name + ": the run carries the ApprovedCause (" + when + ")");
        assertEquals(id, cause.getRequestId(), name + ": the run carries its request (" + when + ")");
        assertEquals(RequestStatus.EXECUTED, status(id), name + ": the request is EXECUTED (" + when + ")");
    }
}
