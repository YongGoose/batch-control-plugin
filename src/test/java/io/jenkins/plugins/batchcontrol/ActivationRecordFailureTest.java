package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ActivationFixtures.decideActivation;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.isActivated;
import static io.jenkins.plugins.batchcontrol.ActivationFixtures.submitActivationOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed change-record append never breaks the activation decision it records (TEST-MATRIX note 305,
 * rows T-06a-66 and T-06a-67). With {@code batch-control/changes/} unwritable, the designated approver's
 * approval of an ACTIVATE (or a HOLD) request still answers without an error, the request is APPROVED,
 * the job's activation state is applied, the job's other pending activation requests are invalidated
 * (security-13 S-13-07) and the approval notification is dispatched; the failed {@code ACTIVATED} /
 * {@code HELD} record is logged at WARNING or SEVERE.
 *
 * <p>Basis: SPEC 6a ("Approval marks the job activated and writes an {@code ACTIVATED} change record";
 * "An approved hold marks the job not activated and writes a {@code HELD} change record"; "decided like a
 * run request (... notifications D-36)"; "approving one request invalidates the job's other pending
 * activation requests. Notifications name the action"), the contract of bug hunt A R3-01 and D-42 (a
 * failure to append the change record never stops, reverses or half-applies the operation it records; it
 * is logged at WARNING or above; the request does not answer 500) and ARCHITECTURE 5 ({@code changes/}).
 * Each row first runs the same flow on another job with a writable store (the guard), so a failure under
 * the fault is the fault's doing.
 *
 * <p>Written from docs/SPEC.md item 6a, docs/DECISIONS.md D-42 and docs/ARCHITECTURE.md section 5 only
 * (no src/main knowledge).
 */
@WithJenkins
public class ActivationRecordFailureTest {

    private JenkinsRule j;

    /** Records every notification for the rows of this class. */
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
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.save();
    }

    /**
     * T-06a-66 (P0): guard: on {@code guard-act} with a writable store, a1 approves the first of u1's three
     * ACTIVATE requests (the other two designate a2): the answer is a success, the job is activated, the other
     * two requests are INVALIDATED and the APPROVED notification arrives. Then the same on {@code act-x} with
     * {@code batch-control/changes/} unwritable: the answer is below 400 (no 500), the request is APPROVED,
     * the job is activated, the other two requests are INVALIDATED, the APPROVED notification naming ACTIVATE
     * arrives, and a WARNING or SEVERE log record is written.
     */
    @Test
    public void t_06a_66_approvedActivateAppliesWhenTheRecordCannotBeWritten() throws Exception {
        FreeStyleProject guard = idleJob("guard-act");
        Lane guardLane = Lane.of(j, guard, "ACTIVATE");
        WebResponse guardAnswer = decideActivation(j, "a1", guardLane.decided, "approve", "go");
        assertTrue(guardAnswer.getStatusCode() < 400, "guard: with a writable store the approval succeeds, got HTTP "
                + guardAnswer.getStatusCode() + ": " + excerpt(guardAnswer.getContentAsString()));
        guardLane.assertApplied(true, "guard");

        FreeStyleProject job = idleJob("act-x");
        Lane lane = Lane.of(j, job, "ACTIVATE");
        List<String> problems = approveUnderFault(lane.decided, "ACTIVATE");
        lane.assertApplied(true, "R3-01 (ACTIVATE)");
        assertFalse(problems.isEmpty(), "R3-01: the failed ACTIVATED record must be logged at WARNING or SEVERE");
    }

    /**
     * T-06a-67 (P0): as T-06a-66 for a HOLD. Guard: on {@code guard-hold} (activated) a1 approves the first of
     * u1's three HOLD requests: success, the job is held, the other two INVALIDATED, the APPROVED notification
     * arrives. Then on {@code hold-x} (activated) with {@code batch-control/changes/} unwritable: below 400,
     * APPROVED, the job is not activated, the other two INVALIDATED, the APPROVED notification naming HOLD
     * arrives, a WARNING or SEVERE log record.
     */
    @Test
    public void t_06a_67_approvedHoldAppliesWhenTheRecordCannotBeWritten() throws Exception {
        FreeStyleProject guard = idleJob("guard-hold");
        BatchControlFixtures.activate(guard, "u1", "a1");
        Lane guardLane = Lane.of(j, guard, "HOLD");
        WebResponse guardAnswer = decideActivation(j, "a1", guardLane.decided, "approve", "hold");
        assertTrue(guardAnswer.getStatusCode() < 400, "guard: with a writable store the approval succeeds, got HTTP "
                + guardAnswer.getStatusCode() + ": " + excerpt(guardAnswer.getContentAsString()));
        guardLane.assertApplied(false, "guard");

        FreeStyleProject job = idleJob("hold-x");
        BatchControlFixtures.activate(job, "u1", "a1");
        Lane lane = Lane.of(j, job, "HOLD");
        List<String> problems = approveUnderFault(lane.decided, "HOLD");
        lane.assertApplied(false, "R3-01 (HOLD)");
        assertFalse(problems.isEmpty(), "R3-01: the failed HELD record must be logged at WARNING or SEVERE");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Three PENDING requests by u1 for the same action on one job: the one a1 will decide and two that
     * designate a2. (A HOLD cannot be filed for a job that is not activated, nor an ACTIVATE for one that is,
     * so the job's other pending activation requests are always of the same action.)
     */
    private static final class Lane {
        final FreeStyleProject job;
        final String action;
        final String decided;
        final List<String> others;

        private Lane(FreeStyleProject job, String action, String decided, List<String> others) {
            this.job = job;
            this.action = action;
            this.decided = decided;
            this.others = others;
        }

        static Lane of(JenkinsRule j, FreeStyleProject job, String action) throws Exception {
            String decided = submitActivationOk(j, "u1", job, action, "first " + action, "a1");
            List<String> others = List.of(
                    submitActivationOk(j, "u1", job, action, "second " + action, "a2"),
                    submitActivationOk(j, "u1", job, action, "third " + action, "a2"));
            for (String id : List.of(decided, others.get(0), others.get(1))) {
                assertEquals(RequestStatus.PENDING, ActivationService.get().load(id).getStatus(), "premise: " + id + " is PENDING");
            }
            return new Lane(job, action, decided, others);
        }

        void assertApplied(boolean activated, String what) throws InterruptedException {
            assertEquals(RequestStatus.APPROVED, ActivationService.get().load(decided).getStatus(),
                    what + ": the approved request must be APPROVED");
            assertEquals(activated, isActivated(job), what + ": the approved " + action + " must be applied: "
                    + job.getFullName() + (activated ? " activated" : " held (not activated)"));
            for (String other : others) {
                assertEquals(RequestStatus.INVALIDATED, ActivationService.get().load(other).getStatus(),
                        what + ": the job's other pending " + action + " request " + other + " must be INVALIDATED (security-13 S-13-07)");
            }
            List<NotificationCapture> approved = NotificationCapture.await(NotificationEvent.APPROVED, decided);
            assertTrue(approved.stream().anyMatch(n -> action.equals(n.action)), what + ": the APPROVED notification must name "
                    + action + ": " + NotificationCapture.describeAll());
        }
    }

    /** a1 approves {@code requestId} while {@code changes/} is unwritable; returns the WARNING+ log lines. */
    private List<String> approveUnderFault(String requestId, String action) throws Exception {
        try (RecordFaultFixtures.Fault ignored = RecordFaultFixtures.makeUnwritable(
                RecordFaultFixtures.changesDir(j.jenkins.getRootDir().toPath()));
             LogRecorder log = new LogRecorder().record("io.jenkins.plugins.batchcontrol", Level.WARNING).capture(200)) {
            WebResponse answer = decideActivation(j, "a1", requestId, "approve", "decided while the record cannot be written");
            int code = answer.getStatusCode();
            assertTrue(code < 400, "R3-01: the approval of the " + action + " request must complete although its change record"
                    + " cannot be written (no HTTP 500), got HTTP " + code + ": " + excerpt(answer.getContentAsString()));
            return log.getRecords().stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(r -> r.getLevel() + " " + r.getMessage())
                    .collect(Collectors.toList());
        }
    }

    /** A job created under run control with its switches cleared; not activated (premise). */
    private FreeStyleProject idleJob(String name) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(name);
        BatchControlJobProperty cleared = new BatchControlJobProperty(true);
        cleared.setBlockTimer(false);
        cleared.setBlockUpstream(false);
        BatchControlFixtures.setBatchControl(job, cleared);
        assertFalse(isActivated(job), "premise: a job created under run control is not activated");
        return job;
    }
}
