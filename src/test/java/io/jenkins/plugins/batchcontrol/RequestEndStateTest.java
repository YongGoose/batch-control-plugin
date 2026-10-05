package io.jenkins.plugins.batchcontrol;

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import jakarta.mail.Message;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests that end without the requester's decision (e2e-04 FD-04, FD-05, FD-06): SPEC line
 * ending (D-54) (notifications for EXPIRED, INVALIDATED and CANCELLED), SPEC line ending (D-55)
 * (approving a run of a disabled job) and SPEC 7 / the usability line (an expired request says
 * why). Matrix rows T-13-21 .. T-13-23, T-05-20 and T-07-10 (note 168).
 *
 * <p>Mail goes through mock-javamail as in EmailNotificationTest; a mail is recognised by the
 * request id plus a word for the event ("expired", "invalid" or "renam", "cancel"). Time moves
 * through {@link BatchClock} and the expiry work is run directly (matrix note 2).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-54/D-55, docs/reports/e2e-04.md and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RequestEndStateTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");
    private static final String U1_MAIL = "requester.one@example.com";
    private static final String A1_MAIL = "alpha.one@example.com";

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        Mailbox.clearAll();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1"));
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        User.getById("a1", true).addProperty(new Mailer.UserProperty(A1_MAIL));
        cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        job = j.createFreeStyleProject("end-x");
        setBatchControl(job, new BatchControlJobProperty(true));
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        Mailbox.clearAll();
    }

    /** T-13-21 (D-54): a pending request that expires mails the requester and the designated approver. */
    @Test
    public void t_13_21_pendingExpiryMailsRequesterAndApprover() throws Exception {
        mailOn();
        cfg.setPendingTimeoutHours(1);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        settleCreationMail();

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(id).getStatus(), "fixture: the request expired");

        assertMailArrives(U1_MAIL, id, "expired");
        assertMailArrives(A1_MAIL, id, "expired");
    }

    /** T-13-22 (D-54): renaming the job invalidates the pending request and mails requester and approver. */
    @Test
    public void t_13_22_invalidationMailsRequesterAndApprover() throws Exception {
        mailOn();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        settleCreationMail();
        job.renameTo("end-x-renamed");
        assertEquals(RequestStatus.INVALIDATED, RunRequestService.get().load(id).getStatus(), "fixture: invalidated");

        assertMailArrives(U1_MAIL, id, "invalid", "renam");
        assertMailArrives(A1_MAIL, id, "invalid", "renam");
    }

    /** T-13-23 (D-54): the requester's cancel mails the approver and not the requester. */
    @Test
    public void t_13_23_cancelMailsTheApproverButNotTheRequester() throws Exception {
        mailOn();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        settleCreationMail();
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("u1");
        WebResponse cancelled = wc.getPage(new WebRequest(wc.createCrumbedUrl("batch-control/requests/" + id + "/cancel"),
                HttpMethod.POST)).getWebResponse();
        assertTrue(cancelled.getStatusCode() < 400, "fixture: the requester's cancel must succeed");
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(id).getStatus(), "fixture: cancelled");

        assertMailArrives(A1_MAIL, id, "cancel");
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS);
        assertTrue(mailsAbout(U1_MAIL, id, "cancel").isEmpty(), "the requester is not mailed about their own cancel");
    }

    /**
     * T-05-20 (D-55, FD-06): while the job is disabled, the decision form says so, Approve is
     * refused with a message naming the disabled job and the request stays PENDING; Reject still
     * works.
     */
    @Test
    public void t_05_20_approveOfDisabledJobIsRefusedAndRejectWorks() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        job.disable();

        String form = get(j, "a1", "batch-control/requests/" + id + "/").getContentAsString();
        assertTrue(form.toLowerCase(Locale.ROOT).contains("disabled"), "the decision form must say the job is disabled: "
                + excerpt(form));

        WebResponse approve = decideRun(j, "a1", id, "approve", "ok");
        assertEquals(RequestStatus.PENDING, RunRequestService.get().load(id).getStatus(),
                "Approve must be refused while the job is disabled");
        String text = approve.getContentAsString();
        assertTrue(text.toLowerCase(Locale.ROOT).contains("disabled"), "the refusal must say the job is disabled: "
                + excerpt(text));
        UsabilityFixtures.assertPlainRefusal("approve of a disabled job", text, null);

        WebResponse reject = decideRun(j, "a1", id, "reject", "not now");
        assertTrue(reject.getStatusCode() < 400, "Reject must stay possible, got " + reject.getStatusCode());
        assertEquals(RequestStatus.REJECTED, RunRequestService.get().load(id).getStatus(), "the request must be rejected");
        j.waitUntilNoActivity();
        assertTrue(job.getBuilds().isEmpty(), "no build may have run");
    }

    /**
     * T-07-10 (FD-05): an expired request says why. A pending request past pendingTimeoutHours
     * names the missing decision; an approved request that never reached the queue names that it
     * was not run in time. The two reasons differ.
     */
    @Test
    public void t_07_10_expiredRequestShowsItsReason() throws Exception {
        cfg.setPendingTimeoutHours(1);
        cfg.setApprovedRunTimeoutMinutes(60);
        cfg.save();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        String pending = submitRunOk(j, "u1", job, "left pending", "a1");
        String approved = submitRunOk(j, "u1", job, "approved, not run", "a1");
        // the queue refuses the approved submission: approved, never queued, and no cancelled
        // queue item (D-72b (7); note 265)
        QueueRefusalFixtures.refusedBeforeTheGate(job,
                () -> assertTrue(decideRun(j, "a1", approved, "approve", "ok").getStatusCode() < 400, "fixture: approval"));

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(pending).getStatus(), "fixture");
        assertEquals(RequestStatus.EXPIRED, RunRequestService.get().load(approved).getStatus(), "fixture");

        String pendingReason = reason(pending);
        String approvedReason = reason(approved);
        assertTrue(Pattern.compile("(?i)decision|decided|timeout|\\d+\\s*hours?").matcher(pendingReason).find(),
                "the expired pending request must say it was not decided in time: " + pendingReason);
        assertTrue(Pattern.compile("(?i)not (been )?(queued|started|run|executed)|\\d+\\s*minutes?|timeout")
                .matcher(approvedReason).find(), "the expired approved request must say it was not run in time: "
                + approvedReason);
        assertFalse(pendingReason.equals(approvedReason), "the two kinds of expiry must give different reasons");
    }

    // ---------------------------------------------------------------- helpers

    /** Refuses armed jobs before Batch Control's queue gate (QueueRefusalFixtures, note 265). */
    @TestExtension
    public static final class RefuseBeforeGate extends QueueRefusalFixtures.RefusingHandler {
    }

    /** Waits for the approver's REQUEST_CREATED mail, then empties every mailbox, so later mails are the event's. */
    private static void settleCreationMail() throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        while (Mailbox.get(A1_MAIL).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery
        }
        assertFalse(Mailbox.get(A1_MAIL).isEmpty(), "fixture: the approver must be mailed about the new request");
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS);
        Mailbox.clearAll();
    }

    private void mailOn() {
        cfg.setEmailNotifications(true);
        cfg.save();
    }

    /** The text after "expired" on the request's screen, as u1 (who may see it). */
    private String reason(String id) throws Exception {
        String text = UsabilityFixtures.htmlPage(j, "u1", "batch-control/requests/" + id + "/").asNormalizedText();
        int at = text.toLowerCase(Locale.ROOT).indexOf("expired");
        assertTrue(at >= 0, "fixture: the screen must show EXPIRED: " + excerpt(text));
        String tail = text.substring(at);
        return tail.length() > 600 ? tail.substring(0, 600) : tail;
    }

    private static void assertMailArrives(String address, String id, String... words) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        while (mailsAbout(address, id, words).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery
        }
        assertFalse(mailsAbout(address, id, words).isEmpty(), address + " must receive a mail about " + id + " naming "
                + Arrays.toString(words) + "; mailbox held " + Mailbox.get(address).size() + " message(s)");
    }

    /** Mails at {@code address} whose subject or body names {@code id} and one of {@code words}. */
    private static List<Message> mailsAbout(String address, String id, String... words) throws Exception {
        List<Message> out = new ArrayList<>();
        for (Message message : new ArrayList<>(Mailbox.get(address))) {
            Object content = message.getContent();
            String text = (message.getSubject() + "\n" + content).toLowerCase(Locale.ROOT);
            if (!text.contains(id.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String word : words) {
                if (text.contains(word)) {
                    out.add(message);
                    break;
                }
            }
        }
        return out;
    }
}
