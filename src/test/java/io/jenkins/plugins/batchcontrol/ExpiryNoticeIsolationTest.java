package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-minute expiry work sends each expiry notice on its own: one request or window whose stored
 * file is damaged or unreadable never costs another request or window its notice. Matrix rows
 * T-GAP-401 .. T-GAP-403 (note 280).
 *
 * <p>Basis: SPEC 13 (D-36): "{@code EXPIRING}/{@code GRANT_EXPIRING} fire once,
 * {@code notifyBeforeExpiryMinutes} (global, default 10) before the expiry", for run and change requests
 * and for an active change window, and (SPEC 6a, D-36) for activation requests, which are "decided like a
 * run request ... notifications D-36"; SPEC 4 (the store; "the per-minute expiry work"); LIMITATIONS 11:
 * "When the change request's file is there but cannot be read, no expiry notice is sent for that window";
 * D-75 (1) (the GRANT_EXPIRING notice reads the window's change request for its approved name).
 *
 * <p>A notice that a row expects is awaited ({@link NotificationCapture#await}); a notice that a row
 * forbids, or a count that must stay at one, is read after the quiet period. Time never passes for real:
 * the plugin clock ({@link BatchClock}) is fixed and moved, and {@link ExpiryPeriodicWork#doRun()} is
 * invoked directly (matrix notes 1 and 2).
 *
 * <p>The order in which the expiry work visits windows and requests is not specified. The windows of
 * T-GAP-401 and T-GAP-403 are therefore approved one second apart, in the order A, B, C, so that the
 * faulty one is the oldest and its end the earliest; a row stays meaningful only where the work visits the
 * faulty entry before at least one healthy one, and is never falsely red otherwise.
 *
 * <p>Fault injection only through the store on disk: a request file overwritten with a torn copy of its own
 * XML, and chmod 000 (skipped where this process can still read the file, as under root), restored in
 * {@code finally}.
 *
 * <p>Fixture: change control on (run control too in T-GAP-402), Batch Control matrix strategy; u1
 * (Overall/Read, Item/Read, RequestGrant), r (Overall/Read, Item/Read, BatchControl/Request), a1
 * (Overall/Read, Item/Read, Approve; the only approver), admin.
 *
 * <p>Written from docs/SPEC.md items 4, 6a, 8 and 13, docs/DECISIONS.md D-36 and D-75, docs/LIMITATIONS.md
 * item 11 and docs/ARCHITECTURE.md section 5 (store layout) only (no src/main knowledge).
 */
@WithJenkins
public class ExpiryNoticeIsolationTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    private final List<Path> restore = new ArrayList<>();

    /** Records every notification of this class's rows. */
    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    /** A window: the id of its change request and of the window itself, and its item. */
    private record Window(String requestId, String grantId, String fullName) {
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        at(T0);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "r", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("r"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setNotifyBeforeExpiryMinutes(10);
        cfg.save();
        assertEquals(10, BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes(), "premise: the notice lead is 10 minutes");
    }

    @AfterEach
    public void tearDown() throws IOException {
        for (Path p : restore) {
            if (Files.exists(p)) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r--r--"));
            }
        }
        restore.clear();
        BatchClock.reset();
        NotificationCapture.clear();
    }

    /**
     * T-GAP-401 (SPEC 13 GRANT_EXPIRING "fire[s] once"; LIMITATIONS 11 "When the change request's file is
     * there but cannot be read, no expiry notice is sent for that window"): u1's three 30-minute CONFIGURE
     * windows A ({@code iso-alpha}), B ({@code iso-bravo}) and C ({@code iso-charlie}), approved by a1 in
     * that order one second apart from T0. A's change request file {@code requests/grant/<A>.xml} is
     * replaced with damaged XML (its own first half). The expiry work runs at T0+21 min and again at
     * T0+22 min: neither run throws; after the first run B and C have each had a GRANT_EXPIRING notice to
     * u1, after the second still exactly one each; A has none (no GRANT_EXPIRING carries A's request or
     * window id or names {@code iso-alpha}), so the two notices are all there are.
     */
    @Test
    public void t_gap_401_damagedChangeRequestDoesNotBlockTheOtherWindowsNotices() throws Exception {
        Window a = window("iso-alpha", T0);
        Window b = window("iso-bravo", T0.plusSeconds(1));
        Window c = window("iso-charlie", T0.plusSeconds(2));

        Path fileA = store().resolve("requests/grant/" + a.requestId() + ".xml");
        assertTrue(Files.isRegularFile(fileA), "premise (ARCHITECTURE 5): A's change request is stored at " + fileA);
        String xml = Files.readString(fileA, StandardCharsets.UTF_8);
        String damaged = xml.substring(0, xml.length() / 2);
        assertThrows(RuntimeException.class, () -> Jenkins.XSTREAM2.fromXML(damaged), "premise: the damaged copy is not readable XML");
        Files.writeString(fileA, damaged, StandardCharsets.UTF_8);
        NotificationCapture.clear();

        at(T0.plus(Duration.ofMinutes(21)));
        assertDoesNotThrow(() -> work().doRun(), "the expiry work must complete although A's change request is damaged");
        assertEquals(1, NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, b.requestId()).size(),
                "B gets its GRANT_EXPIRING notice in the run that met A's damaged request: " + NotificationCapture.describeAll());
        assertEquals(1, NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, c.requestId()).size(),
                "C gets its GRANT_EXPIRING notice in the run that met A's damaged request: " + NotificationCapture.describeAll());

        at(T0.plus(Duration.ofMinutes(22)));
        assertDoesNotThrow(() -> work().doRun(), "the second expiry work must complete too");
        assertOneNoticeTo(b, "u1");
        assertOneNoticeTo(c, "u1");
        assertNoNoticeFor(a, "LIMITATIONS 11: a window whose change request cannot be read gets no expiry notice");
        assertEquals(2, NotificationCapture.matching(n -> n.event == NotificationEvent.GRANT_EXPIRING).size(),
                "B's and C's are the only GRANT_EXPIRING notices: " + NotificationCapture.describeAll());
    }

    /**
     * T-GAP-402 (SPEC 13 EXPIRING for run requests, GRANT_EXPIRING; SPEC 6a / D-36 activation requests
     * notified like run requests; SPEC 4): run control on, pendingTimeoutHours 1, notice lead 10 min. At
     * T0-35 min r creates an ACTIVATE request on {@code iso-act} and a run request on the approval-required
     * {@code iso-run}, and u1 creates a CONFIGURE request on {@code iso-locked}; at T0 u1's 30-minute
     * CONFIGURE window on {@code iso-win} is approved. The pending change request's file is then made
     * unreadable (mode 000). The expiry work at T0+21 min (each pending request 56 minutes old, the window 9
     * minutes from its end) completes, and the run request and the activation request have each had one
     * EXPIRING notice to r, and the window one GRANT_EXPIRING notice to u1; a second run at T0+22 min
     * completes and adds none. Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_402_oneUnreadablePendingRequestDoesNotSkipTheOtherKinds() throws Exception {
        cfg.setRunControlEnabled(true);
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        FreeStyleProject runJob = j.createFreeStyleProject("iso-run");
        setBatchControl(runJob, new BatchControlJobProperty(true));
        FreeStyleProject actJob = j.createFreeStyleProject("iso-act");
        assertFalse(ActivationService.get().isActivated(actJob), "premise (SPEC 6a): a job created under run control starts not activated");
        j.createFreeStyleProject("iso-locked");

        at(T0.minus(Duration.ofMinutes(35)));
        String activation = as("r", () -> ActivationService.get().create(actJob, ActivationRequest.Action.ACTIVATE,
                "bring iso-act into service", List.of("a1")).getId());
        String run = as("r", () -> RunRequestService.get().create(runJob, new LinkedHashMap<>(), "month-end close", "a1").getId());
        String locked = as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, "iso-locked"),
                List.of(GrantAction.CONFIGURE), 30, "fix iso-locked", "a1").getId());
        Window win = window("iso-win", T0);

        Path lockedFile = store().resolve("requests/grant/" + locked + ".xml");
        unreadable(lockedFile);
        NotificationCapture.clear();

        at(T0.plus(Duration.ofMinutes(21)));
        assertDoesNotThrow(() -> work().doRun(), "the expiry work must complete although a pending change request cannot be read");
        assertEquals(1, NotificationCapture.await(NotificationEvent.EXPIRING, run).size(),
                "the run request gets its EXPIRING notice: " + NotificationCapture.describeAll());
        assertEquals(1, NotificationCapture.await(NotificationEvent.EXPIRING, activation).size(),
                "the activation request gets its EXPIRING notice: " + NotificationCapture.describeAll());
        assertEquals(1, NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, win.requestId()).size(),
                "the window gets its GRANT_EXPIRING notice: " + NotificationCapture.describeAll());

        at(T0.plus(Duration.ofMinutes(22)));
        assertDoesNotThrow(() -> work().doRun(), "the second expiry work must complete too");
        List<NotificationCapture> runNotices = NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, run);
        assertEquals(1, runNotices.size(), "EXPIRING fires once for the run request: " + runNotices);
        assertEquals(List.of("r"), runNotices.get(0).recipients, "the run request's EXPIRING goes to its requester");
        List<NotificationCapture> activationNotices = NotificationCapture.of(NotificationEvent.EXPIRING, activation);
        assertEquals(1, activationNotices.size(), "EXPIRING fires once for the activation request: " + activationNotices);
        assertEquals(List.of("r"), activationNotices.get(0).recipients, "the activation request's EXPIRING goes to its requester");
        assertOneNoticeTo(win, "u1");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(run).getStatus(), "premise: the run request has not expired yet");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(activation).getStatus(), "premise: the activation request has not expired yet");
    }

    /**
     * T-GAP-403 (SPEC 13 GRANT_EXPIRING "fire[s] once"; ARCHITECTURE 5 {@code grants/<id>.xml}): u1's
     * three 30-minute CONFIGURE windows A ({@code iso-one}), B ({@code iso-two}) and C ({@code iso-three}),
     * approved by a1 in that order one second apart from T0; B's grant file is made unreadable (mode 000)
     * after the approval. The expiry work runs at T0+21 min and at T0+22 min: after the first run A and C
     * have each had a GRANT_EXPIRING notice to u1, after the second still exactly one each; B has none
     * while its file cannot be read. Skipped where this process can still read the file.
     */
    @Test
    public void t_gap_403_unreadableGrantFileDoesNotCostTheOtherWindowsTheirNotices() throws Exception {
        Window a = window("iso-one", T0);
        Window b = window("iso-two", T0.plusSeconds(1));
        Window c = window("iso-three", T0.plusSeconds(2));
        unreadable(store().resolve("grants/" + b.grantId() + ".xml"));
        NotificationCapture.clear();

        at(T0.plus(Duration.ofMinutes(21)));
        Throwable first = runCatching();
        assertEquals(1, NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, a.requestId()).size(),
                "A gets its GRANT_EXPIRING notice in the run that met B's unreadable file (the work threw " + first + "): "
                        + NotificationCapture.describeAll());
        assertEquals(1, NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, c.requestId()).size(),
                "C gets its GRANT_EXPIRING notice in the run that met B's unreadable file (the work threw " + first + "): "
                        + NotificationCapture.describeAll());

        at(T0.plus(Duration.ofMinutes(22)));
        Throwable second = runCatching();
        System.out.println("T-GAP-403 observation: the expiry work threw " + first + " / " + second);
        assertOneNoticeTo(a, "u1");
        assertOneNoticeTo(c, "u1");
        assertNoNoticeFor(b, "a window whose grant file cannot be read gets no GRANT_EXPIRING notice while it cannot be read");
    }

    // ------------------------------------------------------------------ helpers

    /** u1's 30-minute CONFIGURE window on a new job {@code name}, requested and approved by a1 at {@code approvedAt}. */
    private Window window(String name, Instant approvedAt) throws Exception {
        j.createFreeStyleProject(name);
        at(approvedAt);
        GrantRequest request = as("u1", () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, name),
                List.of(GrantAction.CONFIGURE), 30, "maintenance of " + name, "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval opens a window on " + name);
        assertTrue(GrantService.get().hasActiveGrant("u1", name, Item.CONFIGURE), "premise: the window on " + name + " is open");
        return new Window(request.getId(), grant.getId(), name);
    }

    /** Exactly one GRANT_EXPIRING notice for {@code w}, to {@code recipient}, read after the quiet period. */
    private static void assertOneNoticeTo(Window w, String recipient) throws InterruptedException {
        List<NotificationCapture> notices = NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, w.requestId());
        assertEquals(1, notices.size(), "GRANT_EXPIRING fires once for the window on " + w.fullName() + ": " + NotificationCapture.describeAll());
        assertEquals(List.of(recipient), notices.get(0).recipients, "GRANT_EXPIRING goes to the window's holder");
    }

    /** No GRANT_EXPIRING notice carries {@code w}'s request or window id or names its item. */
    private static void assertNoNoticeFor(Window w, String what) throws InterruptedException {
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS); // bounded wait for a delivery that must NOT happen
        List<NotificationCapture> found = NotificationCapture.matching(n -> n.event == NotificationEvent.GRANT_EXPIRING
                && (w.requestId().equals(n.requestId) || w.grantId().equals(n.requestId)
                        || String.valueOf(n.subject).contains(w.fullName()) || String.valueOf(n.reason).contains(w.fullName())
                        || String.valueOf(n.url).contains(w.fullName())));
        assertTrue(found.isEmpty(), what + " (window on " + w.fullName() + "): " + found);
    }

    /** Makes {@code file} unreadable (mode 000) until {@link #tearDown}; skips the row where this process can still read it. */
    private void unreadable(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): " + file + " is stored");
        Set<PosixFilePermission> none = PosixFilePermissions.fromString("---------");
        try {
            restore.add(file);
            Files.setPosixFilePermissions(file, none);
        } catch (UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "POSIX permissions are needed to make " + file + " unreadable");
        }
        Assumptions.assumeFalse(Files.isReadable(file), "the file system does not refuse reads for this process (root?)");
    }

    private static Throwable runCatching() {
        try {
            work().doRun();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static ExpiryPeriodicWork work() {
        return ExtensionList.lookupSingleton(ExpiryPeriodicWork.class);
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private static void at(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }
}
