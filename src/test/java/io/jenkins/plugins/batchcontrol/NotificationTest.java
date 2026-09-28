package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.changeRunApprovers;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SPEC item 13, D-36: the {@code BatchControlNotifier} extension point. Matrix rows
 * T-13-01 .. T-13-11.
 *
 * <p>A capturing notifier registered with {@code @TestExtension} records every event; rows
 * assert the events, their recipients and their content, and a negative twin in every row
 * asserts an event that must not be sent (a refused action notifies nobody, an expiry notice
 * is not early and is sent once).
 *
 * <p>Time never passes for real: {@code BatchClock} is fixed and moved (matrix note 1) and
 * {@code ExpiryPeriodicWork.doRun()} is invoked directly (matrix note 2); the expiry notices
 * run in that periodic work (frozen contract).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-36/D-37 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    /** Records every event for every row of this class. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    /** T-13-10 only: a notifier that always fails. */
    @TestExtension("t_13_10_failingNotifierDoesNotFailTheAction")
    public static class FailingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            throw new IllegalStateException("notification channel is down (T-13-10)");
        }
    }

    static final CountDownLatch RELEASE = new CountDownLatch(1);

    /** T-13-11 only: a notifier that hangs until the row releases it. */
    @TestExtension("t_13_11_hangingNotifierDoesNotDelayTheAction")
    public static class HangingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            try {
                RELEASE.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1", "u2")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3"));

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2", "a3"));
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        NotificationCapture.clear();
    }

    /**
     * T-13-01: a new run request notifies the designated approvers (the whole set, nobody else)
     * with the request id, the job, the requester, the reason and a link to the request.
     */
    @Test
    public void t_13_01_runRequestCreatedNotifiesTheDesignatedApprovers() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end close & reconcile", "a1", "a2");

        List<NotificationCapture> created = NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        assertEquals(1, created.size(), "exactly one REQUEST_CREATED per request: " + created);
        NotificationCapture n = created.get(0);
        assertEquals("RUN", n.kind);
        assertEquals(id, n.requestId);
        assertEquals("batch-x", n.subject, "the subject of a run request is the job full name");
        assertEquals("u1", n.requester);
        assertEquals("month-end close & reconcile", n.reason);
        assertEquals(set("a1", "a2"), new HashSet<>(n.recipients), "REQUEST_CREATED goes to the designated approvers");
        assertNotNull(n.url);
        assertTrue(n.url.contains("batch-control/requests/" + id), "the link must lead to the request, was " + n.url);

        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVED, id).isEmpty(), "nothing was decided, so no APPROVED may be sent");
    }

    /**
     * T-13-02: a designation change notifies the new set; a refused change (by a user who is
     * not the requester) notifies nobody.
     */
    @Test
    public void t_13_02_approversChangedNotifiesTheNewSet() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");

        assertSuccess(changeRunApprovers(j, "u1", id, "a2", "a3"), "the requester's change");
        List<NotificationCapture> changed = NotificationCapture.await(NotificationEvent.APPROVERS_CHANGED, id);
        assertEquals(1, changed.size());
        assertEquals(set("a2", "a3"), new HashSet<>(changed.get(0).recipients), "APPROVERS_CHANGED goes to the designated (new) set");
        assertEquals("u1", changed.get(0).requester);

        assertClientError(changeRunApprovers(j, "u2", id, "a1"), "a change by u2");
        assertEquals(1, NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVERS_CHANGED, id).size(), "a refused change must not notify");
    }

    /**
     * T-13-03: approval and rejection notify the requester only; a refused decision (by a
     * listed approver outside the set) notifies nobody.
     */
    @Test
    public void t_13_03_decisionsNotifyTheRequester() throws Exception {
        String approvedId = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        assertClientError(decideRun(j, "a3", approvedId, "approve", "stepping in"), "approval by a3 outside the set");
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.APPROVED, approvedId).isEmpty(), "a refused approval must not notify");

        assertSuccess(decideRun(j, "a2", approvedId, "approve", "ok"), "approval by a2");
        List<NotificationCapture> approved = NotificationCapture.await(NotificationEvent.APPROVED, approvedId);
        assertEquals(1, approved.size());
        assertEquals(List.of("u1"), approved.get(0).recipients, "APPROVED goes to the requester");
        assertEquals("RUN", approved.get(0).kind);

        String rejectedId = submitRunOk(j, "u1", job, "second batch", "a1");
        assertSuccess(decideRun(j, "a1", rejectedId, "reject", "wrong date"), "rejection by a1");
        List<NotificationCapture> rejected = NotificationCapture.await(NotificationEvent.REJECTED, rejectedId);
        assertEquals(1, rejected.size());
        assertEquals(List.of("u1"), rejected.get(0).recipients, "REJECTED goes to the requester");
        assertTrue(NotificationCapture.of(NotificationEvent.APPROVED, rejectedId).isEmpty());
        j.waitUntilNoActivity();
    }

    /**
     * T-13-04: change (grant) requests send the same events: REQUEST_CREATED to the set with
     * kind GRANT, the scope as subject and a link to the grant request; APPROVED and REJECTED
     * to the requester.
     */
    @Test
    public void t_13_04_grantRequestEventsAndRecipients() throws Exception {
        String id = submitGrantOk(j, "u1", "JOB", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1", "a2");
        List<NotificationCapture> created = NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        assertEquals(1, created.size());
        NotificationCapture n = created.get(0);
        assertEquals("GRANT", n.kind);
        assertTrue(n.subject != null && n.subject.contains("batch-x"), "the subject of a change request is its scope, was " + n.subject);
        assertEquals("u1", n.requester);
        assertEquals("fix the cron expression", n.reason);
        assertEquals(set("a1", "a2"), new HashSet<>(n.recipients));
        assertTrue(n.url != null && n.url.contains("batch-control/grants/" + id), "the link must lead to the grant request, was " + n.url);

        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval by a1");
        List<NotificationCapture> approved = NotificationCapture.await(NotificationEvent.APPROVED, id);
        assertEquals(List.of("u1"), approved.get(0).recipients);

        String rejectedId = submitGrantOk(j, "u1", "JOB", "batch-x", Arrays.asList("DELETE"), 30,
                "remove the job", null, "a2");
        assertSuccess(decideGrant(j, "a2", rejectedId, "reject", "not now"), "rejection by a2");
        List<NotificationCapture> rejected = NotificationCapture.await(NotificationEvent.REJECTED, rejectedId);
        assertEquals(List.of("u1"), rejected.get(0).recipients);
    }

    /**
     * T-13-05: a PENDING run request whose pendingTimeout ends at T0+60min gets exactly one
     * EXPIRING notice to the requester, not before T0+50min (default notifyBeforeExpiryMinutes
     * = 10), and never a second one, including after the request expired.
     */
    @Test
    public void t_13_05_pendingRunRequestExpiringFiresOnceTenMinutesBefore() throws Exception {
        assertEquals(10, cfg.getNotifyBeforeExpiryMinutes(), "premise: the default notice lead is 10 minutes");
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(49)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).isEmpty(), "11 minutes before the expiry is too early for the notice");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(51)));
        List<NotificationCapture> expiring = NotificationCapture.await(NotificationEvent.EXPIRING, id);
        assertEquals(1, expiring.size());
        assertEquals(List.of("u1"), expiring.get(0).recipients, "EXPIRING goes to the requester");
        assertEquals("RUN", expiring.get(0).kind);

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(55)));
        runExpiryWorkAt(T0.plus(Duration.ofMinutes(61)));
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(id).getStatus(), "fixture: the request expired");
        assertEquals(1, NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).size(), "EXPIRING fires once");
    }

    /** T-13-06: notifyBeforeExpiryMinutes is honoured: with 5, nothing at 9 minutes before, one notice at 4. */
    @Test
    public void t_13_06_noticeLeadFollowsTheGlobalSetting() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.setNotifyBeforeExpiryMinutes(5);
        cfg.save();
        assertEquals(5, BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes(), "premise: the setting took effect");
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(51)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).isEmpty(), "9 minutes before the expiry is too early with a 5 minute lead");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(56)));
        assertEquals(1, NotificationCapture.await(NotificationEvent.EXPIRING, id).size());
    }

    /** T-13-07: a PENDING change request gets one EXPIRING notice too (kind GRANT, to the requester). */
    @Test
    public void t_13_07_pendingGrantRequestExpiringFiresOnce() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        String id = submitGrantOk(j, "u1", "JOB", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(49)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).isEmpty());

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(51)));
        List<NotificationCapture> expiring = NotificationCapture.await(NotificationEvent.EXPIRING, id);
        assertEquals("GRANT", expiring.get(0).kind);
        assertEquals(List.of("u1"), expiring.get(0).recipients);

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(55)));
        assertEquals(1, NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).size(), "EXPIRING fires once");
        assertEquals(RequestStatus.PENDING, GrantRequestService.get().load(id).getStatus(), "fixture: not expired yet");
    }

    /**
     * T-13-08: an active 30-minute change window gets exactly one GRANT_EXPIRING notice to its
     * holder, not before 10 minutes before its end, and never a second one.
     */
    @Test
    public void t_13_08_activeGrantExpiringFiresOnceBeforeTheWindowEnds() throws Exception {
        String id = submitGrantOk(j, "u1", "JOB", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "approval by a1");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "premise: the window is open");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(19)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, id).isEmpty(), "11 minutes before the window ends is too early");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(21)));
        List<NotificationCapture> expiring = NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, id);
        assertEquals(1, expiring.size());
        assertEquals(List.of("u1"), expiring.get(0).recipients, "GRANT_EXPIRING goes to the requester (the window's holder)");
        assertEquals("GRANT", expiring.get(0).kind);

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(25)));
        runExpiryWorkAt(T0.plus(Duration.ofMinutes(29)));
        assertEquals(1, NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, id).size(), "GRANT_EXPIRING fires once");
        assertTrue(NotificationCapture.of(NotificationEvent.EXPIRING, id).isEmpty(), "an APPROVED change request is not a pending request about to expire");
    }

    /**
     * T-13-09: a decided request is not about to expire: an approved-and-executed run request
     * gets no EXPIRING notice when its former pending deadline approaches.
     */
    @Test
    public void t_13_09_decidedRequestGetsNoExpiringNotice() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "approval by a1");
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus(), "premise: executed");

        runExpiryWorkAt(T0.plus(Duration.ofMinutes(51)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).isEmpty(), "an executed request must not be announced as expiring");
    }

    /**
     * T-13-10: a notifier that throws never fails the request action: creation, a designation
     * change, the approval and the approved build all go through.
     */
    @Test
    public void t_13_10_failingNotifierDoesNotFailTheAction() throws Exception {
        assertTrue(ExtensionList.lookup(BatchControlNotifier.class).stream().anyMatch(FailingNotifier.class::isInstance), "premise: the failing notifier is registered");

        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        assertSuccess(changeRunApprovers(j, "u1", id, "a2"), "the designation change");
        assertSuccess(decideRun(j, "a2", id, "approve", "ok"), "the approval");
        j.waitUntilNoActivity();

        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id).getStatus());
        assertEquals(1, job.getBuilds().size(), "the approved build must run despite the failing notifier");

        String grantId = submitGrantOk(j, "u1", "JOB", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a1");
        assertSuccess(decideGrant(j, "a1", grantId, "approve", "ok"), "the grant approval");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE));
        runExpiryWorkAt(T0.plus(Duration.ofMinutes(21))); // must not throw either
    }

    /**
     * T-13-11: a notifier that hangs never delays the request action: creation and approval
     * return while the notifier is still blocked.
     */
    @Test
    public void t_13_11_hangingNotifierDoesNotDelayTheAction() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> created = executor.submit(() -> submitRunOk(j, "u1", job, "month-end batch", "a1"));
            String id = within(created, "the submission");
            Future<?> approved = executor.submit(() -> {
                assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the approval");
                return null;
            });
            within(approved, "the approval");
            assertTrue(RELEASE.getCount() > 0, "premise: the notifier was still blocked while the actions returned");
            j.waitUntilNoActivity();
            assertEquals(1, job.getBuilds().size(), "the approved build must run while the notifier hangs");
        } finally {
            RELEASE.countDown();
            executor.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static <T> T within(Future<T> future, String what) throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail(what + " did not return within 20 s while a notifier was blocked: the notifier delays the action");
            return null;
        }
    }

    private static void runExpiryWorkAt(Instant instant) throws Exception {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
