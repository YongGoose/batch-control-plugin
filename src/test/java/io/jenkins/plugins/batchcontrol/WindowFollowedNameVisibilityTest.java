package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.Permission;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import jakarta.mail.Message;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-04, DECISIONS D-75 (1), SPEC item 8 line 171: "a window's followed name is shown
 * only to a viewer who may read the item: when its item was renamed or moved (D-74), the grant
 * pages, the grants list and the expiry notice show the item's current full name only to a viewer
 * (or recipient) with Item/Read on it; anyone else sees the approved name with a fixed note that the
 * item was moved and its new location is not visible to them. Administrators see the current
 * name." Matrix rows T-SEC-96 (the grant pages and lists) and T-SEC-97 (the GRANT_EXPIRING notice)
 * (note 275).
 *
 * <p>Fixture: change control on, Batch Control matrix strategy; u1 (Overall/Read, Item/Read,
 * RequestGrant) holds CONFIGURE windows approved by a1 (Overall/Read, Item/Read, Approve) on the
 * jobs {@code team/x} and {@code team/y}; admin (Overall/Administer). {@code vault-q9} is a folder
 * whose authorization property blocks inheritance and grants Item/Read to admin only, so neither u1
 * nor a1 may read it or anything inside it. The administrator moves {@code team/x} into it (the
 * window follows to {@code vault-q9/x}, D-74) and renames {@code team/y} to {@code team/y2} inside
 * {@code team}, which u1 and a1 may read (the control).
 *
 * <p>"Not shown" is measured on the raw HTML of each answer (text, links and attributes): the
 * destination folder's name {@code vault-q9} must not occur at all. The fixed note is pinned only
 * as the word "moved" next to the approved name (D-75 gives its sense, not its wording). The request
 * reasons name no item, so an item name on a page or in a mail comes from the window's scope.
 *
 * <p>Written from docs/SPEC.md item 8 and 13, docs/DECISIONS.md D-74 and D-75, docs/ARCHITECTURE.md
 * section 4 and the Given/When/Then of docs/reports/security-39.md S-39-04 only (no src/main
 * knowledge).
 */
@WithJenkins
public class WindowFollowedNameVisibilityTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");
    private static final String VAULT = "vault-q9";
    private static final String U1_MAIL = "requester.one@example.com";
    private static final String REASON = "quarterly maintenance";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;
    /** One logged-in client (JavaScript off) per user, reused for every page the row opens. */
    private final Map<String, JenkinsRule.WebClient> clients = new HashMap<>();

    /** Records every notification of this class's rows. */
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
        Mailbox.clearAll();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);

        cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.createProject(FreeStyleProject.class, "x");
        team.createProject(FreeStyleProject.class, "y");
        Folder vault = j.jenkins.createProject(Folder.class, VAULT);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty adminOnly =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        adminOnly.setInheritanceStrategy(new NonInheritingStrategy());
        adminOnly.add(Item.READ, PermissionEntry.user("admin"));
        vault.addProperty(adminOnly);
        for (String userId : new String[] {"u1", "a1"}) {
            assertFalse(can(userId, vault, Item.READ), "fixture: " + userId + " may not read " + VAULT);
        }
    }

    @AfterEach
    public void tearDown() {
        clients.values().forEach(JenkinsRule.WebClient::close);
        BatchClock.reset();
        NotificationCapture.clear();
        Mailbox.clearAll();
    }

    /**
     * T-SEC-96 (S-39-04, D-75 (1)): after the administrator moves {@code team/x} into
     * {@code vault-q9}, neither the grant request page, the window's page, the grants list nor the
     * active grants URL shows {@code vault-q9} to u1 or a1 (raw HTML); u1's and a1's request page
     * and u1's window page and Active list row show the approved name {@code team/x} with a note
     * saying it was moved; the administrator sees {@code vault-q9/x} on the request page, the
     * window's page and the Active list row. Control first: after the administrator renames
     * {@code team/y} to {@code team/y2}, the window follows and u1 sees {@code team/y2} on the
     * request page, the window's page and the Active list row, and a1 on the request page.
     */
    @Test
    public void t_sec_96_followedNameIsShownOnTheGrantPagesOnlyToViewersWhoMayReadTheItem() throws Exception {
        String requestY = openWindow("team/y");
        String windowY = WindowStateFixtures.windowId("u1", "team/y");
        assertRedirect(ApproverFormFixtures.post(j, "admin", "job/team/job/y/confirmRename", List.of(new NameValuePair("newName", "y2"))),
                "control: the administrator renames team/y to team/y2");
        assertNotNull(j.jenkins.getItemByFullName("team/y2"), "premise: team/y is now team/y2");
        WindowStateFixtures.assertActiveOn(j, "u1", windowY, "team/y2", "control (D-74): u1's window follows team/y to team/y2");
        assertMainNames(page("u1", "batch-control/grants/" + requestY + "/"), "team/y2", "control: u1's request page, item readable");
        assertMainNames(page("u1", "batch-control/grants/" + windowY + "/"), "team/y2", "control: u1's window page, item readable");
        assertMainNames(page("a1", "batch-control/grants/" + requestY + "/"), "team/y2", "control: a1's request page, item readable");

        String requestX = openWindow("team/x");
        String windowX = WindowStateFixtures.windowId("u1", "team/x");
        Item moved = moveIntoVault();
        for (String userId : new String[] {"u1", "a1"}) {
            assertFalse(can(userId, moved, Item.READ), "premise: " + userId + " may not read " + VAULT + "/x");
        }
        assertTrue(can("admin", moved, Item.READ), "premise: the administrator reads " + VAULT + "/x");

        for (String viewer : new String[] {"u1", "a1"}) {
            HtmlPage request = page(viewer, "batch-control/grants/" + requestX + "/");
            assertEquals(200, request.getWebResponse().getStatusCode(), viewer + " still opens the grant request page (P-10)");
            assertHidden(request, viewer + "'s grant request page");
            assertApprovedNameWithMovedNote(text(request), viewer + "'s grant request page");

            Page window = UsabilityFixtures.get(j, client(viewer), "batch-control/grants/" + windowX + "/");
            assertHidden(window, viewer + "'s view of the window's page (HTTP " + window.getWebResponse().getStatusCode() + ")");

            HtmlPage list = page(viewer, "batch-control/grants/");
            assertEquals(200, list.getWebResponse().getStatusCode(), viewer + " opens the grants list");
            assertHidden(list, viewer + "'s grants list");

            Page active = UsabilityFixtures.get(j, client(viewer), "batch-control/grants/active/");
            assertHidden(active, viewer + "'s active grants URL (HTTP " + active.getWebResponse().getStatusCode() + ")");
        }
        HtmlPage holderWindow = page("u1", "batch-control/grants/" + windowX + "/");
        assertEquals(200, holderWindow.getWebResponse().getStatusCode(), "u1 opens its window's page");
        assertApprovedNameWithMovedNote(text(holderWindow), "u1's window page");
        DomElement holderRow = WindowStateFixtures.activeRow(j, page("u1", "batch-control/grants/"), windowX);
        assertNotNull(holderRow, "premise: u1's grants list shows its window among the active windows");
        assertApprovedNameWithMovedNote(holderRow.asNormalizedText(), "u1's Active list row");

        assertMainNames(page("admin", "batch-control/grants/" + requestX + "/"), VAULT + "/x", "the administrator's grant request page");
        assertMainNames(page("admin", "batch-control/grants/" + windowX + "/"), VAULT + "/x", "the administrator's window page");
        DomElement adminRow = WindowStateFixtures.activeRow(j, page("admin", "batch-control/grants/"), windowX);
        assertNotNull(adminRow, "premise: the administrator's grants list shows the window among the active windows");
        assertTrue(adminRow.asNormalizedText().contains(VAULT + "/x"),
                "D-75: the administrator's Active list row must name " + VAULT + "/x: " + excerpt(adminRow.asNormalizedText()));
    }

    /**
     * T-SEC-97 (S-39-04, D-75 (1) "the GRANT_EXPIRING notice (checked as the recipient)"): with
     * e-mail notifications on, after the administrator moves {@code team/x} into {@code vault-q9}
     * and renames {@code team/y} to {@code team/y2}, the expiry work 9 minutes before both windows
     * end (plugin clock) sends u1 one GRANT_EXPIRING notice per window. The notice for the moved
     * window names {@code vault-q9} in none of its fields (subject, reason, link, requester) and its
     * mail (subject and body) does not contain it, while the body names the approved {@code team/x}
     * and says it was moved. Control: the mail of the renamed window names {@code team/y2}.
     */
    @Test
    public void t_sec_97_expiryNoticeHidesTheFollowedNameFromARecipientWhoCannotReadTheItem() throws Exception {
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        cfg.setEmailNotifications(true);
        cfg.save();

        String requestY = openWindow("team/y");
        String requestX = openWindow("team/x");
        awaitMails(U1_MAIL, 2); // fixture: the two APPROVED mails, sent before anything moved
        Mailbox.clearAll();
        NotificationCapture.clear();

        assertRedirect(ApproverFormFixtures.post(j, "admin", "job/team/job/y/confirmRename", List.of(new NameValuePair("newName", "y2"))),
                "control: the administrator renames team/y to team/y2");
        Item moved = moveIntoVault();
        assertFalse(can("u1", moved, Item.READ), "premise: u1 may not read " + VAULT + "/x");
        assertTrue(can("u1", j.jenkins.getItemByFullName("team/y2"), Item.READ), "premise (control): u1 reads team/y2");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(21)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        List<NotificationCapture> notices = NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestX);
        assertEquals(1, notices.size(), "premise (SPEC 13): one GRANT_EXPIRING notice for the moved window");
        NotificationCapture notice = notices.get(0);
        assertEquals(List.of("u1"), notice.recipients, "premise (SPEC 13): GRANT_EXPIRING goes to the window's holder");
        for (String field : new String[] {notice.subject, notice.reason, notice.url, notice.requester, notice.kind, notice.action}) {
            assertFalse(field != null && field.contains(VAULT), "D-75 (1): the GRANT_EXPIRING notice to u1, who may not read the item, must not name "
                    + VAULT + " in any field: " + notice);
        }

        List<Message> mails = awaitMails(U1_MAIL, 2);
        Message moveMail = null;
        Message renameMail = null;
        for (Message mail : mails) {
            String subject = String.valueOf(mail.getSubject());
            String body = body(mail);
            assertFalse(subject.contains(VAULT) || body.contains(VAULT),
                    "D-75 (1): no mail to u1 may name " + VAULT + ": subject=" + subject + " body=" + excerpt(body));
            if (body.contains(requestX)) {
                moveMail = mail;
            } else if (body.contains(requestY)) {
                renameMail = mail;
            }
        }
        assertNotNull(moveMail, "premise (SPEC 13): u1 got the GRANT_EXPIRING mail of " + requestX + " (it carries the request id)");
        assertNotNull(renameMail, "premise (SPEC 13): u1 got the GRANT_EXPIRING mail of " + requestY);
        assertApprovedNameWithMovedNote(body(moveMail), "the GRANT_EXPIRING mail of the moved window");
        assertTrue(body(renameMail).contains("team/y2"),
                "control (D-75 (1)): the GRANT_EXPIRING mail of a window whose item u1 may read names its current name team/y2: "
                        + excerpt(body(renameMail)));
    }

    // ---------------------------------------------------------------- helpers

    /** A CONFIGURE window on {@code fullName} requested by u1 through the form and approved by a1; returns the request id. */
    private String openWindow(String fullName) throws Exception {
        String id = submitGrantOk(j, "u1", fullName, List.of("CONFIGURE"), 30, REASON, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return id;
    }

    /** The administrator moves {@code team/x} into {@code vault-q9} (folders plugin {@code move/move}); the window follows. */
    private Item moveIntoVault() throws Exception {
        String windowX = WindowStateFixtures.windowId("u1", "team/x");
        assertRedirect(ApproverFormFixtures.post(j, "admin", "job/team/job/x/move/move", List.of(new NameValuePair("destination", "/" + VAULT))),
                "the administrator moves team/x into " + VAULT);
        Item moved = j.jenkins.getItemByFullName(VAULT + "/x");
        assertNotNull(moved, "premise: team/x is now " + VAULT + "/x");
        Grant window = WindowStateFixtures.active(windowX);
        assertNotNull(window, "premise (D-74): the window on team/x is still active after the move");
        assertEquals(VAULT + "/x", window.getScope().getFullName(), "premise (D-74): the window follows team/x to " + VAULT + "/x");
        return moved;
    }

    private JenkinsRule.WebClient client(String userId) throws Exception {
        JenkinsRule.WebClient wc = clients.get(userId);
        if (wc == null) {
            wc = UsabilityFixtures.clientNoJs(j, userId);
            clients.put(userId, wc);
        }
        return wc;
    }

    private HtmlPage page(String userId, String path) throws Exception {
        Page page = UsabilityFixtures.get(j, client(userId), path);
        assertTrue(page instanceof HtmlPage, userId + " GET " + path + " must answer an HTML page, got "
                + page.getWebResponse().getContentType() + " HTTP " + page.getWebResponse().getStatusCode());
        return (HtmlPage) page;
    }

    private static String text(HtmlPage page) {
        return WindowStateFixtures.mainPanel(page).asNormalizedText();
    }

    private static void assertHidden(Page page, String what) {
        WebResponse response = page.getWebResponse();
        String raw = response.getContentAsString();
        assertFalse(raw.contains(VAULT), "D-75 (1): " + what + " must not show the moved item's new location " + VAULT
                + " to a viewer who may not read it: " + excerpt(around(raw, VAULT)));
    }

    private static void assertApprovedNameWithMovedNote(String text, String what) {
        assertTrue(text.contains("team/x"), "D-75 (1): " + what + " must show the approved name team/x: " + excerpt(text));
        assertTrue(text.toLowerCase(Locale.ROOT).contains("moved"),
                "D-75 (1): " + what + " must say that the item was moved (its new location is not visible): " + excerpt(text));
    }

    private static void assertMainNames(HtmlPage page, String fullName, String what) {
        assertEquals(200, page.getWebResponse().getStatusCode(), what + " must open");
        assertTrue(text(page).contains(fullName), what + " must name the item's current full name " + fullName + ": " + excerpt(text(page)));
    }

    private static void assertRedirect(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " must go through (redirect), got HTTP " + code + ": "
                + excerpt(response.getContentAsString()));
    }

    private static String around(String text, String needle) {
        int at = text.indexOf(needle);
        if (at < 0) {
            return "";
        }
        return text.substring(Math.max(0, at - 200), Math.min(text.length(), at + 200));
    }

    private static List<Message> awaitMails(String address, int count) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        List<Message> box = Mailbox.get(address);
        while (box.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
        }
        assertTrue(box.size() >= count, count + " mails must arrive at " + address + ", got " + box.size());
        return new ArrayList<>(box);
    }

    private static String body(Message message) throws Exception {
        Object content = message.getContent();
        assertTrue(content instanceof String, "a text/plain message has a String body, was " + content);
        return (String) content;
    }

    private static boolean can(String userId, Item item, Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }
}
