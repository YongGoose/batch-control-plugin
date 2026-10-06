package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AbstractPasswordBasedSecurityRealm;
import hudson.security.GroupDetails;
import hudson.security.Permission;
import hudson.security.SecurityRealm;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomAttr;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-04, DECISIONS D-75 (1), SPEC item 8 line 171: "a window's followed name is shown
 * only to a viewer who may read the item: when its item was renamed or moved (D-74), the grant
 * pages, the grants list and the expiry notice show the item's current full name only to a viewer
 * (or recipient) with Item/Read on it; anyone else sees the approved name with a fixed note that the
 * item was moved and its new location is not visible to them. Administrators see the current
 * name." Matrix rows T-SEC-96 (the grant pages and lists) and T-SEC-97 (the GRANT_EXPIRING notice)
 * (note 275); T-SEC-110/111 (the notice to a holder whose permission cannot be checked: no user
 * record, or a realm that cannot impersonate it; fail closed), T-SEC-113 (the "Request again" form)
 * and T-SEC-114 (a BatchControl/Manage holder without Item/Read, an administrator without an entry
 * of its own) (note 278).
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
    private static final String U2_MAIL = "requester.two@example.com";
    private static final String REASON = "quarterly maintenance";
    /** The fixed note's marker and wording (D-75 (1): "moved; its new location is not visible to you"). */
    private static final String NOTE_SELECTOR = "[data-batch-control-scope-note=\"moved\"]";
    private static final String MOVED_NOTE = "moved; its new location is not visible to you";

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

    /**
     * T-SEC-110 (D-75 (1) "the GRANT_EXPIRING notice (checked as the recipient)", fail closed; note
     * 278): as T-SEC-97, but the holder's user record is missing: after the administrator moves
     * {@code team/x} into {@code vault-q9}, u1's user record is deleted (an administrator's "Delete
     * user"; premise: {@code User.getById("u1", false)} is null). The expiry work 9 minutes before
     * the windows end (plugin clock) either sends u1 no GRANT_EXPIRING notice for the moved window or
     * sends one that names the approved {@code team/x} and names {@code vault-q9} in none of its
     * fields: a recipient whose permission cannot be checked is not a reader. Guard: u2, a known
     * holder, gets the GRANT_EXPIRING notice of its window on {@code team/z} from the same run.
     */
    @Test
    public void t_sec_110_expiryNoticeToAHolderWithoutAUserRecordDoesNotNameTheNewLocation() throws Exception {
        permit("u2", Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT);
        j.jenkins.getItemByFullName("team", Folder.class).createProject(FreeStyleProject.class, "z");
        String requestX = openWindow("u1", "team/x");
        String requestZ = openWindow("u2", "team/z");
        Item moved = moveIntoVault();
        assertFalse(can("u1", moved, Item.READ), "premise: u1 may not read " + VAULT + "/x");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: an administrator deletes u1's user record
            User u1 = User.getById("u1", false);
            assertNotNull(u1, "premise: u1 has a user record before the deletion");
            u1.delete();
        }
        assertNull(User.getById("u1", false), "premise: u1's user record is gone");
        NotificationCapture.clear();

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(21)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        List<NotificationCapture> guard = NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestZ);
        assertEquals(List.of("u2"), guard.get(0).recipients, "guard (SPEC 13): u2, a known holder, gets the GRANT_EXPIRING notice of its window");
        assertFailClosed(NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, requestX),
                "the GRANT_EXPIRING notice for u1, whose user record is missing");
    }

    /**
     * T-SEC-111 (D-75 (1), fail closed; note 278): as T-SEC-97 (e-mail notifications on, u1's and
     * u2's addresses), but after the administrator moves {@code team/x} into {@code vault-q9} the
     * security realm no longer knows u1: impersonating u1 fails with
     * {@code UsernameNotFoundException} (premise; u1's user record and address stay). The expiry work
     * 9 minutes before the windows end either sends u1 no GRANT_EXPIRING notice for the moved window
     * or sends one that names the approved {@code team/x} and names {@code vault-q9} in none of its
     * fields, and no mail to u1 contains {@code vault-q9} (a mail of the moved window, if any, names
     * {@code team/x}). Guard: u2 gets the notice and the mail of its window on {@code team/z}.
     */
    @Test
    public void t_sec_111_expiryNoticeToAHolderTheRealmCannotImpersonateDoesNotNameTheNewLocation() throws Exception {
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        permit("u2", Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST_GRANT);
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        User.getById("u2", true).addProperty(new Mailer.UserProperty(U2_MAIL));
        cfg.setEmailNotifications(true);
        cfg.save();
        j.jenkins.getItemByFullName("team", Folder.class).createProject(FreeStyleProject.class, "z");
        String requestX = openWindow("u1", "team/x");
        String requestZ = openWindow("u2", "team/z");
        awaitMails(U1_MAIL, 1); // fixture: the APPROVED mails, sent before anything moved
        awaitMails(U2_MAIL, 1);
        Item moved = moveIntoVault();
        assertFalse(can("u1", moved, Item.READ), "premise: u1 may not read " + VAULT + "/x");
        Mailbox.clearAll();
        NotificationCapture.clear();

        j.jenkins.setSecurityRealm(new RealmWithoutU1());
        User u1 = User.getById("u1", false);
        assertNotNull(u1, "premise: u1's user record (with its e-mail address) stays");
        assertThrows(UsernameNotFoundException.class, u1::impersonate2, "premise: the security realm can no longer impersonate u1");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(21)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        List<NotificationCapture> guard = NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestZ);
        assertEquals(List.of("u2"), guard.get(0).recipients, "guard (SPEC 13): u2 gets the GRANT_EXPIRING notice of its window");
        awaitMails(U2_MAIL, 1);
        assertFailClosed(NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, requestX),
                "the GRANT_EXPIRING notice for u1, whom the realm cannot impersonate");
        for (Message mail : new ArrayList<>(Mailbox.get(U1_MAIL))) {
            String subject = String.valueOf(mail.getSubject());
            String body = body(mail);
            assertFalse(subject.contains(VAULT) || body.contains(VAULT), "D-75 (1): no mail to u1, whom the realm cannot impersonate, may name "
                    + VAULT + ": subject=" + subject + " body=" + excerpt(body));
            if (body.contains(requestX)) {
                assertTrue(body.contains("team/x"), "D-75 (1): a GRANT_EXPIRING mail of the moved window names the approved team/x: " + excerpt(body));
            }
        }
    }

    /**
     * T-SEC-113 (D-75 (1), SPEC 8 line 153 "re-request link" on the grants screen; note 278): u1's
     * CONFIGURE windows on {@code team/x} and {@code team/y}; the administrator renames
     * {@code team/y} to {@code team/y2} (control) and moves {@code team/x} into {@code vault-q9};
     * both windows expire (plugin clock). u1's Ended list offers a "Request again" control for the
     * moved window (premise). Every form reached from it, and {@code grants/new?from=<id>} and
     * {@code grants/dialog?from=<id>} opened directly, answers 200, contains {@code vault-q9}
     * nowhere in its raw HTML, is prefilled from the window (Configure ticked, premise) and starts
     * with an empty name field. Control: the same forms for the renamed window start with
     * {@code team/y2}, which u1 may read.
     */
    @Test
    public void t_sec_113_requestAgainFormForAWindowMovedOutOfSightStartsEmptyAndNamesNoNewLocation() throws Exception {
        openWindow("u1", "team/y");
        String windowY = WindowStateFixtures.windowId("u1", "team/y");
        assertRedirect(ApproverFormFixtures.post(j, "admin", "job/team/job/y/confirmRename", List.of(new NameValuePair("newName", "y2"))),
                "control: the administrator renames team/y to team/y2");
        openWindow("u1", "team/x");
        String windowX = WindowStateFixtures.windowId("u1", "team/x");
        Item moved = moveIntoVault();
        assertFalse(can("u1", moved, Item.READ), "premise: u1 may not read " + VAULT + "/x");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertNull(WindowStateFixtures.active(windowX), "premise: the window on " + VAULT + "/x has expired");
        assertNull(WindowStateFixtures.active(windowY), "premise: the window on team/y2 has expired");
        HtmlPage list = page("u1", "batch-control/grants/");
        assertHidden(list, "u1's grants list after the windows expired");

        for (String[] window : new String[][] {{windowX, ""}, {windowY, "team/y2"}}) {
            String id = window[0];
            String expected = window[1];
            List<String> offered = requestAgainUrls(list, id);
            assertFalse(offered.isEmpty(), "premise (SPEC 8 line 153): u1's Ended list offers a Request again control for the window " + id
                    + "; Ended list: " + excerpt(endedText(list)));
            Set<String> urls = new LinkedHashSet<>(offered);
            urls.add("batch-control/grants/new?from=" + id);
            urls.add("batch-control/grants/dialog?from=" + id);
            for (String url : urls) {
                String what = "u1's Request again form " + url + (expected.isEmpty() ? " (item moved out of sight)" : " (control, item readable)");
                Page answer = UsabilityFixtures.get(j, client("u1"), url);
                assertEquals(200, answer.getWebResponse().getStatusCode(), what + " must open");
                assertHidden(answer, what);
                assertTrue(answer instanceof HtmlPage, what + " must be an HTML form, got " + answer.getWebResponse().getContentType());
                HtmlForm form = grantForm((HtmlPage) answer, what);
                assertTrue(ticked(form, "CONFIGURE"), "premise: " + what + " is prefilled from the window (Configure ticked): " + excerpt(form.asNormalizedText()));
                assertEquals(expected, fieldValue(form, "scopeFullName"), expected.isEmpty()
                        ? "D-75 (1): " + what + " must start with an empty name field"
                        : "control: " + what + " starts with the item's current name, which u1 may read");
            }
        }
    }

    /**
     * T-SEC-114 (SPEC 8 line 171, D-75 (1); LIMITATIONS 24 (Manage sees active windows); note
     * 278): m1 holds Overall/Read, Item/Read and BatchControl/Manage but may not read anything in
     * {@code vault-q9}; adm2 holds Overall/Administer and no entry of its own on {@code vault-q9}.
     * After the administrator renames {@code team/y} to {@code team/y2} (control) and moves
     * {@code team/x} into {@code vault-q9}: m1's view of the window's page and of the grants list
     * contains {@code vault-q9} nowhere (raw HTML) and shows the approved name {@code team/x} with a
     * {@code [data-batch-control-scope-note="moved"]} note reading "moved; its new location is not
     * visible to you", on the page and in the window's Active row; m1's Active row of the renamed
     * window names {@code team/y2} without that note. adm2 sees {@code vault-q9/x} on the window's
     * page and in its Active row, without the note.
     */
    @Test
    public void t_sec_114_manageHolderWithoutReadSeesTheApprovedNameWithTheNoteAndAnAdministratorTheCurrentName() throws Exception {
        permit("m1", Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE);
        permit("adm2", Jenkins.ADMINISTER);
        openWindow("u1", "team/y");
        String windowY = WindowStateFixtures.windowId("u1", "team/y");
        assertRedirect(ApproverFormFixtures.post(j, "admin", "job/team/job/y/confirmRename", List.of(new NameValuePair("newName", "y2"))),
                "control: the administrator renames team/y to team/y2");
        String requestX = openWindow("u1", "team/x");
        String windowX = WindowStateFixtures.windowId("u1", "team/x");
        Item moved = moveIntoVault();
        assertFalse(can("m1", moved, Item.READ), "premise: m1 may not read " + VAULT + "/x");
        assertTrue(can("m1", j.jenkins.getItemByFullName("team/y2"), Item.READ), "premise (control): m1 reads team/y2");
        assertTrue(can("adm2", moved, Item.READ), "premise: adm2, an administrator, reads " + VAULT + "/x");

        Set<String> pages = new LinkedHashSet<>(List.of("batch-control/grants/" + windowX + "/", "batch-control/grants/" + requestX + "/"));
        for (String path : pages) {
            HtmlPage managerView = page("m1", path);
            assertEquals(200, managerView.getWebResponse().getStatusCode(), "m1 (BatchControl/Manage) opens " + path);
            assertHidden(managerView, "m1's view of " + path);
            assertMovedNote(WindowStateFixtures.mainPanel(managerView), "m1's view of " + path);

            HtmlPage adminView = page("adm2", path);
            assertEquals(200, adminView.getWebResponse().getStatusCode(), "adm2 opens " + path);
            assertTrue(text(adminView).contains(VAULT + "/x"), "D-75 (1): an administrator sees the current name " + VAULT + "/x on " + path
                    + ": " + excerpt(text(adminView)));
            assertTrue(WindowStateFixtures.mainPanel(adminView).querySelectorAll(NOTE_SELECTOR).isEmpty(),
                    "D-75 (1): an administrator's view of " + path + " carries no moved note: " + excerpt(text(adminView)));
        }

        HtmlPage managerList = page("m1", "batch-control/grants/");
        assertHidden(managerList, "m1's grants list");
        DomElement managerRow = WindowStateFixtures.activeRow(j, managerList, windowX);
        assertNotNull(managerRow, "premise (LIMITATIONS 24): m1's grants list shows u1's window among the active windows");
        assertMovedNote(managerRow, "m1's Active list row of the moved window");
        DomElement managerControl = WindowStateFixtures.activeRow(j, managerList, windowY);
        assertNotNull(managerControl, "premise: m1's grants list shows u1's window on team/y2 among the active windows");
        assertTrue(managerControl.asNormalizedText().contains("team/y2") && managerControl.querySelectorAll(NOTE_SELECTOR).isEmpty(),
                "control: m1's Active row of the renamed window names team/y2, which m1 may read, without the moved note: "
                        + excerpt(managerControl.asNormalizedText()));

        DomElement adminRow = WindowStateFixtures.activeRow(j, page("adm2", "batch-control/grants/"), windowX);
        assertNotNull(adminRow, "premise: adm2's grants list shows u1's window among the active windows");
        assertTrue(adminRow.asNormalizedText().contains(VAULT + "/x") && adminRow.querySelectorAll(NOTE_SELECTOR).isEmpty(),
                "D-75 (1): adm2's Active row names " + VAULT + "/x without the moved note: " + excerpt(adminRow.asNormalizedText()));
    }

    // ---------------------------------------------------------------- helpers

    /** A CONFIGURE window on {@code fullName} requested by u1 through the form and approved by a1; returns the request id. */
    private String openWindow(String fullName) throws Exception {
        return openWindow("u1", fullName);
    }

    /** A CONFIGURE window on {@code fullName} requested by {@code userId} through the form and approved by a1; returns the request id. */
    private String openWindow(String userId, String fullName) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of("CONFIGURE"), 30, REASON, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return id;
    }

    /** Adds global entries for {@code userId} to the installed Batch Control matrix strategy. */
    private void permit(String userId, Permission... permissions) {
        BatchControlMatrixAuthorizationStrategy strategy = (BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy();
        for (Permission permission : permissions) {
            strategy.add(permission, PermissionEntry.user(userId));
        }
    }

    /**
     * Fail closed for a recipient whose read permission cannot be confirmed: either no notice came,
     * or every notice names {@code vault-q9} in none of its fields and names the approved
     * {@code team/x} in one of them.
     */
    private static void assertFailClosed(List<NotificationCapture> notices, String what) {
        System.out.println("D-75 observation: " + what + ": " + (notices.isEmpty() ? "not sent" : notices));
        for (NotificationCapture notice : notices) {
            for (String field : new String[] {notice.subject, notice.reason, notice.url, notice.requester, notice.kind, notice.action}) {
                assertFalse(field != null && field.contains(VAULT), "D-75 (1): " + what + " must not name " + VAULT + " in any field: " + notice);
            }
            assertTrue(Arrays.asList(notice.subject, notice.reason, notice.url).stream().anyMatch(f -> f != null && f.contains("team/x")),
                    "D-75 (1): " + what + ", if sent, names the approved team/x: " + notice);
        }
    }

    /**
     * The URLs (relative to the Jenkins root) that the Ended list row of the window {@code grantId}
     * offers for requesting it again: any {@code href} or {@code data-*} attribute in that row whose
     * value carries {@code from=<grantId>}.
     */
    private List<String> requestAgainUrls(HtmlPage list, String grantId) throws Exception {
        String root = j.getURL().toExternalForm();
        List<String> out = new ArrayList<>();
        for (DomNode table : list.querySelectorAll(WindowStateFixtures.ENDED_LIST)) {
            for (DomNode node : table.querySelectorAll("*")) {
                DomElement element = (DomElement) node;
                for (DomAttr attr : element.getAttributesMap().values()) {
                    String name = attr.getName();
                    String value = attr.getValue().trim();
                    if (("href".equals(name) || name.startsWith("data-")) && value.contains("from=" + grantId)) {
                        String absolute = list.getFullyQualifiedUrl(value).toExternalForm();
                        out.add(absolute.startsWith(root) ? absolute.substring(root.length()) : absolute);
                    }
                }
            }
        }
        return out;
    }

    private static String endedText(HtmlPage list) {
        DomNode ended = list.querySelector(WindowStateFixtures.ENDED_LIST);
        return ended == null ? "(no Ended list)" : ended.asNormalizedText();
    }

    /** The grant request form of {@code page} (posting to {@code batch-control/grants/create}). */
    private static HtmlForm grantForm(HtmlPage page, String what) throws Exception {
        List<HtmlForm> forms = UsabilityFixtures.formsEndingWith(page, "batch-control/grants/create");
        assertFalse(forms.isEmpty(), what + " must carry the grant request form posting to batch-control/grants/create: "
                + excerpt(page.asNormalizedText()));
        return forms.get(0);
    }

    private static boolean ticked(HtmlForm form, String action) {
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlCheckBoxInput box && "actions".equals(box.getAttribute("name")) && action.equals(box.getValueAttribute())) {
                return box.isChecked();
            }
        }
        return false;
    }

    /** The starting value of the form's text field {@code name} (asserted present). */
    private static String fieldValue(HtmlForm form, String name) {
        for (DomElement element : form.getElementsByTagName("input")) {
            if (element instanceof HtmlInput input && name.equals(input.getAttribute("name"))) {
                return input.getValue();
            }
        }
        throw new AssertionError("the form must offer the field " + name + ": " + excerpt(form.asXml()));
    }

    /** {@code scope} shows the approved name team/x with the fixed moved note (D-75 (1)). */
    private static void assertMovedNote(DomNode scope, String what) {
        String text = scope.asNormalizedText();
        assertTrue(text.contains("team/x"), "D-75 (1): " + what + " must show the approved name team/x: " + excerpt(text));
        List<DomNode> notes = new ArrayList<>(scope.querySelectorAll(NOTE_SELECTOR));
        assertFalse(notes.isEmpty(), "D-75 (1): " + what + " must carry the note " + NOTE_SELECTOR + ": " + excerpt(text));
        assertTrue(notes.stream().anyMatch(n -> n.asNormalizedText().toLowerCase(Locale.ROOT).contains(MOVED_NOTE)),
                "D-75 (1): the note on " + what + " must read '" + MOVED_NOTE + "': "
                        + notes.stream().map(DomNode::asNormalizedText).toList());
    }

    /**
     * A realm that knows every user but u1: authentication is user name equals password, and
     * loading u1 fails with {@code UsernameNotFoundException}, so impersonating u1 fails.
     */
    public static final class RealmWithoutU1 extends AbstractPasswordBasedSecurityRealm {
        @Override
        protected UserDetails authenticate2(String username, String password) throws AuthenticationException {
            if (username.equals(password)) {
                return loadUserByUsername2(username);
            }
            throw new BadCredentialsException(username);
        }

        @Override
        public UserDetails loadUserByUsername2(String username) throws UsernameNotFoundException {
            if ("u1".equals(username)) {
                throw new UsernameNotFoundException("u1 is not known to this realm");
            }
            return new org.springframework.security.core.userdetails.User(username, "", true, true, true, true,
                    List.of(SecurityRealm.AUTHENTICATED_AUTHORITY2));
        }

        @Override
        public GroupDetails loadGroupByGroupname2(String groupname, boolean fetchMembers) throws UsernameNotFoundException {
            throw new UsernameNotFoundException(groupname);
        }
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
