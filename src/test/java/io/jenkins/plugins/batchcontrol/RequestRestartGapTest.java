package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedCause;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-06: restart recovery edge cases. Matrix rows T-GAP-123 ..
 * T-GAP-125 (note 276).
 *
 * <p>Basis: SPEC 4 "승인됐지만 큐 투입 전 재시작된 요청은 재시작 후 자동으로 큐에 투입된다(중복 투입
 * 없음)"; D-20 "복구 재투입은 requestId 기반 중복 제거"; SPEC 7 "APPROVED 상태에서
 * approvedRunTimeoutMinutes 안에 큐 투입이 안 되면 EXPIRED가 된다(재시작 복구 지연은 예외로 허용: 복구
 * 시점 기준으로 판정)". J is approval-required, already has one finished build, and is restricted
 * to a label no agent has, so an approved run waits in the queue. The faults (F) are made on disk
 * between the sessions.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-20 and docs/ARCHITECTURE.md section 5 only (no
 * src/main knowledge).
 */
public class RequestRestartGapTest {

    private static final String JOB = "gap-restart-j";
    private static final String NOWHERE = "gap-no-such-agent";
    private static final Instant T0 = Instant.parse("2026-09-23T09:00:00Z");

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-GAP-123 (L1-06 case 1; SPEC 4 no double submission): an approved request's run is still
     * queued (label nobody has) when Jenkins stops. After the restart, once the label restriction
     * is removed, exactly one new build runs and carries that request (ApprovedCause), the request
     * is EXECUTED, and J's earlier build #1 is untouched.
     */
    @Test
    public void t_gap_123_queuedApprovedRunRunsOnceAfterRestart() throws Throwable {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicLong firstStart = new AtomicLong();
        session.then(r -> {
            FreeStyleProject job = prepare(r);
            firstStart.set(job.getBuildByNumber(1).getStartTimeInMillis());
            id.set(approveQueued(r, job));
        });
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            assertNotNull(job, "J survives the restart");
            job.setAssignedLabel(null);
            r.jenkins.getQueue().scheduleMaintenance();
            awaitBuild(r, job, 2);
            r.waitUntilNoActivity();
            assertEquals(2, job.getBuilds().size(), "exactly one new build after the restart");
            assertEquals(3, job.getNextBuildNumber(), "exactly one build number consumed after the restart");
            FreeStyleBuild second = job.getBuildByNumber(2);
            ApprovedCause cause = second.getCause(ApprovedCause.class);
            assertNotNull(cause, "the new build carries the approved request");
            assertEquals(id.get(), cause.getRequestId());
            FreeStyleBuild first = job.getBuildByNumber(1);
            assertNotNull(first, "the earlier build is still there");
            assertEquals(Result.SUCCESS, first.getResult(), "the earlier build is untouched");
            assertEquals(firstStart.get(), first.getStartTimeInMillis(), "the earlier build is the same build");
            assertNull(first.getCause(ApprovedCause.class), "the earlier build was not relabelled as the approved run");
            assertEquals(RequestStatus.EXECUTED, RunRequestService.get().load(id.get()).getStatus());
        });
    }

    /**
     * T-GAP-124 (L1-06 case 2 (F); SPEC 4 "중복 투입 없음", D-20 requestId-based de-duplication): an
     * approved request has run (EXECUTED, build #2). While Jenkins is stopped its
     * {@code requests/run/<id>.xml} is edited back to APPROVED without the executed run id. After
     * the restart no second build is started for it.
     */
    @Test
    public void t_gap_124_editedBackToApprovedDoesNotRunTwice() throws Throwable {
        AtomicReference<Path> file = new AtomicReference<>();
        session.then(r -> {
            FreeStyleProject job = prepare(r);
            job.setAssignedLabel(null);
            String id = approveQueued(r, job);
            r.jenkins.getQueue().scheduleMaintenance();
            awaitBuild(r, job, 2);
            r.waitUntilNoActivity();
            RunRequest executed = RunRequestService.get().load(id);
            assertEquals(RequestStatus.EXECUTED, executed.getStatus(), "premise: the request ran");
            assertNotNull(executed.getExecutedRunId(), "premise: the run is linked");
            file.set(r.jenkins.getRootDir().toPath().resolve("batch-control").resolve("requests").resolve("run")
                    .resolve(id + ".xml"));
        });
        Path requestFile = file.get();
        String xml = Files.readString(requestFile, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<status>EXECUTED</status>"), "fixture: the stored request says EXECUTED: " + xml);
        String edited = xml.replace("<status>EXECUTED</status>", "<status>APPROVED</status>")
                .replaceAll("(?s)<executedRunId>.*?</executedRunId>", "");
        assertFalse(edited.contains("executedRunId>"), "fixture: the executed run id is removed");
        Files.writeString(requestFile, edited, StandardCharsets.UTF_8);
        session.then(r -> {
            secure(r);
            FreeStyleProject job = r.jenkins.getItemByFullName(JOB, FreeStyleProject.class);
            r.waitUntilNoActivity();
            r.jenkins.getQueue().scheduleMaintenance();
            r.waitUntilNoActivity();
            assertEquals(2, job.getBuilds().size(), "no second build may be started for a request that already ran");
            assertEquals(3, job.getNextBuildNumber(), "no build number consumed after the restart");
            assertTrue(r.jenkins.getQueue().isEmpty(), "nothing is queued");
        });
    }

    /**
     * T-GAP-125 (L1-06 case 3 (F); SPEC 7 approved-run timeout judged from recovery time): an
     * approved request's run is still queued when Jenkins stops; while it is stopped J's job
     * directory is deleted. After the restart Jenkins and the run requests page work, no build of
     * J exists, and once the approved-run timeout has passed and the periodic work ran, the request
     * is EXPIRED. Guard: right after the restart, inside the timeout, it is not EXPIRED yet.
     */
    @Test
    public void t_gap_125_queuedApprovedRunOfAJobDeletedOnDiskExpires() throws Throwable {
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<File> jobDir = new AtomicReference<>();
        session.then(r -> {
            BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
            FreeStyleProject job = prepare(r);
            id.set(approveQueued(r, job));
            jobDir.set(job.getRootDir());
        });
        try (Stream<Path> paths = Files.walk(jobDir.get().toPath())) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
        assertFalse(jobDir.get().exists(), "fixture: J's directory is gone");
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
        session.then(r -> {
            secure(r);
            r.waitUntilNoActivity();
            assertNull(r.jenkins.getItemByFullName(JOB), "premise: J does not exist after the restart");
            assertTrue(r.jenkins.getQueue().isEmpty(), "nothing of J is queued");
            assertEquals(200, ApproverFormFixtures.get(r, "a1", "batch-control/requests/").getStatusCode(),
                    "the run requests page still opens");
            runExpiryWork();
            assertFalse(RunRequestService.get().load(id.get()).getStatus() == RequestStatus.EXPIRED,
                    "guard: inside the approved-run timeout (judged from recovery) the request is not EXPIRED yet");

            BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(5 + 61)), ZoneOffset.UTC));
            runExpiryWork();
            RunRequest reloaded = RunRequestService.get().load(id.get());
            assertEquals(RequestStatus.EXPIRED, reloaded.getStatus(), "the approved request of a vanished job ends EXPIRED");
            assertNull(reloaded.getExecutedRunId(), "no run is linked");
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void secure(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
    }

    /** J with one finished build (run before run control), then approval-required and restricted to a label nobody has. */
    private static FreeStyleProject prepare(JenkinsRule r) throws Exception {
        secure(r);
        FreeStyleProject job = r.createFreeStyleProject(JOB);
        r.buildAndAssertSuccess(job);
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        setBatchControl(job, new BatchControlJobProperty(true));
        job.setAssignedLabel(r.jenkins.getLabel(NOWHERE));
        assertEquals(1, job.getBuilds().size(), "premise: J has one finished build");
        return job;
    }

    /** u1 requests, a1 approves; the approved run waits in the queue (label nobody has). */
    private static String approveQueued(JenkinsRule r, FreeStyleProject job) throws Exception {
        String id;
        try (ACLContext ignored = as("u1")) {
            id = RunRequestService.get().create(job, new LinkedHashMap<>(), "restart edge case", "a1").getId();
        }
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
        if (job.getAssignedLabel() != null) {
            assertEquals(1, r.jenkins.getQueue().getItems().length, "premise: the approved run waits in the queue");
            assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus(), "premise: APPROVED, queued");
        }
        return id;
    }

    private static void awaitBuild(JenkinsRule r, FreeStyleProject job, int number) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (job.getBuildByNumber(number) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100); // polling for the queue to start the build, not waiting for an expiry
        }
        assertNotNull(job.getBuildByNumber(number), "build #" + number + " of " + job.getFullName() + " must start");
    }

    private static void runExpiryWork() throws Exception {
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
