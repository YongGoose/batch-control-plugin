package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.security.Permission;
import hudson.tasks.Mailer;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RequestStatus;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.ops.BatchControlNotifier;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import jakarta.mail.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.mock_javamail.Mailbox;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.BatchControlFixtures.setBatchControl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenario L3-19 (the rows that need no restart): notifications for one's own
 * cancel, and the GRANT_EXPIRING notice of a window that followed its item out of the recipient's
 * sight. Matrix rows T-GAP-369 and T-GAP-371 .. T-GAP-373 (note 279); the restart row T-GAP-370 is in
 * {@link StoreRestartGapTest}.
 *
 * <p>Basis: SPEC 13 (D-36: "Recipients: the designated approvers for REQUEST_CREATED / APPROVERS_CHANGED,
 * the requester for the others"; D-54: "the designated approvers of a pending request receive CANCELLED
 * ... The requester is not mailed about their own cancel"); SPEC 8 line 171 / D-75 (1): "the expiry
 * notice show[s] the item's current full name only to a ... recipient with Item/Read on it; anyone else
 * sees the approved name with a fixed note that the item was moved", "checked as the recipient".
 *
 * <p>Fixture as WindowFollowedNameVisibilityTest: change control on, Batch Control matrix strategy;
 * u1 and u2 (RequestGrant) hold CONFIGURE windows approved by a1 on {@code team/x} and {@code team/z};
 * {@code vault-l3} is a folder only the administrator may read. Time moves through {@link BatchClock}.
 *
 * <p>Written from docs/SPEC.md items 8 and 13, docs/DECISIONS.md D-36, D-54, D-74 and D-75 only (no
 * src/main knowledge).
 */
@WithJenkins
public class NotificationGapTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");
    private static final String VAULT = "vault-l3";
    private static final String U1_MAIL = "l3.requester.one@example.com";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

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
        cfg = BatchControlGlobalConfiguration.get();
    }

    @AfterEach
    public void tearDown() {
        BatchClock.reset();
        NotificationCapture.clear();
        Mailbox.clearAll();
    }

    /**
     * T-GAP-369 (L3-19; SPEC 13 D-54 "The requester is not mailed about their own cancel", D-36 the
     * requester receives the other events, D-54 the designated approvers receive CANCELLED): the
     * administrator (on the approver list, self-approval allowed) requests a run of the approval-required
     * job {@code l3-own} naming only himself as approver and cancels it: no CANCELLED notification names
     * him as a recipient (he is both the requester who cancelled and the only approver; note 279). Guard:
     * m1 (BatchControl/Manage) cancels r's pending request designating a1 and a2: CANCELLED reaches r and
     * both approvers.
     */
    @Test
    public void t_gap_369_ownCancelNotifiesNoOneButAManagersCancelNotifiesRequesterAndApprovers() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new org.jvnet.hudson.test.MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.REQUEST).everywhere().to("r")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.MANAGE).everywhere().to("m1")
                .grant(Jenkins.READ, Item.READ, BatchControlPermissions.APPROVE).everywhere().to("a1", "a2"));
        cfg.setRunControlEnabled(true);
        cfg.setApprovers(Arrays.asList("admin", "a1", "a2"));
        cfg.save();
        FreeStyleProject job = j.createFreeStyleProject("l3-own");
        setBatchControl(job, new BatchControlJobProperty(true));

        RunRequest own = as("admin", () -> RunRequestService.get().create(job, new LinkedHashMap<>(), "my own run", "admin"));
        NotificationCapture.await(NotificationEvent.REQUEST_CREATED, own.getId());
        as("admin", () -> {
            RunRequestService.get().cancel(own.getId());
            return null;
        });
        assertEquals(RequestStatus.CANCELLED, RunRequestService.get().load(own.getId()).getStatus(), "premise: the administrator cancelled");
        List<NotificationCapture> ownCancel = NotificationCapture.afterQuietPeriod(NotificationEvent.CANCELLED, own.getId());
        assertTrue(ownCancel.stream().noneMatch(c -> c.recipients != null && c.recipients.contains("admin")),
                "D-54: the requester is not notified of his own cancel: " + ownCancel);

        String otherId = ApproverFormFixtures.submitRunOk(j, "r", job, "r's run", "a1", "a2");
        RunRequest other = RunRequestService.get().load(otherId);
        assertEquals(List.of("a1", "a2"), other.getApprovers(), "premise: r designated a1 and a2");
        as("m1", () -> {
            RunRequestService.get().cancel(other.getId());
            return null;
        });
        List<NotificationCapture> cancelled = NotificationCapture.await(NotificationEvent.CANCELLED, other.getId());
        Set<String> reached = new java.util.TreeSet<>();
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            reached.clear();
            for (NotificationCapture c : NotificationCapture.of(NotificationEvent.CANCELLED, other.getId())) {
                if (c.recipients != null) {
                    reached.addAll(c.recipients);
                }
            }
            if (reached.containsAll(List.of("r", "a1", "a2"))) {
                break;
            }
            Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
        }
        assertTrue(reached.containsAll(List.of("r", "a1", "a2")), "guard (D-36, D-54): a Manage holder's cancel reaches the requester r"
                + " and the approvers a1 and a2: " + cancelled + " / reached " + reached);
    }

    /**
     * T-GAP-371 (L3-19; SPEC 8 line 171 / D-75 (1) "checked as the recipient"): u1 holds Item/Discover
     * but not Item/Read on {@code vault-l3}. After the administrator moves {@code team/x} into it (the
     * window follows), the expiry work ten minutes before the window ends sends u1 a GRANT_EXPIRING notice
     * that names {@code vault-l3} in none of its fields, and a mail that names the approved {@code team/x}
     * and says it was moved. Guard: u2's notice for {@code team/z} from the same run names {@code team/z}.
     */
    @Test
    public void t_gap_371_discoverOnlyRecipientGetsTheApprovedNameWithTheMovedNote() throws Exception {
        matrixFixture();
        Folder vault = (Folder) j.jenkins.getItemByFullName(VAULT);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                vault.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        property.add(Item.DISCOVER, PermissionEntry.user("u1"));
        vault.save();
        mail();
        String requestX = openWindow("u1", "team/x");
        String requestZ = openWindow("u2", "team/z");
        Item moved = moveIntoVault("team/x");
        assertTrue(can("u1", moved, Item.DISCOVER), "premise: u1 may discover " + VAULT + "/x");
        assertFalse(can("u1", moved, Item.READ), "premise: u1 may not read " + VAULT + "/x");
        NotificationCapture.clear();
        Mailbox.clearAll();

        expiryWork();
        NotificationCapture notice = single(NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestX), "u1");
        assertNoVault(notice, "the GRANT_EXPIRING notice to the Discover-only u1");
        String body = mailFor(requestX);
        assertFalse(body.contains(VAULT), "D-75 (1): the mail names " + VAULT + " nowhere: " + excerpt(body));
        assertTrue(body.contains("team/x") && body.toLowerCase(Locale.ROOT).contains("moved"),
                "D-75 (1): the mail names the approved team/x and says it was moved: " + excerpt(body));
        NotificationCapture guard = single(NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestZ), "u2");
        assertTrue(String.valueOf(guard.subject).contains("team/z") || String.valueOf(guard.reason).contains("team/z"),
                "guard: u2's notice names team/z: " + guard);
    }

    /**
     * T-GAP-372 (L3-19; SPEC 8 line 171 / D-75 (1) "checked as the recipient", fail closed): the realm is
     * Jenkins' own user database; after the administrator moves {@code team/x} into {@code vault-l3},
     * u1's account is removed from the realm while u1's user record (and mail address) stays. The expiry
     * work either sends u1 no GRANT_EXPIRING notice for the moved window or one that names
     * {@code vault-l3} in no field and names the approved {@code team/x} (and, in the mail, says it was
     * moved). Guard: u2's notice for {@code team/z} from the same run arrives.
     */
    @Test
    public void t_gap_372_recipientRemovedFromThePrivateRealmGetsNoFollowedName() throws Exception {
        matrixFixture();
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        for (String userId : new String[] {"admin", "u1", "u2", "a1"}) {
            realm.createAccount(userId, userId + "-pw-L3");
        }
        j.jenkins.setSecurityRealm(realm);
        mail();
        String requestX = openWindow("u1", "team/x");
        String requestZ = openWindow("u2", "team/z");
        moveIntoVault("team/x");
        User u1 = User.getById("u1", false);
        assertNotNull(u1, "premise: u1's user record exists");
        // the account is removed from the realm's user database (its Details property), the user record stays
        Path config = u1.getUserFolder().toPath().resolve("config.xml");
        String xml = Files.readString(config, java.nio.charset.StandardCharsets.UTF_8);
        String withoutAccount = xml.replaceAll("(?s)<hudson\\.security\\.HudsonPrivateSecurityRealm_-Details>.*?"
                + "</hudson\\.security\\.HudsonPrivateSecurityRealm_-Details>", "");
        assertFalse(withoutAccount.equals(xml), "fixture: u1's record carries the realm's account details: " + excerpt(xml));
        Files.writeString(config, withoutAccount, java.nio.charset.StandardCharsets.UTF_8);
        User.reload();
        User kept = User.getById("u1", false);
        assertNotNull(kept, "premise: u1's user record is kept");
        assertNotNull(kept.getProperty(Mailer.UserProperty.class), "premise: u1's mail address is kept");
        boolean known;
        try {
            realm.loadUserByUsername2("u1");
            known = true;
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
            known = false;
        }
        assertFalse(known, "premise: the realm no longer knows u1");
        NotificationCapture.clear();
        Mailbox.clearAll();

        expiryWork();
        NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestZ);
        List<NotificationCapture> notices = NotificationCapture.afterQuietPeriod(NotificationEvent.GRANT_EXPIRING, requestX);
        System.out.println("T-GAP-372 observation: " + (notices.isEmpty() ? "not sent" : notices));
        for (NotificationCapture notice : notices) {
            assertNoVault(notice, "a GRANT_EXPIRING notice to the unknown u1");
            assertTrue(String.valueOf(notice.subject).contains("team/x") || String.valueOf(notice.reason).contains("team/x")
                    || String.valueOf(notice.url).contains("team/x"), "D-75 (1): if sent, the notice names the approved team/x: " + notice);
        }
        for (Message m : Mailbox.get(U1_MAIL)) {
            String body = String.valueOf(m.getContent());
            assertFalse(body.contains(VAULT) || String.valueOf(m.getSubject()).contains(VAULT),
                    "D-75 (1): no mail to the unknown u1 names " + VAULT + ": " + excerpt(body));
            if (body.contains(requestX)) {
                assertTrue(body.contains("team/x") && body.toLowerCase(Locale.ROOT).contains("moved"),
                        "D-75 (1): a mail sent to the unknown u1 names the approved team/x and says it was moved: " + excerpt(body));
            }
        }
    }

    /**
     * T-GAP-373 (L3-19; SPEC 8 line 171 / D-75 (1)): after the administrator moves {@code team/x} into
     * {@code vault-l3}, the window's request file {@code requests/grant/<id>.xml} is deleted. The expiry
     * work sends no GRANT_EXPIRING notice that names {@code vault-l3} in any field, to anyone. Guard: u2's
     * notice for {@code team/z} from the same run arrives.
     */
    @Test
    public void t_gap_373_windowWithoutItsRequestFileLeaksNoFollowedName() throws Exception {
        matrixFixture();
        String requestX = openWindow("u1", "team/x");
        String requestZ = openWindow("u2", "team/z");
        moveIntoVault("team/x");
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/requests/grant/" + requestX + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the request is stored at " + file);
        Files.delete(file);
        NotificationCapture.clear();

        expiryWork();
        NotificationCapture.await(NotificationEvent.GRANT_EXPIRING, requestZ);
        Thread.sleep(NotificationCapture.QUIET_PERIOD_MS); // bounded wait for a delivery that must not name the vault
        List<NotificationCapture> all = NotificationCapture.matching(c -> c.event == NotificationEvent.GRANT_EXPIRING);
        System.out.println("T-GAP-373 observation: " + all);
        for (NotificationCapture notice : all) {
            assertNoVault(notice, "a GRANT_EXPIRING notice after the moved window's request file vanished");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void matrixFixture() throws Exception {
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u2"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
        Folder team = j.jenkins.createProject(Folder.class, "team");
        team.createProject(FreeStyleProject.class, "x");
        team.createProject(FreeStyleProject.class, "z");
        Folder vault = j.jenkins.createProject(Folder.class, VAULT);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty adminOnly =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        adminOnly.setInheritanceStrategy(new NonInheritingStrategy());
        adminOnly.add(Item.READ, PermissionEntry.user("admin"));
        vault.addProperty(adminOnly);
        assertFalse(can("u1", vault, Item.READ), "fixture: u1 may not read " + VAULT);
    }

    private void mail() throws Exception {
        JenkinsLocationConfiguration.get().setAdminAddress("batch-control@example.com");
        User.getById("u1", true).addProperty(new Mailer.UserProperty(U1_MAIL));
        cfg.setEmailNotifications(true);
        cfg.save();
    }

    /** A 30-minute CONFIGURE window requested by {@code userId} and approved by a1 (services); returns the request id. */
    private String openWindow(String userId, String fullName) throws Exception {
        GrantRequest request = as(userId, () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                List.of(GrantAction.CONFIGURE), 30, "quarterly maintenance", "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval opens a window");
        return request.getId();
    }

    /** The administrator moves {@code fullName} into {@code vault-l3}; the window follows (premise). */
    private Item moveIntoVault(String fullName) throws Exception {
        Item item = j.jenkins.getItemByFullName(fullName);
        Folder vault = (Folder) j.jenkins.getItemByFullName(VAULT);
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            Items.move((FreeStyleProject) item, vault);
        }
        Item moved = j.jenkins.getItemByFullName(VAULT + "/" + item.getName());
        assertNotNull(moved, "premise: " + fullName + " is now in " + VAULT);
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> (VAULT + "/" + item.getName()).equals(g.getScope().getFullName())),
                "premise (D-74): the window follows into " + VAULT);
        return moved;
    }

    private void expiryWork() throws Exception {
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(21)), ZoneOffset.UTC));
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
    }

    private static NotificationCapture single(List<NotificationCapture> notices, String recipient) {
        assertEquals(1, notices.size(), "premise (SPEC 13): one GRANT_EXPIRING notice: " + notices);
        assertEquals(List.of(recipient), notices.get(0).recipients, "premise (SPEC 13): GRANT_EXPIRING goes to the holder");
        return notices.get(0);
    }

    private static void assertNoVault(NotificationCapture notice, String what) {
        for (String field : new String[] {notice.subject, notice.reason, notice.url, notice.requester, notice.kind, notice.action}) {
            assertFalse(field != null && field.contains(VAULT), "D-75 (1): " + what + " must not name " + VAULT + " in any field: " + notice);
        }
    }

    private static String mailFor(String requestId) throws Exception {
        long deadline = System.currentTimeMillis() + NotificationCapture.DELIVERY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (Message m : new ArrayList<>(Mailbox.get(U1_MAIL))) {
                String body = String.valueOf(m.getContent());
                if (body.contains(requestId)) {
                    return body;
                }
            }
            Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
        }
        throw new AssertionError("no mail carrying " + requestId + " reached " + U1_MAIL);
    }

    private static boolean can(String userId, Item item, Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String userId, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(userId, true).impersonate2())) {
            return body.run();
        }
    }
}
