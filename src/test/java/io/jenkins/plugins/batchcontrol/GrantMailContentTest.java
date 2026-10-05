package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.Item;
import hudson.model.User;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
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
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.UsabilityFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix rows T-13-20 (e2e-03 DEF-24, note 139) and T-08-123 (D-71, note 260): the grant request
 * mail tells the approver what they decide on — the actions, the duration, the name restriction
 * and the kind of the window's item — besides the D-36 fields.
 * Derived from SPEC 8 (D-40: the restriction "is shown to the approver") and SPEC 13 (D-36); the
 * D-36 field list itself does not name them, see note 139.
 *
 * <p>Written from docs/SPEC.md, docs/reports/e2e-03.md and docs/TEST-MATRIX.md only (no src/main
 * knowledge).
 */
@WithJenkins
public class GrantMailContentTest {

    private static final String A2_MAIL = "alpha.two@example.com";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        Mailbox.clearAll();
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT).everywhere().to("u1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a2"));
        User.getById("u1", true).addProperty(new Mailer.UserProperty("requester.one@example.com"));
        User.getById("a2", true).addProperty(new Mailer.UserProperty(A2_MAIL));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a2"));
        cfg.setGrantDurationOptions(Arrays.asList(15, 37, 60));
        cfg.setEmailNotifications(true);
        cfg.save();
        j.jenkins.createProject(Folder.class, "team");
    }

    @AfterEach
    public void tearDown() {
        Mailbox.clearAll();
    }

    /**
     * T-13-20 (DEF-24): the REQUEST_CREATED mail of a grant request names actions, duration and
     * restriction. Since D-71 the two actions are CREATE and CONFIGURE (a request for DELETE on a
     * folder is refused at submission; before D-71 the row used CREATE and DELETE, note 260).
     */
    @Test
    public void t_13_20_grantRequestMailNamesActionsDurationAndRestriction() throws Exception {
        String id = submitGrantOk(j, "u1", "team", Arrays.asList("CREATE", "CONFIGURE"), 37,
                "month-end report", "app-2", "a2");

        String body = plainTextBody(awaitSingleMail(A2_MAIL));
        assertTrue(body.contains(id) && body.contains("team"), "premise (T-13-16): id and scope are in the mail; body was: " + body);
        String lower = body.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("create") && lower.contains("configure"), "the mail must name the requested actions: " + excerpt(body));
        assertTrue(body.contains("37"), "the mail must name the requested duration (37 minutes): " + excerpt(body));
        assertTrue(body.contains("app-2"), "the mail must name the name restriction the approver decides on (D-40): " + excerpt(body));
    }

    /**
     * T-08-123 (D-71): the REQUEST_CREATED mail names the window's item and its kind's display
     * name, as the request screens do (SPEC 8): the line "Item kind: <display name>" next to the
     * scope, and no scope type line. A request on a Freestyle job says "Item kind: Freestyle
     * project"; a request on a folder says "Item kind: Folder" and not "Freestyle project", so the
     * kind is the item's own and not a constant (note 260).
     */
    @Test
    public void t_08_123_grantRequestMailNamesTheItemKind() throws Exception {
        j.createFreeStyleProject("mail-job");
        submitGrantOk(j, "u1", "mail-job", Arrays.asList("CONFIGURE"), 15, "fix the mail job", null, "a2");
        String jobBody = plainTextBody(awaitSingleMail(A2_MAIL));
        assertTrue(jobBody.contains("mail-job"), "premise: the mail names the item: " + excerpt(jobBody));
        assertTrue(jobBody.contains("Item kind: Freestyle project"), "the mail must name the kind of mail-job: " + excerpt(jobBody));
        assertFalse(jobBody.contains("Scope type"), "D-71: the mail carries no scope type line: " + excerpt(jobBody));

        Mailbox.clearAll();
        submitGrantOk(j, "u1", "team", Arrays.asList("CREATE"), 15, "a new job in team", null, "a2");
        String folderBody = plainTextBody(awaitSingleMail(A2_MAIL));
        assertTrue(folderBody.contains("team"), "premise: the mail names the item: " + excerpt(folderBody));
        assertTrue(folderBody.contains("Item kind: Folder"), "the mail must name the kind of team: " + excerpt(folderBody));
        assertFalse(folderBody.contains("Freestyle project"), "the kind must be the item's own: " + excerpt(folderBody));
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
