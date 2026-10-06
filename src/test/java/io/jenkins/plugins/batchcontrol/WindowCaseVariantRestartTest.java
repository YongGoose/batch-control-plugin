package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEF-E17-01 (docs/reports/e2e-17.md F-2): after a restart, an item that arrives next to a windowed item
 * under another letter case must not end the window, because the window's own item is still there.
 * Matrix rows T-SEC-116 (a new item {@code f/CTL} next to {@code f/ctl}) and T-SEC-117 (another item
 * renamed to {@code f/CTL} next to {@code f/ctl}) (note 280).
 *
 * <p>Basis: SPEC 8 line 170 ("a window applies to its item, not to a name ... deleting the item ends the
 * window, as do creating a new item at the window's name and starting Jenkins after the item vanished; so
 * renaming, moving, swapping or re-creating items never makes a window reach an item nobody approved");
 * LIMITATIONS 11: "For a new item, and for an item renamed or moved onto a window's name (above), names are
 * compared as Jenkins usually looks them up, without regard to letter case, so a window under another
 * spelling of that name ends too. A folder loaded from disk (after a restart or a reload) looks its
 * children up by exact name, though, and can then hold two items whose names differ only in letter case. A
 * window whose own item is still there under exactly the window's name is therefore about that item and
 * stays, whatever arrives next to it under another spelling (DEF-E17-01)"; D-75 (2) / security-39 S-39-02
 * for the guard (an item created under a case variant of a deleted item's name gets nothing).
 *
 * <p>The folders are created in session 1, so in session 2 they are loaded from disk. Whether core then
 * admits the case variant next to the windowed job is a property of core and the folders plugin, not of
 * Batch Control: it is checked as a premise (the row is skipped, with the observation printed, if core
 * refuses it, because DEF-E17-01 cannot arise then).
 *
 * <p>Fixture: change control on, Batch Control matrix strategy; u1 (Overall/Read, Item/Read,
 * RequestGrant) holds 60-minute CONFIGURE windows approved by a1; the administrator acts over HTTP.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-74 and D-75, docs/LIMITATIONS.md item 11 and
 * docs/reports/e2e-17.md only (no src/main knowledge).
 */
public class WindowCaseVariantRestartTest {

    @RegisterExtension
    final JenkinsSessionExtension session = new JenkinsSessionExtension();

    private final Map<String, String> ids = new HashMap<>();

    /**
     * T-SEC-116 (DEF-E17-01; LIMITATIONS 11, SPEC 8 line 170): session 1: folders {@code f} and {@code g},
     * jobs {@code f/ctl} and {@code g/ctl}, u1's windows W on {@code f/ctl} and G on {@code g/ctl}. Session 2
     * (after a restart): the administrator creates {@code f/CTL} on {@code f}'s New Item endpoint
     * (premise: core creates it next to {@code f/ctl}, two distinct items). W is still active on
     * {@code f/ctl} (service and u1's Active list), still confers Configure on {@code f/ctl}, confers none on
     * {@code f/CTL}, and no GRANT_REVOKE record identifies it. Guard (S-39-02): the administrator deletes
     * {@code g/ctl} and creates {@code g/CTL}: G has ended and confers nothing on {@code g/CTL}.
     */
    @Test
    public void t_sec_116_caseVariantCreatedNextToAWindowedJobAfterARestartKeepsTheWindow() throws Throwable {
        session.then(r -> {
            prepare(r);
            Folder f = r.jenkins.createProject(Folder.class, "f");
            f.createProject(FreeStyleProject.class, "ctl");
            Folder g = r.jenkins.createProject(Folder.class, "g");
            g.createProject(FreeStyleProject.class, "ctl");
            ids.put("W", window("u1", "f/ctl"));
            ids.put("G", window("u1", "g/ctl"));
            assertTrue(can("u1", r.jenkins.getItemByFullName("f/ctl"), Item.CONFIGURE), "premise: W confers Configure on f/ctl");
            assertTrue(can("u1", r.jenkins.getItemByFullName("g/ctl"), Item.CONFIGURE), "premise: G confers Configure on g/ctl");
        });
        session.then(r -> {
            Item lower = r.jenkins.getItemByFullName("f/ctl");
            assertNotNull(lower, "premise: f/ctl survived the restart");
            WindowStateFixtures.assertActiveOn(r, "u1", ids.get("W"), "f/ctl", "premise: W survived the restart");
            Set<String> before = WindowStateFixtures.revokeRecordIds();

            WebResponse created = createAsAdmin(r, "job/f/", "CTL");
            Item upper = r.jenkins.getItemByFullName("f/CTL");
            System.out.println("T-SEC-116 observation: createItem f/CTL answered " + created.getStatusCode() + "; f/CTL "
                    + (upper == null ? "absent" : upper == lower ? "resolves to f/ctl" : "is a new item"));
            Assumptions.assumeTrue(created.getStatusCode() < 400 && upper != null && upper != lower,
                    "core did not create f/CTL next to f/ctl after the restart, so DEF-E17-01 cannot arise: HTTP "
                            + created.getStatusCode() + " " + excerpt(created.getContentAsString()));
            assertNotNull(r.jenkins.getItemByFullName("f/ctl"), "premise: f/ctl still exists next to f/CTL");

            WindowStateFixtures.assertActiveOn(r, "u1", ids.get("W"), "f/ctl",
                    "DEF-E17-01: a window whose own item still exists is not ended by a case variant created next to it");
            assertTrue(can("u1", r.jenkins.getItemByFullName("f/ctl"), Item.CONFIGURE), "DEF-E17-01: W still confers Configure on f/ctl");
            assertFalse(can("u1", upper, Item.CONFIGURE), "SPEC 8 line 170: W confers nothing on the new f/CTL");
            assertNoRevokeOf(before, ids.get("W"), "f/ctl");

            // Guard (S-39-02): the window's own item is deleted, then an item is created under a case variant of its name.
            assertSuccess(ApproverFormFixtures.post(r, "admin", "job/g/job/ctl/doDelete", List.of()), "the administrator deletes g/ctl");
            assertNull(r.jenkins.getItemByFullName("g/ctl"), "premise: g/ctl is deleted");
            assertSuccess(createAsAdmin(r, "job/g/", "CTL"), "the administrator creates g/CTL");
            Item gUpper = r.jenkins.getItemByFullName("g/CTL");
            assertNotNull(gUpper, "premise: g/CTL exists");
            WindowStateFixtures.assertEnded(r, "u1", ids.get("G"), "guard (S-39-02): the window on the deleted g/ctl");
            assertFalse(can("u1", gUpper, Item.CONFIGURE), "guard (S-39-02): the window on the deleted g/ctl confers nothing on g/CTL");
        });
    }

    /**
     * T-SEC-117 (DEF-E17-01, rename half; LIMITATIONS 11 "an item renamed or moved onto a window's name ...
     * whatever arrives next to it under another spelling"; e2e-17 F-2 "the same mechanism makes a rename onto
     * a case variant end an unrelated window"): session 1: folder {@code f} with jobs {@code f/ctl} and
     * {@code f/other}; u1's window W on {@code f/ctl}. Session 2 (after a restart): the administrator renames
     * {@code f/other} to {@code CTL} on core's rename endpoint (premise: core renames it, and {@code f/ctl}
     * and {@code f/CTL} are two distinct items). W is still active on {@code f/ctl}, still confers Configure
     * on {@code f/ctl}, confers none on {@code f/CTL}, and no GRANT_REVOKE record identifies it.
     */
    @Test
    public void t_sec_117_itemRenamedToACaseVariantNextToAWindowedJobAfterARestartKeepsTheWindow() throws Throwable {
        session.then(r -> {
            prepare(r);
            Folder f = r.jenkins.createProject(Folder.class, "f");
            f.createProject(FreeStyleProject.class, "ctl");
            f.createProject(FreeStyleProject.class, "other");
            ids.put("W", window("u1", "f/ctl"));
            assertTrue(can("u1", r.jenkins.getItemByFullName("f/ctl"), Item.CONFIGURE), "premise: W confers Configure on f/ctl");
        });
        session.then(r -> {
            Item lower = r.jenkins.getItemByFullName("f/ctl");
            assertNotNull(lower, "premise: f/ctl survived the restart");
            WindowStateFixtures.assertActiveOn(r, "u1", ids.get("W"), "f/ctl", "premise: W survived the restart");
            Set<String> before = WindowStateFixtures.revokeRecordIds();

            WebResponse renamed = ApproverFormFixtures.post(r, "admin", "job/f/job/other/confirmRename",
                    List.of(new NameValuePair("newName", "CTL")));
            Item upper = r.jenkins.getItemByFullName("f/CTL");
            System.out.println("T-SEC-117 observation: confirmRename f/other -> CTL answered " + renamed.getStatusCode() + "; f/CTL "
                    + (upper == null ? "absent" : upper == lower ? "resolves to f/ctl" : "is the renamed item"));
            Assumptions.assumeTrue(renamed.getStatusCode() < 400 && upper != null && upper != lower
                            && r.jenkins.getItemByFullName("f/other") == null,
                    "core did not rename f/other to CTL next to f/ctl after the restart, so DEF-E17-01 cannot arise: HTTP "
                            + renamed.getStatusCode() + " " + excerpt(renamed.getContentAsString()));
            assertNotNull(r.jenkins.getItemByFullName("f/ctl"), "premise: f/ctl still exists next to f/CTL");

            WindowStateFixtures.assertActiveOn(r, "u1", ids.get("W"), "f/ctl",
                    "DEF-E17-01: a window whose own item still exists is not ended by an item renamed to a case variant next to it");
            assertTrue(can("u1", r.jenkins.getItemByFullName("f/ctl"), Item.CONFIGURE), "DEF-E17-01: W still confers Configure on f/ctl");
            assertFalse(can("u1", upper, Item.CONFIGURE), "SPEC 8 line 170: W confers nothing on the renamed f/CTL");
            assertNoRevokeOf(before, ids.get("W"), "f/ctl");
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void prepare(JenkinsRule r) throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("u1"));
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(strategy);
        r.jenkins.save();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    /** {@code user}'s 60-minute CONFIGURE window on {@code fullName}, approved by a1; returns the window id. */
    private static String window(String user, String fullName) throws Exception {
        GrantRequest request = as(user, () -> GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                List.of(GrantAction.CONFIGURE), 60, "maintenance of " + fullName, "a1"));
        Grant grant = as("a1", () -> GrantRequestService.get().approve(request.getId(), "ok"));
        assertNotNull(grant, "fixture: the approval opens a window on " + fullName);
        return grant.getId();
    }

    /** The administrator's {@code POST <folderUrl>createItem} (New Item form fields) for a Freestyle job {@code name}. */
    private static WebResponse createAsAdmin(JenkinsRule r, String folderUrl, String name) throws Exception {
        return ApproverFormFixtures.post(r, "admin", folderUrl + "createItem", List.of(
                new NameValuePair("name", name), new NameValuePair("mode", FreeStyleProject.class.getName())));
    }

    private static void assertNoRevokeOf(Set<String> before, String grantId, String fullName) {
        List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before).stream()
                .filter(rec -> WindowStateFixtures.identifies(rec, grantId, fullName)).toList();
        assertTrue(revokes.isEmpty(), "DEF-E17-01: no GRANT_REVOKE record ends the window on " + fullName + ": "
                + WindowStateFixtures.describe(revokes));
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item != null && item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private interface Body<T> {
        T run() throws Exception;
    }

    private static <T> T as(String user, Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return body.run();
        }
    }
}
