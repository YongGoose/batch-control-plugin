package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import jakarta.mail.Message;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideRun;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.get;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 13, D-36: the shipped e-mail notifier. Matrix rows T-13-12 .. T-13-16 (the
 * without-Mailer row T-13-17 is {@link EmailNotificationWithoutMailerTest}).
 *
 * <p>Mail goes through the Mailer plugin into mock-javamail's {@link Mailbox}. Every user has
 * a Mailer address that cannot be derived from the user id, so a row that finds a message in
 * {@code alpha.one@example.com} proves the recipient's Mailer address was used.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-36 and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class EmailNotificationTest {

    private static final String U1_MAIL = "requester.one@example.com";
    private static final String A1_MAIL = "alpha.one@example.com";
    private static final String A2_MAIL = "alpha.two@example.com";
    private static final String A3_MAIL = "alpha.three@example.com";

    private JenkinsRule j;
    private FreeStyleProject job;
    private BatchControlGlobalConfiguration cfg;

    /** Proves that the events fire in the rows where no mail may be sent. */
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
        Mailbox.clearAll();
        NotificationCapture.clear();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, Item.BUILD, BatchControlPermissions.REQUEST,
                        BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2", "a3"));
        mailAddress("u1", U1_MAIL);
        mailAddress("a1", A1_MAIL);
        mailAddress("a2", A2_MAIL);
        mailAddress("a3", A3_MAIL);

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
        Mailbox.clearAll();
        NotificationCapture.clear();
    }

    /**
     * T-13-12: emailNotifications defaults to false and notifyBeforeExpiryMinutes to 10; both
     * round-trip, and with Mailer installed the option is on the global configuration page.
     */
    @Test
    public void t_13_12_emailOptionDefaultsAndIsOfferedWithMailer() throws Exception {
        assertFalse(cfg.isEmailNotifications(), "emailNotifications default (an upgrade changes nothing)");
        assertEquals(10, cfg.getNotifyBeforeExpiryMinutes(), "notifyBeforeExpiryMinutes default");

        cfg.setEmailNotifications(true);
        cfg.setNotifyBeforeExpiryMinutes(15);
        cfg.save();
        cfg.load();
        assertTrue(BatchControlGlobalConfiguration.get().isEmailNotifications());
        assertEquals(15, BatchControlGlobalConfiguration.get().getNotifyBeforeExpiryMinutes());

        String page = get(j, "admin", "configure").getContentAsString();
        assertTrue(page.contains("emailNotifications"), "with Mailer installed the global configuration must offer emailNotifications");
        assertTrue(page.contains("notifyBeforeExpiryMinutes"), "the global configuration must offer notifyBeforeExpiryMinutes");
    }

    /** T-13-13: with emailNotifications off (default) an event fires but no mail is sent to anyone. */
    @Test
    public void t_13_13_noMailWhileTheOptionIsOff() throws Exception {
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1", "a2");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id); // premise: the event fired
        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the approval");
        NotificationCapture.await(NotificationEvent.APPROVED, id);
        j.waitUntilNoActivity();

        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS); // bounded wait for a mail that must not come
        for (String address : new String[] {U1_MAIL, A1_MAIL, A2_MAIL, A3_MAIL}) {
            assertTrue(Mailbox.get(address).isEmpty(), "no mail may be sent to " + address + " while emailNotifications is off");
        }
    }

    /**
     * T-13-14: with the option on, a new run request mails each designated approver at their
     * Mailer address and nobody else; the message is plain text with the request id, the job,
     * the requester, the reason verbatim and a link to the request.
     */
    @Test
    public void t_13_14_requestCreatedMailsTheDesignatedApprovers() throws Exception {
        cfg.setEmailNotifications(true);
        cfg.save();
        String reason = "month-end close & reconcile";
        String id = submitRunOk(j, "u1", job, reason, "a1", "a2");

        for (String address : new String[] {A1_MAIL, A2_MAIL}) {
            Message message = awaitSingleMail(address);
            String body = plainTextBody(message, address);
            assertTrue(body.contains(id), address + ": the body must carry the request id; body was: " + body);
            assertTrue(body.contains("batch-x"), address + ": the body must carry the job; body was: " + body);
            assertTrue(body.contains("u1"), address + ": the body must carry the requester; body was: " + body);
            assertTrue(body.contains(reason), address + ": the body must carry the reason verbatim (plain text, no escaping); body was: " + body);
            assertTrue(body.contains("batch-control/requests/" + id), address + ": the body must carry a link to the request; body was: " + body);
            assertFalse(body.toLowerCase(Locale.ROOT).contains("<html") || body.toLowerCase(Locale.ROOT).contains("<a "),
                    address + ": the body must be plain text; body was: " + body);
        }
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS);
        assertTrue(Mailbox.get(A3_MAIL).isEmpty(), "a3 is a listed approver outside the set and must not be mailed");
        assertTrue(Mailbox.get(U1_MAIL).isEmpty(), "the requester is not a recipient of REQUEST_CREATED");
    }

    /** T-13-15: with the option on, an approval mails the requester (and not the approvers again). */
    @Test
    public void t_13_15_approvalMailsTheRequester() throws Exception {
        cfg.setEmailNotifications(true);
        cfg.save();
        String id = submitRunOk(j, "u1", job, "month-end batch", "a1");
        awaitSingleMail(A1_MAIL);

        assertSuccess(decideRun(j, "a1", id, "approve", "ok"), "the approval");
        Message message = awaitSingleMail(U1_MAIL);
        String body = plainTextBody(message, U1_MAIL);
        assertTrue(body.contains(id), "the body must carry the request id; body was: " + body);
        assertTrue(body.contains("batch-control/requests/" + id), "the body must carry a link; body was: " + body);
        j.waitUntilNoActivity();
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS);
        assertEquals(1, Mailbox.get(A1_MAIL).size(), "the approver's mailbox must hold only the REQUEST_CREATED mail");
    }

    /** T-13-16: a change request mail names the scope and links to the grant request. */
    @Test
    public void t_13_16_grantRequestMailNamesTheScope() throws Exception {
        cfg.setEmailNotifications(true);
        cfg.save();
        String id = submitGrantOk(j, "u1", "batch-x", Arrays.asList("CONFIGURE"), 30,
                "fix the cron expression", null, "a2");

        String body = plainTextBody(awaitSingleMail(A2_MAIL), A2_MAIL);
        assertTrue(body.contains(id), "the body must carry the request id; body was: " + body);
        assertTrue(body.contains("batch-x"), "the body must carry the scope; body was: " + body);
        assertTrue(body.contains("u1"), "the body must carry the requester; body was: " + body);
        assertTrue(body.contains("fix the cron expression"), "the body must carry the reason; body was: " + body);
        assertTrue(body.contains("batch-control/grants/" + id), "the body must link to the grant request; body was: " + body);
    }

    // ---------------------------------------------------------------- helpers

    private static void mailAddress(String userId, String address) throws Exception {
        User.getById(userId, true).addProperty(new Mailer.UserProperty(address));
    }

    private static Message awaitSingleMail(String address) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<Message> box = Mailbox.get(address);
        while (box.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery
        }
        assertEquals(1, box.size(), "exactly one mail must arrive at " + address);
        return box.get(0);
    }

    private static String plainTextBody(Message message, String address) throws Exception {
        assertTrue(message.isMimeType("text/plain"), address + ": the message must be text/plain, was " + message.getContentType());
        Object content = message.getContent();
        assertTrue(content instanceof String, address + ": a text/plain message has a String body, was " + content);
        return (String) content;
    }
}
