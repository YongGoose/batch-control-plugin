package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.User;
import hudson.scm.NullSCM;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.TimerTrigger;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.scm.impl.SingleSCMSource;
import org.htmlunit.WebResponse;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.activationIds;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.assertBlocked;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.activate;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 1, scenarios L1-01 (activation request validation and refusals on decided
 * requests), L1-02 (activation pending timeout and the expiring notice) and L1-03 (deleting a
 * folder removes the activation of the jobs inside it). Matrix rows T-GAP-101 .. T-GAP-111
 * (note 276).
 *
 * <p>Basis: SPEC 6a "an activation is requested per job ({@code ACTIVATE}, with a reason and one
 * or more designated approvers) ... decided like a run request (designated approver,
 * self-approval rules, notifications D-36)", "Computed folders ... carry the activation for their
 * children ... the ACTIVATE/HOLD request is made on it", "deleting a job removes it" and the
 * security-13 line "a job re-created under a deleted job's name ... starts not activated"; SPEC 5
 * (an empty rejection reason is refused; a reason over 4,000 characters is refused); SPEC 7
 * (cancel only while PENDING; a pending request expires after the configured period and the
 * expiry is recorded); SPEC 3 (the requester may change the approvers until the decision);
 * SPEC 13 (EXPIRING fires once, {@code notifyBeforeExpiryMinutes} before the expiry; EXPIRED,
 * D-54). Time moves only through {@link BatchClock} (SPEC 8 "no timer dependency").
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md section 5 and the existing
 * activation fixtures only (no src/main knowledge).
 */
@WithJenkins
public class ActivationGapTest {

    private static final Instant T = Instant.parse("2026-09-21T08:00:00Z");

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    /** Records every notification (SPEC 13 extension point). */
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
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        NotificationCapture.clear();
    }

    // ------------------------------------------------------------------ L1-01

    /**
     * T-GAP-101 (L1-01; SPEC 6a, SPEC 5 "사유가 비어 있으면 요청 생성이 거부된다"): an ACTIVATE request
     * on J with a blank reason is refused through the form (4xx, the answer names the reason) and
     * through the service, and no activation request exists afterwards. Guard: the same request
     * with a reason is created.
     */
    @Test
    public void t_gap_101_blankActivationReasonIsRefused() throws Exception {
        FreeStyleProject jobJ = clearedJob("gap-act-j");
        assertFalse(isActivated(jobJ), "premise: J was created under run control and is not activated");

        WebResponse blank = submitActivation(j, "r", jobJ, "ACTIVATE", "   ", "a1");
        assertClientError(blank, "an ACTIVATE request with a blank reason");
        UsabilityFixtures.assertPlainRefusal("the blank-reason refusal", blank.getContentAsString(),
                Pattern.compile("(?i)reason"));
        try (ACLContext ignored = as("r")) {
            assertThrows(RuntimeException.class, () -> ActivationService.get().create(jobJ,
                    ActivationRequest.Action.ACTIVATE, "", List.of("a1")), "the service must refuse an empty reason");
        }
        assertTrue(activationIds().isEmpty(), "a refused submission must store no activation request: " + activationIds());

        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "nightly close goes live", "a1");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "guard: with a reason the request is created");
    }

    /**
     * T-GAP-102 (L1-01; SPEC 5 "사유가 4,000자를 초과 ... 거부", applied by SPEC 6a "decided like a run
     * request"): a reason of 4,001 characters is refused and stores nothing; one of exactly 4,000
     * characters is accepted and stored verbatim.
     */
    @Test
    public void t_gap_102_activationReasonLengthLimit() throws Exception {
        FreeStyleProject jobJ = clearedJob("gap-act-len");
        String over = "r".repeat(4001);
        String exact = "x".repeat(4000);

        assertClientError(submitActivation(j, "r", jobJ, "ACTIVATE", over, "a1"), "a 4,001-character reason");
        try (ACLContext ignored = as("r")) {
            assertThrows(RuntimeException.class, () -> ActivationService.get().create(jobJ,
                    ActivationRequest.Action.ACTIVATE, over, List.of("a1")), "the service must refuse a 4,001-character reason");
        }
        assertTrue(activationIds().isEmpty(), "nothing may be stored for an over-long reason: " + activationIds());

        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", exact, "a1");
        assertEquals(exact, ActivationService.get().load(id).getReason(), "a 4,000-character reason is accepted and kept");
    }

    /**
     * T-GAP-103 (L1-01; SPEC 6a "a computed child passes only if its nearest computed-folder
     * ancestor is activated ... the ACTIVATE/HOLD request is made on it"): ACTIVATE and HOLD
     * requests on a multibranch project's branch job are refused, the refusal names the
     * multibranch project as the item to request it on, and nothing is stored. Guard: the same
     * ACTIVATE on the multibranch project itself is accepted.
     */
    @Test
    public void t_gap_103_requestOnComputedChildNamesTheComputedFolder() throws Exception {
        WorkflowMultiBranchProject mb = j.jenkins.createProject(WorkflowMultiBranchProject.class, "gap-mb");
        WorkflowJob branch = index(mb);

        for (ActivationRequest.Action action : ActivationRequest.Action.values()) {
            RuntimeException refused;
            try (ACLContext ignored = as("r")) {
                refused = assertThrows(RuntimeException.class, () -> ActivationService.get().create(branch, action,
                        "branch " + action, List.of("a1")), action + " on the branch job must be refused");
            }
            assertNotNull(refused.getMessage(), action + ": the refusal must say why");
            assertTrue(refused.getMessage().contains("gap-mb"), action + ": the refusal must name gap-mb as the item to request it on: "
                    + refused.getMessage());

            WebResponse http = submitActivation(j, "r", branch, action.name(), "branch " + action, "a1");
            assertTrue(http.getStatusCode() >= 400, action + " through the branch job's URL must not be accepted, got HTTP "
                    + http.getStatusCode() + ": " + excerpt(http.getContentAsString()));
        }
        assertTrue(activationIds().isEmpty(), "no request may be stored for a computed child: " + activationIds());

        String id = submitActivationOk(j, "r", mb, "ACTIVATE", "put gap-mb into service", "a1");
        assertEquals("gap-mb", ActivationService.get().load(id).getJobFullName(), "guard: the request is made on the multibranch project");
    }

    /**
     * T-GAP-104 (L1-01; SPEC 6a "An approved hold marks the job not activated"): a HOLD request on
     * J, which is not activated, is refused and stores nothing; a HOLD on the activated job K is
     * accepted (PENDING).
     */
    @Test
    public void t_gap_104_holdOnANotActivatedJobIsRefused() throws Exception {
        FreeStyleProject jobJ = clearedJob("gap-hold-j");
        FreeStyleProject jobK = clearedJob("gap-hold-k");
        activate(jobK, "r", "a1");
        assertFalse(isActivated(jobJ), "premise: J is not activated");
        Set<String> before = activationIds();

        WebResponse refused = submitActivation(j, "r", jobJ, "HOLD", "pause it", "a1");
        assertClientError(refused, "a HOLD on a job that is not activated");
        UsabilityFixtures.assertPlainRefusal("the HOLD refusal", refused.getContentAsString(), null);
        assertEquals(before, activationIds(), "a refused HOLD must store nothing");

        String id = submitActivationOk(j, "r", jobK, "HOLD", "vendor outage", "a2");
        assertEquals(ActivationRequest.Action.HOLD, ActivationService.get().load(id).getAction());
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "guard: a HOLD on the activated K is accepted");
    }

    /**
     * T-GAP-105 (L1-01; SPEC 5 "반려 사유가 비어 있으면 반려가 거부된다"; SPEC 6 usability "invalid
     * input is refused with a message next to the field"): a1 posts the reject form of a pending
     * activation request with an empty comment (crumb); it is refused (4xx, a plain message, no
     * crash page) and the request stays PENDING. Guard: the same form with a comment rejects it.
     */
    @Test
    public void t_gap_105_rejectFormWithEmptyCommentIsRefused() throws Exception {
        FreeStyleProject jobJ = clearedJob("gap-rej-empty");
        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");

        WebResponse empty = decideActivation(j, "a1", id, "reject", "");
        assertClientError(empty, "a rejection with an empty comment");
        UsabilityFixtures.assertPlainRefusal("the empty-comment refusal", empty.getContentAsString(), null);
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "the request must stay PENDING");

        assertSuccess(decideActivation(j, "a1", id, "reject", "missing runbook"), "guard: a rejection with a comment");
        assertEquals(RequestStatus.REJECTED, ActivationService.get().load(id).getStatus());
    }

    /**
     * T-GAP-106 (L1-01; SPEC 4 state machine, SPEC 7 "취소는 ... PENDING 상태에서만 가능하다", SPEC 3
     * "결재 전까지 요청자가 결재자를 바꿀 수 있고"): on an APPROVED and on a CANCELLED activation
     * request, rejecting (designated approver), cancelling (requester) and changing the approvers
     * (requester) are each refused with a message, and the stored request file (status and
     * history) is unchanged byte for byte. Guard: a third, PENDING request accepts the approver
     * change.
     */
    @Test
    public void t_gap_106_decidedActivationRequestsRefuseFurtherChanges() throws Exception {
        FreeStyleProject jobJ = clearedJob("gap-decided");
        String approved = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "a1", approved, "approve", "ok"), "fixture: approval");
        assertEquals(RequestStatus.APPROVED, ActivationService.get().load(approved).getStatus());

        FreeStyleProject jobK = clearedJob("gap-cancelled");
        String cancelled = submitActivationOk(j, "r", jobK, "ACTIVATE", "go live", "a1");
        assertSuccess(decideActivation(j, "r", cancelled, "cancel", ""), "fixture: the requester's cancel");
        assertEquals(RequestStatus.CANCELLED, ActivationService.get().load(cancelled).getStatus());

        for (String id : new String[] {approved, cancelled}) {
            RequestStatus status = ActivationService.get().load(id).getStatus();
            String fileBefore = requestFile(id);
            assertRefusedWithMessage(id + " reject", "a1", () -> ActivationService.get().reject(id, "too late"));
            assertRefusedWithMessage(id + " cancel", "r", () -> ActivationService.get().cancel(id));
            assertRefusedWithMessage(id + " change approvers", "r", () -> ActivationService.get().changeApprovers(id, List.of("a2")));
            assertClientError(decideActivation(j, "a1", id, "reject", "too late"), id + ": the reject form");
            assertClientError(decideActivation(j, "r", id, "cancel", ""), id + ": the cancel form");
            assertEquals(status, ActivationService.get().load(id).getStatus(), id + ": the status must not change");
            assertEquals(fileBefore, requestFile(id), id + ": the stored request (status and history) must not change");
        }
        assertTrue(isActivated(jobJ), "the approved request's activation stays");
        assertFalse(isActivated(jobK), "the cancelled request activated nothing");

        FreeStyleProject jobM = clearedJob("gap-pending");
        String pending = submitActivationOk(j, "r", jobM, "ACTIVATE", "go live", "a1");
        try (ACLContext ignored = as("r")) {
            ActivationService.get().changeApprovers(pending, List.of("a2"));
        }
        assertEquals(List.of("a2"), ActivationService.get().load(pending).getApprovers(), "guard: a PENDING request accepts the change");
    }

    /**
     * T-GAP-107 (L1-01; SPEC 6 usability "no link leads to a 404"; SPEC 4 state machine): approving
     * an activation request id that does not exist is refused with IllegalArgumentException, and
     * the detail URL of an unknown id answers 404. Guard: a real request's detail page answers 200.
     */
    @Test
    public void t_gap_107_unknownActivationIdIsRefused() throws Exception {
        String unknown = UUID.randomUUID().toString();
        try (ACLContext ignored = as("a1")) {
            assertThrows(IllegalArgumentException.class, () -> ActivationService.get().approve(unknown, "ok"),
                    "approving an unknown id must be refused with IllegalArgumentException");
        }
        assertEquals(404, get(j, "a1", "batch-control/activations/" + unknown + "/").getStatusCode(),
                "an unknown activation request id must answer 404");

        FreeStyleProject jobJ = clearedJob("gap-known");
        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");
        assertEquals(200, get(j, "a1", "batch-control/activations/" + id + "/").getStatusCode(), "guard: a real request's page opens");
    }

    // ------------------------------------------------------------------ L1-02

    /**
     * T-GAP-108 (L1-02; SPEC 13 "EXPIRING ... fire once, notifyBeforeExpiryMinutes ... before the
     * expiry"; SPEC 6a notifications D-36): pending timeout 1 h, notice lead 10 min. At T+30 min the
     * periodic work sends no EXPIRING for the activation request (guard); at T+55 min it runs
     * twice and exactly one EXPIRING reaches the requester, with kind ACTIVATION.
     */
    @Test
    public void t_gap_108_activationExpiringNoticeFiresOnce() throws Exception {
        timeouts();
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));
        FreeStyleProject jobJ = clearedJob("gap-expiring");
        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");

        runExpiryWorkAt(T.plus(Duration.ofMinutes(30)));
        assertTrue(NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id).isEmpty(),
                "30 minutes before the expiry is too early for the notice");

        runExpiryWorkAt(T.plus(Duration.ofMinutes(55)));
        runExpiryWorkAt(T.plus(Duration.ofMinutes(55)));
        List<NotificationCapture> expiring = NotificationCapture.await(NotificationEvent.EXPIRING, id);
        expiring = NotificationCapture.afterQuietPeriod(NotificationEvent.EXPIRING, id);
        assertEquals(1, expiring.size(), "EXPIRING fires once for the activation request: " + NotificationCapture.describeAll());
        assertEquals(List.of("r"), expiring.get(0).recipients, "EXPIRING goes to the requester");
        assertEquals("ACTIVATION", expiring.get(0).kind, "the notice is about an activation request");
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "not expired yet at T+55");
    }

    /**
     * T-GAP-109 (L1-02; SPEC 7 "승인 대기 요청은 설정된 기간이 지나면 자동 만료 ... EXPIRED로 바뀌고";
     * SPEC 13 / D-54 EXPIRED): at T+61 min, before any periodic work, the designated approver's
     * approval is refused with a plain message about the timeout, the request is EXPIRED, an
     * EXPIRED event reaches the requester, and the job is still not activated (no ACTIVATED
     * record, its timer refused).
     */
    @Test
    public void t_gap_109_approvalAfterThePendingTimeoutIsRefused() throws Exception {
        timeouts();
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));
        FreeStyleProject jobJ = clearedJob("gap-late");
        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");

        BatchClock.setForTest(Clock.fixed(T.plus(Duration.ofMinutes(61)), ZoneOffset.UTC));
        WebResponse late = decideActivation(j, "a1", id, "approve", "ok");
        assertClientError(late, "an approval after the pending timeout");
        UsabilityFixtures.assertPlainRefusal("the late approval", late.getContentAsString(),
                Pattern.compile("(?i)time ?out|expire"));
        assertEquals(RequestStatus.EXPIRED, ActivationService.get().load(id).getStatus(), "the request must be EXPIRED");
        List<NotificationCapture> expired = NotificationCapture.await(NotificationEvent.EXPIRED, id);
        assertTrue(expired.stream().anyMatch(n -> n.recipients != null && n.recipients.contains("r")),
                "the requester receives EXPIRED (D-54): " + NotificationCapture.describeAll());
        assertFalse(isActivated(jobJ), "a refused approval must not activate the job");
        assertTrue(ActivationFixtures.recordsFor(ChangeType.ACTIVATED, "gap-late").isEmpty(), "no ACTIVATED record");
        assertNull(jobJ.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "the timer is still refused");
        assertBlocked(j, jobJ, 1, 0);
    }

    /**
     * T-GAP-110 (L1-02; SPEC 7 "만료 시 상태가 EXPIRED로 바뀌고 이력에 남는다"): a pending request is
     * left alone until the clock passes the timeout; the periodic work then marks it EXPIRED and
     * its detail page shows it as expired. Guard: before the timeout the same work leaves it
     * PENDING.
     */
    @Test
    public void t_gap_110_periodicWorkExpiresAPendingActivationRequest() throws Exception {
        timeouts();
        BatchClock.setForTest(Clock.fixed(T, ZoneOffset.UTC));
        FreeStyleProject jobJ = clearedJob("gap-expired");
        String id = submitActivationOk(j, "r", jobJ, "ACTIVATE", "go live", "a1");

        runExpiryWorkAt(T.plus(Duration.ofMinutes(59)));
        assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "guard: inside the timeout it stays PENDING");

        runExpiryWorkAt(T.plus(Duration.ofMinutes(62)));
        assertEquals(RequestStatus.EXPIRED, ActivationService.get().load(id).getStatus(), "past the timeout the work expires it");
        WebResponse page = get(j, "r", "batch-control/activations/" + id + "/");
        assertEquals(200, page.getStatusCode(), "the expired request's page still opens");
        assertTrue(page.getContentAsString().toLowerCase(Locale.ROOT).contains("expired"),
                "the history shows the request as expired: " + excerpt(page.getContentAsString()));
    }

    // ------------------------------------------------------------------ L1-03

    /**
     * T-GAP-111 (L1-03; SPEC 6a "deleting a job removes it", security-13 "a job re-created under a
     * deleted job's name ... starts not activated"; SPEC 6 usability "why a request was
     * invalidated"): folder F holds the activated F/A and F/B with a pending ACTIVATE request. The
     * administrator deletes F. The activation state file written for F/A is gone, F/B's request
     * is INVALIDATED with a reason that names the deleted folder, and a re-created F/A is not
     * activated: its timer submission is refused and recorded as TRIGGER_BLOCKED naming
     * activation.
     */
    @Test
    public void t_gap_111_deletingAFolderRemovesTheActivationsInside() throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, "gap-fold");
        FreeStyleProject jobA = cleared(folder.createProject(FreeStyleProject.class, "a"));
        FreeStyleProject jobB = cleared(folder.createProject(FreeStyleProject.class, "b"));
        activate(jobA, "r", "a1");
        String stateA = stateFileOf("gap-fold/a");
        assertNotNull(stateA, "fixture: activating gap-fold/a writes activations/<encoded name>.xml (ARCHITECTURE 5); activations/ holds "
                + stateFiles());
        String pendingB = submitActivationOk(j, "r", jobB, "ACTIVATE", "go live", "a1");

        try (ACLContext ignored = as("admin")) {
            folder.delete();
        }
        Set<String> after = stateFiles();
        assertFalse(after.contains(stateA), "deleting the folder must remove gap-fold/a's activation state " + stateA
                + "; activations/ holds " + after);
        ActivationRequest invalidated = ActivationService.get().load(pendingB);
        assertEquals(RequestStatus.INVALIDATED, invalidated.getStatus(), "the pending request of a job deleted with its folder is INVALIDATED");
        assertNotNull(invalidated.getDecisionComment(), "the invalidation must give its reason");
        assertTrue(invalidated.getDecisionComment().contains("gap-fold"), "the reason must name the deleted folder: "
                + invalidated.getDecisionComment());

        Folder again = j.jenkins.createProject(Folder.class, "gap-fold");
        FreeStyleProject recreated = cleared(again.createProject(FreeStyleProject.class, "a"));
        assertFalse(isActivated(recreated), "a job re-created under a deleted job's name starts not activated");
        assertNull(recreated.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()), "its timer is refused");
        assertBlocked(j, recreated, 1, 0);
        List<ChangeRecord> blocked = ActivationFixtures.recordsFor(ChangeType.TRIGGER_BLOCKED, "gap-fold/a");
        assertTrue(blocked.stream().anyMatch(rec -> rec.getDetail() != null
                        && rec.getDetail().toLowerCase(Locale.ROOT).contains("activation")),
                "the refusal is recorded as TRIGGER_BLOCKED naming activation: "
                        + blocked.stream().map(ChangeRecord::getDetail).collect(Collectors.toList()));
    }

    // ------------------------------------------------------------------ helpers

    private void timeouts() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.setNotifyBeforeExpiryMinutes(10);
        cfg.save();
        assertEquals(10, BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes(), "premise: notice lead 10 minutes");
    }

    /** A job created under run control with its timer and upstream switches cleared, so only activation decides. */
    private FreeStyleProject clearedJob(String name) throws Exception {
        return cleared(j.createFreeStyleProject(name));
    }

    private static FreeStyleProject cleared(FreeStyleProject job) throws Exception {
        BatchControlJobProperty property = new BatchControlJobProperty(true);
        property.setBlockTimer(false);
        property.setBlockUpstream(false);
        setBatchControl(job, property);
        return job;
    }

    private WorkflowJob index(WorkflowMultiBranchProject mb) throws Exception {
        mb.getSourcesList().add(new BranchSource(new SingleSCMSource("main", new NullSCM())));
        Queue.Item indexing = mb.scheduleBuild2(0);
        assertNotNull(indexing, "fixture: branch indexing must be schedulable");
        indexing.getFuture().get();
        j.waitUntilNoActivity();
        WorkflowJob branch = mb.getItem("main");
        assertNotNull(branch, "fixture: indexing must have created the branch job");
        return branch;
    }

    private String requestFile(String id) throws Exception {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("activation-requests").resolve(id + ".xml");
        assertTrue(Files.isRegularFile(file), "fixture: the activation request is stored at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Set<String> stateFiles() throws Exception {
        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control").resolve("activations");
        Set<String> out = new TreeSet<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".xml")).forEach(out::add);
            }
        }
        return out;
    }

    /** The file of {@code activations/} whose URL-decoded name is {@code <fullName>.xml} (ARCHITECTURE 5: {@code /} is {@code %2F}), or null. */
    private String stateFileOf(String fullName) throws Exception {
        for (String name : stateFiles()) {
            if (java.net.URLDecoder.decode(name, StandardCharsets.UTF_8).equals(fullName + ".xml")) {
                return name;
            }
        }
        return null;
    }

    private static void assertRefusedWithMessage(String what, String user, org.junit.jupiter.api.function.Executable action) {
        RuntimeException refused;
        try (ACLContext ignored = as(user)) {
            refused = assertThrows(RuntimeException.class, action, what + " must be refused");
        }
        assertTrue(refused.getMessage() != null && !refused.getMessage().isBlank(), what + ": the refusal must carry a message");
    }

    private static void runExpiryWorkAt(Instant at) throws Exception {
        BatchClock.setForTest(Clock.fixed(at, ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static ACLContext as(String userId) {
        return ACL.as2(User.getById(userId, true).impersonate2());
    }
}
