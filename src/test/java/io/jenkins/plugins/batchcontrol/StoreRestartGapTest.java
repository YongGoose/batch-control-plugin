package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, the restart rows of scenarios L3-05, L3-06, L3-12, L3-19 and L3-24: "changed under a
 * grant" while grant writes fail, the review surface after a restart with an unreadable change log, a
 * corrupt activation state file, a blocking notifier across a restart, and a cancelled approved run whose
 * cancellation could not be written. Matrix rows T-GAP-317, T-GAP-318, T-GAP-322, T-GAP-343, T-GAP-370 and
 * T-GAP-384 (note 279).
 *
 * <p>Basis: SPEC 2 lines 46-47 (D-58a, D-58b: the guard on changed items; "The state ends only through the
 * explicit 'Mark as reviewed' action ... which writes a GUARD_REVIEWED record"); ARCHITECTURE 5
 * ("changedItems ... loses an item when it is deleted or reviewed"); D-75 (2); LIMITATIONS 11 (failed
 * grant writes retried by the periodic work) and 35; SPEC 6 usability; SPEC 6a ("activation state is
 * truthful and fails closed"; refused unattended submissions write TRIGGER_BLOCKED naming the switch, #21);
 * SPEC 13 (D-36: "A notifier failure never fails or delays the request action"; recipients); SPEC 4 restart
 * durability; SPEC 5 (D-74) "the approved run's queue item is cancelled (the request stays APPROVED and is
 * never submitted again)" and LIMITATIONS 32 ("never submits that run again, not when Jenkins restarts
 * either").
 *
 * <p>Time moves through {@link BatchClock} only. Fault injection: chmod (skipped where this process can
 * still read or write), a hand-edited state file, a notifier of the test that blocks. Restored in
 * {@code finally}.
 *
 * <p>Batch Control matrix strategy; u1, u2 hold RequestGrant; r holds BatchControl/Request; carol holds
 * native Item/Configure (not an administrator); a1 approves.
 *
 * <p>Written from docs/SPEC.md items 2, 4, 5, 6, 6a and 13, docs/DECISIONS.md D-36, D-58a, D-58b, D-74 and
 * D-75, docs/LIMITATIONS.md items 11, 32 and 35 and docs/ARCHITECTURE.md sections 4 and 5 only (no src/main
 * knowledge).
 */
public class StoreRestartGapTest {

    private static final Instant T0 = Instant.parse("2026-10-06T09:00:30Z");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private Path home;
    private final Map<String, String> ids = new HashMap<>();

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        Blocking.release();
    }

    // ------------------------------------------------------------------ L3-05

    /**
     * T-GAP-317 (L3-05; D-58a (1) and (5), ARCHITECTURE 5, LIMITATIONS 11 retry, SPEC 4): session 1 at T0:
     * u1's 30-minute CONFIGURE window on {@code gj}, u2's on {@code gk}. u2 saves {@code gk} while writes
     * succeed (the guard); u1 saves {@code gj} while the grants directory refuses writes. At T0 + 31 min
     * (both windows ended, so neither item is guarded by an active window) carol's widening of each is
     * reverted with a GRANT_VIOLATION: both are changed under a grant at once. Writes are allowed again and
     * the periodic work runs. Session 2 (restart): the monitor still lists {@code gj} and {@code gk}, and
     * carol's widening of each is still reverted.
     */
    @Test
    public void t_gap_317_changedStateWrittenWhileGrantWritesFailSurvivesARestart() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            FreeStyleProject gj = r.jenkins.createProject(FreeStyleProject.class, "gj");
            FreeStyleProject gk = r.jenkins.createProject(FreeStyleProject.class, "gk");
            window("u1", "gj");
            window("u2", "gk");
            saveAs(r, "u2", gk, "edited by u2");
            Path grants = home.resolve("batch-control/grants");
            try {
                assertTrue(grants.toFile().setWritable(false, false), "fixture: grants/ made read-only");
                Assumptions.assumeTrue(writesRefused(grants), "the file system does not refuse writes to a read-only directory for this process");
                saveAs(r, "u1", gj, "edited by u1");
                BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
                assertFalse(can("u1", gj, Item.CONFIGURE), "premise: u1's window has ended");
                assertReverted(r, "gj", "zed", "D-58a (1): gj, saved under the window while grant writes failed, is changed at once");
            } finally {
                grants.toFile().setWritable(true, false);
            }
            assertReverted(r, "gk", "zed", "guard: gk, saved while writes succeeded, is changed");
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        });
        session.then(r -> {
            List<String> listed = WindowStoreFaultGapTest.monitorItems(r);
            assertTrue(listed.contains("gk"), "guard (SPEC 4): gk is still listed after the restart: " + listed);
            assertTrue(listed.contains("gj"), "SPEC 4, LIMITATIONS 11: gj's changed state, written after the retry, is still listed: " + listed);
            assertReverted(r, "gj", "yan", "after the restart carol's widening of gj is still reverted");
            assertReverted(r, "gk", "yan", "guard: and of gk");
        });
    }

    /**
     * T-GAP-318 (L3-05; D-58b (3), ARCHITECTURE 5; expected red at head, finding F-2): session 1: u1's
     * window on {@code gr} ended after u1 saved {@code gr} (premise: listed as changed). With the grants
     * directory refusing writes, the administrator marks {@code gr} reviewed on the monitor. The record and
     * the state agree: either {@code gr} is no longer listed and a GUARD_REVIEWED record by the administrator
     * says an entry was cleared, or it is still listed and no GUARD_REVIEWED record claims a clear. Writes are
     * allowed again and the periodic work runs; session 2 (restart): the same agreement holds between the
     * GUARD_REVIEWED records and the monitor.
     */
    @Test
    public void t_gap_318_reviewWhileGrantWritesFailAgreesWithTheStateAcrossARestart() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            FreeStyleProject gr = r.jenkins.createProject(FreeStyleProject.class, "gr");
            window("u1", "gr");
            saveAs(r, "u1", gr, "edited by u1");
            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
            assertTrue(WindowStoreFaultGapTest.monitorItems(r).contains("gr"), "premise: gr is listed as changed");
            Path grants = home.resolve("batch-control/grants");
            try {
                assertTrue(grants.toFile().setWritable(false, false), "fixture: grants/ made read-only");
                try (Stream<Path> files = Files.list(grants)) {
                    files.forEach(p -> p.toFile().setWritable(false, false));
                }
                Assumptions.assumeTrue(writesRefused(grants), "the file system does not refuse writes to a read-only directory for this process");
                int code = markReviewed(r, "gr");
                System.out.println("T-GAP-318 observation: the review answered HTTP " + code);
                assertTrue(code < 500, "the review answers without a server error");
                assertAgreement(r, "in the same session, grant writes still refused");
            } finally {
                grants.toFile().setWritable(true, false);
                try (Stream<Path> files = Files.list(grants)) {
                    files.forEach(p -> p.toFile().setWritable(true, false));
                }
            }
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            assertAgreement(r, "after writes were allowed again and the periodic work ran");
        });
        session.then(r -> assertAgreement(r, "after the restart"));
    }

    // ------------------------------------------------------------------ L3-06 (u)

    /**
     * T-GAP-322 (L3-06 (u); SPEC 6 usability, D-58c): session 1: folder {@code F} with Pipeline {@code F/P};
     * u1 replays #1 under a CONFIGURE window, so the monitor lists the marked run (premise). Between the
     * sessions every change month file is made unreadable. Session 2: Manage Jenkins renders for the
     * administrator (200, no crash page); the monitor may list fewer runs. Skipped where this process can
     * still read the files.
     */
    @Test
    public void t_gap_322_manageJenkinsRendersWhenTheChangeLogCannotBeRead() throws Throwable {
        session.then(r -> {
            prepare(r, false);
            Folder f = r.jenkins.createProject(Folder.class, "F");
            WorkflowJob p = f.createProject(WorkflowJob.class, "P");
            p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
            r.buildAndAssertSuccess(p);
            window("u1", "F/P");
            try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
                p.getBuildByNumber(1).getAction(ReplayAction.class).run("echo 'replayed'", new LinkedHashMap<>());
            }
            r.waitUntilNoActivity();
            WebResponse manage = ApproverFormFixtures.get(r, "admin", "manage/");
            assertTrue(manage.getContentAsString().contains("job/F/job/P/2/"), "premise: the monitor lists the marked run F/P#2");
        });
        List<Path> months = monthFiles();
        try {
            for (Path month : months) {
                assertTrue(month.toFile().setReadable(false, false), "fixture: " + month + " made unreadable");
            }
            Assumptions.assumeTrue(months.stream().noneMatch(Files::isReadable), "the file system does not refuse reads for this process");
            session.then(r -> {
                WebResponse manage = ApproverFormFixtures.get(r, "admin", "manage/");
                System.out.println("T-GAP-322 observation: Manage Jenkins answered HTTP " + manage.getStatusCode() + ", lists F/P#2 = "
                        + manage.getContentAsString().contains("job/F/job/P/2/"));
                assertEquals(200, manage.getStatusCode(), "SPEC 6: Manage Jenkins renders: " + excerpt(manage.getContentAsString()));
                UsabilityFixtures.assertPlainRefusal("Manage Jenkins", manage.getContentAsString(), null);
            });
        } finally {
            for (Path month : months) {
                month.toFile().setReadable(true, false);
            }
        }
    }

    // ------------------------------------------------------------------ L3-12 (restart)

    /**
     * T-GAP-343 (L3-12; SPEC 6a "activation state is truthful and fails closed", #21): session 1: run
     * control on; jobs {@code ak} and {@code al} (switches cleared) are activated (premise: their timers
     * pass). Between the sessions {@code activations/ak.xml} is overwritten with text that is not XML.
     * Session 2: {@code ak} is not activated: its timer submission is refused (empty queue, unchanged next
     * build number, no build), a TRIGGER_BLOCKED record names {@code ak}, TIMER and activation, and its page
     * says it is not activated. Guard: {@code al}'s timer still runs.
     */
    @Test
    public void t_gap_343_corruptActivationStateFailsClosedAfterARestart() throws Throwable {
        session.then(r -> {
            prepare(r, true);
            for (String name : new String[] {"ak", "al"}) {
                FreeStyleProject job = r.jenkins.createProject(FreeStyleProject.class, name);
                BatchControlJobProperty cleared = new BatchControlJobProperty(true);
                cleared.setBlockTimer(false);
                cleared.setBlockUpstream(false);
                setBatchControl(job, cleared);
                BatchControlFixtures.activate(job, "r", "a1");
            }
        });
        Path state = home.resolve("batch-control/activations/ak.xml");
        assertTrue(Files.isRegularFile(state), "premise (ARCHITECTURE 5, a top-level name is not encoded): the state of ak is " + state);
        Files.writeString(state, "this is not an activation state <<<", StandardCharsets.UTF_8);
        session.then(r -> {
            FreeStyleProject ak = (FreeStyleProject) r.jenkins.getItemByFullName("ak");
            assertFalse(ActivationService.get().isActivated(ak), "SPEC 6a: a corrupt state file fails closed");
            int next = ak.getNextBuildNumber();
            assertNull(ak.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "SPEC 6a: ak's timer is refused");
            ActivationFixtures.assertBlocked(r, ak, next, 0);
            List<ChangeRecord> blocked = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, "ak");
            assertTrue(blocked.stream().anyMatch(rec -> String.valueOf(rec.getDetail()).contains("TIMER")
                            && String.valueOf(rec.getDetail()).toLowerCase(Locale.ROOT).contains("activation")),
                    "#21: a TRIGGER_BLOCKED record names TIMER and activation: "
                            + blocked.stream().map(ChangeRecord::getDetail).collect(Collectors.toList()));
            String page = ApproverFormFixtures.get(r, "r", ak.getUrl()).getContentAsString().toLowerCase(Locale.ROOT);
            assertTrue(page.contains("not activated") || page.contains("on hold"), "SPEC 6a: ak's page says it is not activated");
            FreeStyleProject al = (FreeStyleProject) r.jenkins.getItemByFullName("al");
            assertNotNull(al.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "guard: al's timer passes");
            r.waitUntilNoActivity();
            assertNotNull(al.getBuildByNumber(1), "guard: al's timer run exists");
        });
    }

    // ------------------------------------------------------------------ L3-19 (restart)

    /** Blocks on its first delivery until interrupted, then restores the interrupt flag; records every call. */
    @TestExtension("t_gap_370_blockedNotifierDoesNotHoldUpARestart")
    public static final class Blocking extends BatchControlNotifier {
        static final List<String> SEEN = Collections.synchronizedList(new ArrayList<>());
        static volatile boolean blockNext = true;
        private static final Object LOCK = new Object();

        @Override
        public void notify(NotificationEvent event, Notification notification) {
            SEEN.add(event + ":" + notification.getRequestId());
            if (blockNext) {
                blockNext = false;
                synchronized (LOCK) {
                    try {
                        LOCK.wait(TimeUnit.MINUTES.toMillis(10));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        static void release() {
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
        }
    }

    /** Records every call. */
    @TestExtension("t_gap_370_blockedNotifierDoesNotHoldUpARestart")
    public static final class Recording extends BatchControlNotifier {
        static final List<String> SEEN = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void notify(NotificationEvent event, Notification notification) {
            SEEN.add(event + ":" + notification.getRequestId());
        }
    }

    /**
     * T-GAP-370 (L3-19; SPEC 13 D-36 "A notifier failure never fails or delays the request action", SPEC 4):
     * two notifiers of the test; the first blocks on its first delivery until it is interrupted. Session 1:
     * r creates three run requests (each creation returns; premise). The restart completes (session 2
     * starts within the test's time limit), and in session 2 a new request's REQUEST_CREATED reaches both
     * notifiers.
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    public void t_gap_370_blockedNotifierDoesNotHoldUpARestart() throws Throwable {
        Blocking.SEEN.clear();
        Recording.SEEN.clear();
        Blocking.blockNext = true;
        session.then(r -> {
            prepare(r, true);
            FreeStyleProject job = r.jenkins.createProject(FreeStyleProject.class, "nb");
            setBatchControl(job, new BatchControlJobProperty(true));
            for (int i = 1; i <= 3; i++) {
                int n = i;
                long start = System.nanoTime();
                RunRequest created = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "request " + n, "a1"));
                assertNotNull(created, "premise: request " + n + " is created");
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30), "D-36: a blocked notifier does not delay the creation");
            }
        });
        session.then(r -> {
            FreeStyleProject job = (FreeStyleProject) r.jenkins.getItemByFullName("nb");
            RunRequest fresh = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "after the restart", "a1"));
            String key = NotificationEvent.REQUEST_CREATED + ":" + fresh.getId();
            long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && !(Blocking.SEEN.contains(key) && Recording.SEEN.contains(key))) {
                Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
            }
            assertTrue(Recording.SEEN.contains(key), "SPEC 13: after the restart REQUEST_CREATED reaches the second notifier: " + Recording.SEEN);
            assertTrue(Blocking.SEEN.contains(key), "SPEC 13: and the first: " + Blocking.SEEN);
        });
    }

    // ------------------------------------------------------------------ L3-24 (restart)

    /**
     * T-GAP-384 (L3-24; SPEC 5 D-74, LIMITATIONS 32 "never submits that run again, not when Jenkins restarts
     * either"): session 1: the approval-required job {@code qj} is restricted to a label no agent has; r's
     * request is approved and its run waits in the queue. The {@code requests/run/} directory refuses writes
     * while the queue item is cancelled: the cancel succeeds without an error. Writes are allowed again and
     * the periodic work runs; within ten seconds no queue item of {@code qj} appears and the request is not
     * EXECUTED. Session 2 (restart): within ten seconds of the start no queue item of {@code qj} appears, no
     * build of {@code qj} exists, and the request is not EXECUTED. (A bounded look at the queue, because a
     * resubmitted run of a job bound to a label no agent has would never leave it.)
     */
    @Test
    public void t_gap_384_cancelledApprovedRunIsNotResubmittedAfterARestart() throws Throwable {
        session.then(r -> {
            prepare(r, true);
            FreeStyleProject job = r.jenkins.createProject(FreeStyleProject.class, "qj");
            setBatchControl(job, new BatchControlJobProperty(true));
            job.setAssignedLabel(r.jenkins.getLabel("l3-nowhere"));
            RunRequest request = as("r", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "label-bound", "a1"));
            ids.put("req", request.getId());
            as("a1", () -> {
                RunRequestService.get().approve(request.getId(), "ok");
                return null;
            });
            assertNotNull(r.jenkins.getQueue().getItem(job), "premise: the approved run waits in the queue");
            Path dir = home.resolve("batch-control/requests/run");
            try {
                assertTrue(dir.toFile().setWritable(false, false), "fixture: requests/run/ made read-only");
                Assumptions.assumeTrue(writesRefused(dir), "the file system does not refuse writes to a read-only directory for this process");
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    assertNotNull(r.jenkins.getQueue().getItem(job), "premise: the run still waits in the queue");
                    assertTrue(r.jenkins.getQueue().cancel(job), "the cancel succeeds without an error");
                }
            } finally {
                dir.toFile().setWritable(true, false);
            }
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            assertFalse(queuedWithin(r, job), "LIMITATIONS 32: after writes are allowed again and the periodic work ran, nothing waits for qj");
            assertFalse(RunRequestService.get().load(request.getId()).getStatus() == RequestStatus.EXECUTED, "the request is not EXECUTED");
        });
        session.then(r -> {
            FreeStyleProject job = (FreeStyleProject) r.jenkins.getItemByFullName("qj");
            // The job is bound to a label no agent has, so a resubmitted run would wait forever: look for it in the queue
            // for a bounded time instead of waiting for the queue to drain (a wait that would never end).
            boolean resubmitted = queuedWithin(r, job);
            try {
                assertFalse(resubmitted, "LIMITATIONS 32: after the restart the cancelled run is not submitted again");
                assertTrue(job.getBuilds().isEmpty(), "no build of qj exists");
                assertFalse(RunRequestService.get().load(ids.get("req")).getStatus() == RequestStatus.EXECUTED, "the request is not EXECUTED");
            } finally {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    r.jenkins.getQueue().cancel(job); // leave no item that can never run behind the test
                }
            }
        });
    }

    /** True if a queue item of {@code job} is present now or appears within ten seconds (queue maintained each poll). */
    private static boolean queuedWithin(JenkinsRule r, FreeStyleProject job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        do {
            r.jenkins.getQueue().maintain();
            if (r.jenkins.getQueue().getItem(job) != null) {
                return true;
            }
            Thread.sleep(200); // polling for an asynchronous submission, not waiting for an expiry
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private void prepare(JenkinsRule r, boolean runControl) throws Exception {
        home = r.jenkins.getRootDir().toPath();
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "r", "a1", "carol"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("r"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setRunControlEnabled(runControl);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    private String window(String user, String fullName) throws Exception {
        GrantRequest request = as(user, () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                List.of(GrantAction.CONFIGURE), 30, "work on " + fullName, "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval opens a window");
        return grant.getId();
    }

    private static void saveAs(JenkinsRule r, String user, FreeStyleProject job, String description) throws Exception {
        assertTrue(postXml(r, user, job, StoreFaultGapTest.withDescription(job.getConfigFile().asString(), description)) < 400,
                "fixture: " + user + " saves " + job.getFullName());
    }

    private static int postXml(JenkinsRule r, String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(r, user);
        WebRequest post = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(xml);
        return wc.getPage(post).getWebResponse().getStatusCode();
    }

    /** carol adds an authorization property giving {@code sid} Item/Configure; asserts it is reverted with one GRANT_VIOLATION. */
    private static void assertReverted(JenkinsRule r, String name, String sid, String what) throws Exception {
        FreeStyleProject job = (FreeStyleProject) r.jenkins.getItemByFullName(name);
        int violations = ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).size();
        String xml = job.getConfigFile().asString();
        String property = "<hudson.security.AuthorizationMatrixProperty>"
                + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                + "<permission>USER:hudson.model.Item.Configure:" + sid + "</permission></hudson.security.AuthorizationMatrixProperty>";
        String widened = xml.contains("<properties/>") ? xml.replace("<properties/>", "<properties>" + property + "</properties>")
                : xml.replaceFirst("<properties>", "<properties>" + property);
        assertFalse(widened.equals(xml), "fixture: the widening changes config.xml");
        postXml(r, "carol", job, widened);
        AuthorizationMatrixProperty amp = ((FreeStyleProject) r.jenkins.getItemByFullName(name)).getProperty(AuthorizationMatrixProperty.class);
        boolean kept = amp != null && amp.getGrantedPermissionEntries().values().stream().anyMatch(set -> set.stream().anyMatch(pe -> sid.equals(pe.getSid())));
        assertFalse(kept, what + ": carol's widening of " + name + " is reverted");
        assertEquals(violations + 1, ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).size(), what + ": recorded as GRANT_VIOLATION");
    }

    private static int markReviewed(JenkinsRule r, String item) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(r, "admin");
        URL url = new URL(wc.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed").toExternalForm()
                + "&item=" + java.net.URLEncoder.encode(item, StandardCharsets.UTF_8));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    /** The GUARD_REVIEWED records and the monitor agree about {@code gr} (see T-GAP-318). */
    private static void assertAgreement(JenkinsRule r, String when) throws Exception {
        List<String> listed = WindowStoreFaultGapTest.monitorItems(r);
        List<ChangeRecord> reviews = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED);
        boolean claimed = reviews.stream().anyMatch(rec -> WindowStoreFaultGapTest.claimsClear(rec.getDetail()));
        System.out.println("T-GAP-318 observation (" + when + "): listed " + listed + ", records " + WindowStateFixtures.describe(reviews));
        if (listed.contains("gr")) {
            assertFalse(claimed, "D-58b (3) " + when + ": gr is still listed, so no GUARD_REVIEWED record may claim an entry was cleared: "
                    + WindowStateFixtures.describe(reviews));
        } else {
            assertTrue(claimed && reviews.stream().anyMatch(rec -> "admin".equals(rec.getUser())),
                    "D-58b (3) " + when + ": gr is no longer listed, so a GUARD_REVIEWED record by admin says an entry was cleared: "
                            + WindowStateFixtures.describe(reviews));
        }
    }

    private List<Path> monthFiles() throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("batch-control/changes"))) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}\\.jsonl")).sorted().toList();
        }
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

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String user, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return body.run();
        }
    }
}
