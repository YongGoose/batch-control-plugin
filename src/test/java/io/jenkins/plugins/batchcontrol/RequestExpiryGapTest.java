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
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Coverage lane 1, scenario L1-21 (F): periodic work isolation. Matrix rows T-GAP-179 and
 * T-GAP-180 (note 276).
 *
 * <p>Basis: SPEC 7 "승인 대기 요청은 설정된 기간이 지나면 자동 만료 ... 만료 시 상태가 EXPIRED로 바뀌고";
 * SPEC 6a pending activation requests are "decided like a run request" and follow the same pending
 * timeout; SPEC 13 "EXPIRING ... fire once" and "A notifier failure never fails or delays the
 * request action"; ARCHITECTURE 2 ({@code PeriodicWork}, one-minute expiry work). Fault: a store
 * directory made unwritable (POSIX; skipped where it stays writable, e.g. as root).
 *
 * <p>Written from docs/SPEC.md and docs/ARCHITECTURE.md sections 2 and 5 only (no src/main
 * knowledge).
 */
@WithJenkins
public class RequestExpiryGapTest {

    private static final Instant T = Instant.parse("2026-09-26T06:00:00Z");

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setPendingTimeoutHours(1);
        cfg.setNotifyBeforeExpiryMinutes(10);
        cfg.save();
        job = j.createFreeStyleProject("gap-expiry-iso");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
    }

    /**
     * T-GAP-179 (L1-21 case 1): an overdue pending activation request and an overdue pending run
     * request; {@code activation-requests/} is made unwritable and the periodic work runs: the run
     * request is still EXPIRED.
     */
    @Test
    public void t_gap_179_unwritableActivationRequestsDoNotStopRunRequestExpiry() throws Exception {
        at(T);
        String activation = createActivation();
        String run = createRun();
        Path dir = store().resolve("activation-requests");
        at(T.plus(Duration.ofMinutes(62)));
        Throwable thrown = withUnwritable(dir, () -> ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun());
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(run).getStatus(),
                "the overdue run request expires although activation requests cannot be written"
                        + (thrown == null ? "" : " (the work threw " + thrown + ")"));
        assertTrue(ActivationService.get().load(activation) != null, "the activation request still loads");
    }

    /**
     * T-GAP-180 (L1-21 case 2): a run request inside its EXPIRING window (created 52 minutes ago)
     * and an overdue pending activation request; {@code requests/run/} is made unwritable and the
     * periodic work runs: the work completes without an error and the overdue activation request
     * is EXPIRED.
     */
    @Test
    public void t_gap_180_unwritableRunRequestsDoNotStopOtherExpiries() throws Exception {
        at(T);
        String activation = createActivation();
        at(T.plus(Duration.ofMinutes(10)));
        String run = createRun();
        Path dir = store().resolve("requests").resolve("run");
        at(T.plus(Duration.ofMinutes(62)));
        Throwable thrown = withUnwritable(dir, () -> ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun());
        assertTrue(thrown == null, "the periodic work must complete although requests/run/ cannot be written, it threw " + thrown);
        assertEquals(RequestStatus.EXPIRED, ActivationService.get().load(activation).getStatus(),
                "the overdue activation request expires although run requests cannot be written");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(run).getStatus(), "premise: the run request is not overdue yet");
    }

    // ------------------------------------------------------------------ helpers

    interface Step {
        void run() throws Exception;
    }

    /** Runs {@code step} while {@code dir} is unwritable (r-x); returns what it threw, or null. */
    private static Throwable withUnwritable(Path dir, Step step) throws Exception {
        assertTrue(Files.isDirectory(dir), "fixture: " + dir + " exists");
        Set<PosixFilePermission> original;
        try {
            original = Files.getPosixFilePermissions(dir);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions are needed to make " + dir + " unwritable");
            return null;
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(dir), dir + " must really be unwritable (not as root)");
            try {
                step.run();
                return null;
            } catch (Exception | Error e) {
                return e;
            }
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    private Path store() {
        return j.jenkins.getRootDir().toPath().resolve("batch-control");
    }

    private String createActivation() throws Exception {
        try (ACLContext ignored = as("u1")) {
            return ActivationService.get().create(job, ActivationRequest.Action.ACTIVATE, "go live", List.of("a1")).getId();
        }
    }

    private String createRun() {
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(job, new LinkedHashMap<>(), "expiry isolation", "a1").getId();
        }
    }

    private static void at(Instant instant) {
        BatchClock.setForTest(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
