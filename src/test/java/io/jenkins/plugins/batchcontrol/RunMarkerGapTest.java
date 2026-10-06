package io.jenkins.plugins.batchcontrol;

import hudson.model.Cause;
import hudson.model.Failure;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesFile;
import static io.jenkins.plugins.batchcontrol.TypedParameterFixtures.valuesPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenario L1-05: forged and stale approval markers, and check-at-submit.
 * Matrix rows T-GAP-119 .. T-GAP-122 (note 276).
 *
 * <p>Basis: SPEC 6 "승인 투입 마커는 요청 ID에 묶이고 큐 투입 1회로 소비된다. 동일 마커의 재사용 ...
 * 차단되고 기록된다" (D-23), "type=MARKER_REUSE_BLOCKED ... 레코드는 소비된 요청 ID와 대상 잡을 식별할 수
 * 있어야 한다" (D-30); SPEC 7 "큐 투입 직전에 만료를 재확인(check-at-submit)하여 만료된 승인 건은 절대
 * 투입되지 않는다" (D-20), "APPROVED 상태에서 approvedRunTimeoutMinutes 안에 큐 투입이 안 되면 EXPIRED";
 * SPEC 13 D-54 (the requester receives EXPIRED); SPEC 5 D-74 (the values file is deleted when the
 * request ends). The marker is the public {@link ApprovedRunAction} (read from builds in
 * MarkerReuseAuditTest), built here with its request-id constructor; an approved request that was
 * not queued is reached with {@link QueueRefusalFixtures} (a test queue handler refuses J).
 * Submissions are made by the requester with a user cause, so the approval rule (not activation)
 * decides them.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-20/D-23/D-30/D-54/D-74 and the existing
 * fixtures only (no src/main knowledge).
 */
@WithJenkins
public class RunMarkerGapTest {

    private static final Instant T = Instant.parse("2026-09-22T10:00:00Z");

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    private FreeStyleProject jobJ;
    private FreeStyleProject jobK;

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures, note 265). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    @TestExtension
    public static class CapturingNotifier extends BatchControlNotifier {
        @Override
        public void notify(NotificationEvent event, Notification notification) {
            NotificationCapture.record(event, notification);
        }
    }

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        NotificationCapture.clear();
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));

        jobJ = j.createFreeStyleProject("gap-marker-j");
        jobJ.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("DAY", "2000-01-01")));
        setBatchControl(jobJ, new BatchControlJobProperty(true));
        jobK = j.createFreeStyleProject("gap-marker-k");
        setBatchControl(jobK, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        NotificationCapture.clear();
    }

    /**
     * T-GAP-119 (L1-05; SPEC 6 D-23 "승인 투입 마커는 요청 ID에 묶이고"): u1 schedules J with a marker
     * naming a request id that does not exist; nothing is queued and no build starts (blocking
     * triple).
     */
    @Test
    public void t_gap_119_markerOfAnUnknownRequestIsRefused() throws Exception {
        String forged = UUID.randomUUID().toString();
        assertRefusedSubmission("a marker naming no request", jobJ, new ApprovedRunAction(forged));
        assertBlocked(j, jobJ, 1, 0);
    }

    /**
     * T-GAP-120 (L1-05; SPEC 6 D-23; SPEC 4 state machine PENDING → APPROVED → EXECUTED): u1
     * schedules J with the marker of its own PENDING request; nothing is queued and the request
     * stays PENDING with no executed run.
     */
    @Test
    public void t_gap_120_markerOfAPendingRequestIsRefused() throws Exception {
        String id = create("pending marker", "2026-09-22");
        assertRefusedSubmission("the marker of a PENDING request", jobJ, new ApprovedRunAction(id));
        assertBlocked(j, jobJ, 1, 0);
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(), "the request stays PENDING");
        assertNull(RunRequestService.get().load(id).getExecutedRunId(), "no run is linked to the PENDING request");
    }

    /**
     * T-GAP-121 (L1-05; SPEC 6 D-23/D-30): a request for J is approved while a test queue handler
     * refuses J, so it stays APPROVED with no build. u1 then schedules K with that request's
     * marker: nothing is queued on K and a MARKER_REUSE_BLOCKED record names K and the request id.
     * Guard: no such record existed before the attempt.
     */
    @Test
    public void t_gap_121_markerPresentedOnAnotherJobIsRefusedAndRecorded() throws Exception {
        String id = create("approved, never queued", "2026-09-22");
        QueueRefusalFixtures.refusedBeforeTheGate(jobJ, () -> approve(id));
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(id).getStatus(), "premise: APPROVED");
        j.waitUntilNoActivity();
        assertTrue(jobJ.getBuilds().isEmpty(), "premise: no build of J");
        assertTrue(reuseRecords(id).isEmpty(), "guard: no MARKER_REUSE_BLOCKED record before the attempt");

        assertRefusedSubmission("J's marker presented on K", jobK, new ApprovedRunAction(id));
        assertBlocked(j, jobK, 1, 0);
        List<ChangeRecord> records = reuseRecords(id);
        assertEquals(1, records.size(), "one MARKER_REUSE_BLOCKED record for the attempt: " + describe(records));
        ChangeRecord rec = records.get(0);
        String text = rec.getTarget() + " " + rec.getDetail();
        assertTrue(text.contains("gap-marker-k"), "the record names K, the job the marker was presented on: " + text);
        assertEquals("u1", rec.getUser(), "the record names who presented the marker");
    }

    /**
     * T-GAP-122 (L1-05; SPEC 7 D-20 check-at-submit; SPEC 13 D-54; SPEC 5 D-74): two requests for J
     * (string parameter, so each has a values file) are approved while the test handler refuses
     * J. With the handler off and the clock inside the approved-run timeout, J scheduled with the
     * first marker starts exactly one build (guard). With the clock past
     * {@code approvedRunTimeoutMinutes}, J scheduled with the second marker queues nothing, the
     * request is EXPIRED, the requester receives EXPIRED and its values file is gone.
     */
    @Test
    public void t_gap_122_checkAtSubmitExpiresAStaleApproval() throws Exception {
        String inTime = create("approved in time", "2026-09-23");
        String stale = create("approved, then stale", "2026-09-24");
        valuesFile(j, stale);
        QueueRefusalFixtures.refusedBeforeTheGate(jobJ, () -> {
            approve(inTime);
            approve(stale);
        });
        assertEquals(RequestStatus.APPROVED, RunRequestService.get().load(stale).getStatus(), "premise: APPROVED, never queued");
        assertTrue(jobJ.getBuilds().isEmpty(), "premise: no build yet");

        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
        submitAsU1(jobJ, new ApprovedRunAction(inTime));
        j.waitUntilNoActivity();
        assertEquals(1, jobJ.getBuilds().size(), "guard: inside the timeout the marker's submission starts exactly one build");

        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        int next = jobJ.getNextBuildNumber();
        assertRefusedSubmission("the stale marker", jobJ, new ApprovedRunAction(stale));
        assertBlocked(j, jobJ, next, 1);
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(stale).getStatus(), "check-at-submit expires the stale approval");
        assertNull(RunRequestService.get().load(stale).getExecutedRunId(), "no run is linked to the expired request");
        List<NotificationCapture> expired = NotificationCapture.await(NotificationEvent.EXPIRED, stale);
        assertTrue(expired.stream().anyMatch(n -> n.recipients != null && n.recipients.contains("u1")),
                "the requester receives EXPIRED: " + NotificationCapture.describeAll());
        assertFalse(Files.exists(valuesPath(j, stale)), "the values file of the expired request is deleted (D-74)");
    }

    // ------------------------------------------------------------------ helpers

    private String create(String reason, String day) {
        List<ParameterValue> values = List.of(new StringParameterValue("DAY", day));
        try (ACLContext ignored = as("u1")) {
            return RunRequestService.get().create(jobJ, values, reason, "a1").getId();
        }
    }

    private void approve(String id) {
        try (ACLContext ignored = as("a1")) {
            RunRequestService.get().approve(id, "ok");
        }
    }

    /** u1 submits {@code job} with a user cause and {@code marker}; returns the future (null when refused silently). */
    private Future<?> submitAsU1(FreeStyleProject job, ApprovedRunAction marker) {
        try (ACLContext ignored = as("u1")) {
            return job.scheduleBuild2(0, new Cause.UserIdCause(), marker);
        }
    }

    /** A refused submission either returns null or throws the guidance {@link Failure}. */
    private void assertRefusedSubmission(String what, FreeStyleProject job, ApprovedRunAction marker) {
        try {
            assertNull(submitAsU1(job, marker), what + " must not be queued");
        } catch (Failure expectedGuidance) {
            // a refusal that tells the person why is equally a refusal
        }
    }

    private static List<ChangeRecord> reuseRecords(String requestId) {
        return ApproverFormFixtures.records(ChangeType.MARKER_REUSE_BLOCKED).stream()
                .filter(r -> (r.getTarget() + " " + r.getDetail()).contains(requestId))
                .collect(Collectors.toList());
    }

    private static String describe(List<ChangeRecord> records) {
        return records.stream().map(r -> r.getType() + "/" + r.getUser() + "/" + r.getTarget() + "/" + r.getDetail())
                .collect(Collectors.toList()).toString();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
