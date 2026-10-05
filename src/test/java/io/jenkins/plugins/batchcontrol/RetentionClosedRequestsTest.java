package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.RetentionPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 4, "Closed requests and grants past the retention period are deleted by retention
 * like the other records, and the per-minute expiry work does not scan closed requests" (#13).
 * Matrix rows T-04-13 and T-04-14 (note 67).
 *
 * <p>Presence is asserted on the documented store paths (ARCHITECTURE section 5:
 * {@code requests/run/<id>.xml}, {@code requests/grant/<id>.xml}, {@code grants/<id>.xml}) and
 * through the services' list/lookup API. T-04-14 has no public counter to observe, so "does not
 * scan" is measured as cost: the expiry work over 10,000 closed request files must cost about
 * what it costs over none (note 67).
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md section 5, issue #13 and docs/TEST-MATRIX.md
 * only (no src/main knowledge).
 */
@WithJenkins
public class RetentionClosedRequestsTest {

    private static final int CLOSED_RUN_FILES = 6_000;
    private static final int CLOSED_GRANT_FILES = 4_000;
    /** Allowed extra cost of one expiry pass over 10,000 closed request files. */
    private static final long SCAN_BOUND_MS = 250;

    private JenkinsRule j;
    private FreeStyleProject job;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(Item.BUILD, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setRetentionMonths(1);
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-04-13 (#13): a rejected and a cancelled run request, a rejected grant request, and an
     * approved grant request whose grant expired, all three months old, are deleted by retention
     * (files gone, no longer listed). A request closed yesterday and a grant active now are kept.
     */
    @Test
    public void t_04_13_closedRequestsAndGrantsPastRetentionAreDeleted() throws Exception {
        Instant old = YearMonth.now(ZoneOffset.UTC).minusMonths(3).atDay(15).atTime(12, 0)
                .toInstant(ZoneOffset.UTC);
        BatchClock.setForTest(Clock.fixed(old, ZoneOffset.UTC));
        String oldRejected = submitRun();
        asDo("a1", () -> RunRequestService.get().reject(oldRejected, "not this month"));
        String oldCancelled = submitRun();
        asDo("u1", () -> RunRequestService.get().cancel(oldCancelled));
        GrantRequest oldGrantRejected = submitGrant();
        asDo("a1", () -> GrantRequestService.get().reject(oldGrantRejected.getId(), "no"));
        GrantRequest oldGrantApproved = submitGrant();
        Grant oldGrant = as("a1", () -> GrantRequestService.get().approve(oldGrantApproved.getId(), "ok"));

        BatchClock.reset();
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        BatchClock.setForTest(Clock.fixed(yesterday, ZoneOffset.UTC));
        String recentRejected = submitRun();
        asDo("a1", () -> RunRequestService.get().reject(recentRejected, "not today"));
        BatchClock.reset();
        GrantRequest activeRequest = submitGrant();
        Grant active = as("a1", () -> GrantRequestService.get().approve(activeRequest.getId(), "ok"));

        Path store = StoreDataFixtures.storeDir();
        List<Path> oldFiles = List.of(
                store.resolve("requests/run/" + oldRejected + ".xml"),
                store.resolve("requests/run/" + oldCancelled + ".xml"),
                store.resolve("requests/grant/" + oldGrantRejected.getId() + ".xml"),
                store.resolve("requests/grant/" + oldGrantApproved.getId() + ".xml"),
                store.resolve("grants/" + oldGrant.getId() + ".xml"));
        for (Path file : oldFiles) {
            assertTrue(Files.exists(file), "fixture: " + file + " is stored");
        }
        Path recentFile = store.resolve("requests/run/" + recentRejected + ".xml");
        Path activeGrantFile = store.resolve("grants/" + active.getId() + ".xml");
        assertTrue(Files.exists(recentFile) && Files.exists(activeGrantFile), "fixture: recent files stored");

        ExtensionList.lookupSingleton(RetentionPeriodicWork.class).doRun();

        for (Path file : oldFiles) {
            assertFalse(Files.exists(file), "a closed record past retentionMonths must be deleted by retention (#13): " + file);
        }
        List<String> runIds = RunRequestService.get().list().stream().map(RunRequest::getId).toList();
        assertFalse(runIds.contains(oldRejected) || runIds.contains(oldCancelled), "deleted run requests must no longer be listed");
        List<String> grantIds = GrantRequestService.get().list().stream().map(GrantRequest::getId).toList();
        assertFalse(grantIds.contains(oldGrantRejected.getId()) || grantIds.contains(oldGrantApproved.getId()), "deleted grant requests must no longer be listed");

        // negative twin: what is inside retention, or still open, stays
        assertTrue(Files.exists(recentFile), "a request closed yesterday is inside retention and must be kept");
        assertTrue(runIds.contains(recentRejected), "the recent request must still be listed");
        assertTrue(Files.exists(activeGrantFile), "an active grant must be kept");
        assertTrue(GrantService.get().hasActiveGrant("u1", "batch-x", Item.CONFIGURE), "the active grant must still be effective after retention");
    }

    /**
     * T-04-14 (#13): the per-minute expiry work does not scan closed requests. One expiry pass
     * over 6,000 closed run request files and 4,000 closed grant request files (inside
     * retention) costs no more than {@value #SCAN_BOUND_MS} ms beyond a pass over none, while the
     * same pass still expires an overdue PENDING request (the guard that the pass did its work).
     */
    @Test
    public void t_04_14_expiryWorkDoesNotScanClosedRequests() throws Exception {
        Instant t0 = Instant.parse("2026-09-20T00:00:00Z");
        BatchClock.setForTest(Clock.fixed(t0, ZoneOffset.UTC));
        ExpiryPeriodicWork expiry = ExtensionList.lookupSingleton(ExpiryPeriodicWork.class);

        String runTemplate = submitRun();
        asDo("a1", () -> RunRequestService.get().reject(runTemplate, "template"));
        GrantRequest grantTemplate = submitGrant();
        asDo("a1", () -> GrantRequestService.get().reject(grantTemplate.getId(), "template"));
        String pending = submitRun();

        long baseline = fastestOf3(expiry);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(pending).getStatus(), "fixture: nothing is due at T0");

        Path store = StoreDataFixtures.storeDir();
        copies(store.resolve("requests/run"), runTemplate, t0, CLOSED_RUN_FILES);
        copies(store.resolve("requests/grant"), grantTemplate.getId(), t0.plusSeconds(1), CLOSED_GRANT_FILES);

        BatchClock.setForTest(Clock.fixed(t0.plus(Duration.ofHours(73)), ZoneOffset.UTC));
        long withClosed = fastestOf3(expiry);
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(pending).getStatus(), "guard: the expiry pass must still expire an overdue PENDING request");

        System.out.println("[expiry-scan] baseline " + baseline + " ms, with "
                + (CLOSED_RUN_FILES + CLOSED_GRANT_FILES) + " closed request files " + withClosed + " ms");
        assertTrue(withClosed - baseline < SCAN_BOUND_MS, "the per-minute expiry work must not scan closed requests (#13): a pass over "
                + (CLOSED_RUN_FILES + CLOSED_GRANT_FILES) + " closed request files took " + withClosed
                + " ms against " + baseline + " ms over none");
    }

    /**
     * T-04-17 (security-10 S-09): an EXECUTED request is judged by its latest activity, including
     * the start of its build. A request created, approved and queued three months ago whose build
     * only started now (no executor until then) is kept by retention, and its run record still
     * links to it; the negative twin, a request whose build also ran three months ago, is deleted.
     */
    @Test
    public void t_04_17_executedRequestWhoseBuildStartedInsideRetentionIsKept() throws Exception {
        Instant old = YearMonth.now(ZoneOffset.UTC).minusMonths(3).atDay(15).atTime(12, 0)
                .toInstant(ZoneOffset.UTC);
        BatchClock.setForTest(Clock.fixed(old, ZoneOffset.UTC));

        // twin: queued and executed three months ago
        String executedOld = submitRun();
        asDo("a1", () -> RunRequestService.get().approve(executedOld, "ok"));
        j.waitUntilNoActivity();
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(executedOld).getStatus(), "fixture: the old request executed at the old time");

        // queued three months ago, but no executor until now
        j.jenkins.setNumExecutors(0);
        String late = submitRun();
        asDo("a1", () -> RunRequestService.get().approve(late, "ok"));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(late).getStatus(), "fixture: the late request is approved and waiting in the queue");
        assertFalse(j.jenkins.getQueue().isEmpty(), "fixture: its build waits in the queue");

        BatchClock.reset();
        j.jenkins.setNumExecutors(1);
        j.waitUntilNoActivity();
        RunRequest executedLate = RunRequestService.get().load(late);
        assertEquals(RequestStatus.EXECUTED, executedLate.getStatus(), "fixture: the late request executed now");
        String runId = executedLate.getExecutedRunId();
        assertTrue(runId != null, "fixture: the late request links its run");

        Path store = StoreDataFixtures.storeDir();
        Path lateFile = store.resolve("requests/run/" + late + ".xml");
        Path oldFile = store.resolve("requests/run/" + executedOld + ".xml");
        assertTrue(Files.exists(lateFile) && Files.exists(oldFile), "fixture: both request files are stored");

        ExtensionList.lookupSingleton(RetentionPeriodicWork.class).doRun();

        assertTrue(Files.exists(lateFile), "an EXECUTED request whose build started inside the retention period must be kept (S-09)");
        assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(late).getStatus(), "the kept request must still load as EXECUTED");
        assertTrue(FileStore.get().listRunRecords(YearMonth.now(BatchClock.clock())).stream()
                .anyMatch(rec -> runId.equals(rec.getRunId()) && late.equals(rec.getRunRequestId())), "the kept run record must still link to the kept request");
        assertFalse(Files.exists(oldFile), "twin: a request whose build also ran three months ago is deleted");
    }

    // ---------------------------------------------------------------------------------------

    /** Copies {@code dir/<templateId>.xml} {@code count} times under fresh ids of the documented form. */
    private static void copies(Path dir, String templateId, Instant at, int count) throws Exception {
        Path template = dir.resolve(templateId + ".xml");
        assertTrue(Files.exists(template), "fixture: " + template + " is stored");
        String xml = Files.readString(template, StandardCharsets.UTF_8);
        assertTrue(xml.contains(templateId), "fixture: the request file carries its id");
        String prefix = LocalDateTime.ofInstant(at, ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT));
        for (int i = 0; i < count; i++) {
            String id = prefix + "-" + String.format(Locale.ROOT, "%06d", i);
            Files.writeString(dir.resolve(id + ".xml"), xml.replace(templateId, id), StandardCharsets.UTF_8);
        }
    }

    private static long fastestOf3(ExpiryPeriodicWork expiry) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            expiry.doRun();
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        return best;
    }

    private String submitRun() throws Exception {
        return as("u1", () -> RunRequestService.get().create(job, new LinkedHashMap<>(),
                "retention probe", "a1").getId());
    }

    private GrantRequest submitGrant() throws Exception {
        return as("u1", () -> GrantRequestService.get().create(
                new GrantScope(GrantScope.Type.ITEM, "batch-x"), Arrays.asList(GrantAction.CONFIGURE),
                30, "retention probe", "a1"));
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private interface VoidBody {
        void run() throws Exception;
    }

    private static void asDo(String user, VoidBody body) throws Exception {
        try (ACLContext ignored = ACL.as(User.getById(user, true))) {
            body.run();
        }
    }

    private static <T> T as(String user, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as(User.getById(user, true))) {
            return body.run();
        }
    }
}
