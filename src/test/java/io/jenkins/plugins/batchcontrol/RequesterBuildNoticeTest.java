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
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitRunOk;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 5, D-38a: "The request detail page and the approver notification state when the
 * requester lacks {@code Item/Build} on the job." The frozen wording is
 * {@value #NOTICE}. Matrix rows T-05-21 .. T-05-24 (note 184) and T-05-31/32 for the
 * APPROVERS_CHANGED notification to a re-designated approver (spec-review-S5 m-3, note 196).
 *
 * <p>Actors: {@code nb} holds Request and Item/Read but no Item/Build; {@code wb} holds the same
 * plus Item/Build (the twin in which the sentence must be absent); {@code a1} is the approver.
 * The notification is observed through the shipped e-mail notifier (SPEC 13, mock-javamail),
 * whose plain-text message is the approver-facing notification.
 *
 * Written from docs/SPEC.md, docs/DECISIONS.md D-38a/D-36 and docs/TEST-MATRIX.md only (no
 * src/main knowledge).
 */
@WithJenkins
public class RequesterBuildNoticeTest {

    static final String NOTICE = "The requester does not have Build permission on this job.";

    private static final String A1_MAIL = "alpha.one@example.com";
    private static final String A2_MAIL = "alpha.two@example.com";

    private JenkinsRule j;
    private FreeStyleProject job;

    /** Proves the REQUEST_CREATED event fired before a mailbox is inspected. */
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
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("nb", "wb")
                .grant(Item.BUILD).everywhere().to("wb")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        User.getById("a1", true).addProperty(new Mailer.UserProperty(A1_MAIL));
        User.getById("a2", true).addProperty(new Mailer.UserProperty(A2_MAIL));

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1", "a2"));
        cfg.setEmailNotifications(true);
        cfg.save();

        job = j.createFreeStyleProject("batch-x");
        setBatchControl(job, new BatchControlJobProperty(true));
        assertFalse(can("nb", Item.BUILD), "fixture: nb must NOT hold Item/Build on batch-x");
        assertTrue(can("wb", Item.BUILD), "fixture: wb must hold Item/Build on batch-x");
    }

    @AfterEach
    public void tearDown() {
        Mailbox.clearAll();
        NotificationCapture.clear();
    }

    /** T-05-21: the detail page of nb's request, opened by the approver and by an administrator, shows the notice. */
    @Test
    public void t_05_21_detailPageStatesTheRequesterLacksBuild() throws Exception {
        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        for (String viewer : new String[] {"a1", "admin"}) {
            String text = detailText(viewer, id);
            assertTrue(text.contains(NOTICE), viewer + " must read \"" + NOTICE + "\" on the detail page of a request"
                    + " whose requester lacks Item/Build: " + excerpt(text));
        }
    }

    /** T-05-22 (twin of T-05-21): the detail page of wb's request (wb holds Item/Build) does not show the notice. */
    @Test
    public void t_05_22_detailPageOmitsTheNoticeWhenTheRequesterHoldsBuild() throws Exception {
        String id = submitRunOk(j, "wb", job, "month-end batch", "a1");
        for (String viewer : new String[] {"a1", "admin"}) {
            String text = detailText(viewer, id);
            assertFalse(text.contains(NOTICE), "the notice must not appear when the requester holds Item/Build: " + excerpt(text));
            assertFalse(text.contains("does not have Build permission"),
                    "no variant of the notice may appear when the requester holds Item/Build: " + excerpt(text));
        }
    }

    /** T-05-23: the REQUEST_CREATED notification to the approver for nb's request contains the notice. */
    @Test
    public void t_05_23_approverNotificationStatesTheRequesterLacksBuild() throws Exception {
        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id); // premise: the event fired
        String body = normalise(plainTextBody(awaitSingleMail(A1_MAIL)));
        assertTrue(body.contains(id), "premise: the mail is the one for request " + id + ": " + excerpt(body));
        assertTrue(body.contains(NOTICE), "the approver notification must state \"" + NOTICE + "\": " + excerpt(body));
    }

    /** T-05-24 (twin of T-05-23): the REQUEST_CREATED notification for wb's request does not contain the notice. */
    @Test
    public void t_05_24_approverNotificationOmitsTheNoticeWhenTheRequesterHoldsBuild() throws Exception {
        String id = submitRunOk(j, "wb", job, "month-end batch", "a1");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        String body = normalise(plainTextBody(awaitSingleMail(A1_MAIL)));
        assertTrue(body.contains(id), "premise: the mail is the one for request " + id + ": " + excerpt(body));
        assertFalse(body.contains("does not have Build permission"),
                "the notice must not appear when the requester holds Item/Build: " + excerpt(body));
    }

    /**
     * T-05-31 (spec-review-S5 m-3): nb re-designates the own request from a1 to a2; the
     * APPROVERS_CHANGED notification to a2 (the new approver, who decides with it) states the notice.
     */
    @Test
    public void t_05_31_approversChangedNotificationStatesTheRequesterLacksBuild() throws Exception {
        String id = submitRunOk(j, "nb", job, "month-end batch", "a1");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "nb", id, "a2"), "nb's re-designation");
        NotificationCapture.await(NotificationEvent.APPROVERS_CHANGED, id); // premise: the event fired
        String body = normalise(plainTextBody(awaitSingleMail(A2_MAIL)));
        assertTrue(body.contains(id), "premise: the mail is the one for request " + id + ": " + excerpt(body));
        assertTrue(body.contains(NOTICE), "the APPROVERS_CHANGED notification must state \"" + NOTICE + "\": " + excerpt(body));
    }

    /** T-05-32 (twin of T-05-31): the same re-designation by wb (holds Item/Build) carries no notice. */
    @Test
    public void t_05_32_approversChangedNotificationOmitsTheNoticeWhenTheRequesterHoldsBuild() throws Exception {
        String id = submitRunOk(j, "wb", job, "month-end batch", "a1");
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, id);
        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.changeRunApprovers(j, "wb", id, "a2"), "wb's re-designation");
        NotificationCapture.await(NotificationEvent.APPROVERS_CHANGED, id);
        String body = normalise(plainTextBody(awaitSingleMail(A2_MAIL)));
        assertTrue(body.contains(id), "premise: the mail is the one for request " + id + ": " + excerpt(body));
        assertFalse(body.contains("does not have Build permission"),
                "the notice must not appear when the requester holds Item/Build: " + excerpt(body));
    }

    // ---------------------------------------------------------------- helpers

    private String detailText(String viewer, String id) throws Exception {
        WebResponse response = ApproverFormFixtures.get(j, viewer, "batch-control/requests/" + id + "/");
        assertEquals(200, response.getStatusCode(), viewer + " must be able to open the request detail page");
        org.htmlunit.html.HtmlPage page = UsabilityFixtures.htmlPage(j, viewer, "batch-control/requests/" + id + "/");
        String text = normalise(page.asNormalizedText());
        assertTrue(text.contains(id), "premise: the detail page renders request " + id);
        return text;
    }

    private boolean can(String userId, hudson.security.Permission permission) {
        return job.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private static String normalise(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static Message awaitSingleMail(String address) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<Message> box = Mailbox.get(address);
        while (box.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
        }
        assertEquals(1, box.size(), "exactly one mail must arrive at " + address);
        return box.get(0);
    }

    private static String plainTextBody(Message message) throws Exception {
        Object content = message.getContent();
        assertTrue(content instanceof String, "a text/plain message has a String body, was " + content);
        return (String) content;
    }
}
