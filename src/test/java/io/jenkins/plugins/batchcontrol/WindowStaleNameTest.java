package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.model.View;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-39 S-39-02: a window must never be left on a name its item no longer has, where an item
 * created, renamed or moved to that name would pick it up. SPEC item 8 line 170: "a window applies
 * to its item, not to a name ... so renaming, moving, swapping or re-creating items never makes a
 * window reach an item nobody approved"; DECISIONS D-71c (3): "approval re-verifies the item's
 * identity after registering the grant"; D-74 (3). Matrix rows T-SEC-92 (the approval race),
 * T-SEC-93 (a follow whose write failed) and T-SEC-94 (an item renamed or moved into a name a
 * window was left on) (note 274); T-SEC-100 (a D-35c created-item record whose follow could not be
 * written), T-SEC-101 (an item created under a case variant of a vanished item's name), T-SEC-102
 * (the approval's registration after the deletion event, before the name is freed) and T-SEC-104
 * (a window that cannot follow its item ends with a record), from D-75 (2) and ARCHITECTURE 4
 * (note 275). T-SEC-100's fixture as the owner settled it on 2026-10-06, its twin T-SEC-105 (an
 * item created as SYSTEM inside the guarded folder keeps its own changed-under-grant state) and
 * T-SEC-112 (a stale changed-under-grant entry of a vanished item does not pass to an item renamed
 * into its name), from D-58a, D-75 (2) and ARCHITECTURE 5 (note 278).
 *
 * <p>"Changed under a grant" is read two ways: the stored window's {@code changedItems} list
 * (ARCHITECTURE 5, D-58a (5)) and its consequence once every window has ended: a script's
 * (SYSTEM, not an HTTP request) widening of an item's authorization is reverted and recorded as
 * GRANT_VIOLATION on an item in that state and kept on any other (D-58a (2); T-02-58, T-02-65).
 * The windows end by moving the plugin clock ({@code BatchClock}), never by sleeping.
 *
 * <p>The approval race cannot be timed from a test: approval checks the item, writes the request,
 * then registers the window under the checked object's full name, and no public extension point
 * runs in between. The rows therefore play the last step themselves, as security-39's probe did:
 * {@code GrantService#register(Grant, Item)} (public) is called, as the approver, with the item
 * object the approval checked, after that item was deleted (and, for T-SEC-92, after a new item was
 * created at its name). A guard in each row shows that the same call makes a working window when
 * the checked item is still in place, so a green row cannot come from a call that never works.
 *
 * <p>Fixture: change control on, Batch Control matrix strategy; u1 (Overall/Read, Item/Read,
 * RequestGrant), a1 approver, admin. What a window confers is read from the item's own ACL.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-71c and D-74, docs/ARCHITECTURE.md
 * sections 4 and 5 and the Given/When/Then of docs/reports/security-39.md only (no src/main
 * knowledge; the signature of {@code GrantService#register} was taken from the compiler's answer to
 * a call).
 */
@WithJenkins
public class WindowStaleNameTest {

    /** The job of the template window whose stored file {@link #registerAsApproval} copies. */
    private static final String TEMPLATE_JOB = "template-job-q7z";

    private JenkinsRule j;
    private GrantRequest templateRequest;
    private Grant template;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
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
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-SEC-92 (S-39-02 (a), D-71c (3)): u1 requests a CONFIGURE window on the job {@code r}. The
     * approval checks {@code r}; before the window is registered the administrator deletes
     * {@code r} and creates a new job {@code r}; then the window is registered for the checked
     * (deleted) object. u1 holds no Configure on the new {@code r}. Guard: the same registration
     * for an item still in place ({@code h}) confers Configure on it.
     */
    @Test
    public void t_sec_92_windowRegisteredAfterItsItemWasReplacedDoesNotReachTheNewItem() throws Exception {
        FreeStyleProject h = j.createFreeStyleProject("h");
        registerAsApproval(request("u1", "h"), h);
        assertTrue(can("u1", h, Item.CONFIGURE), "guard: registering for an item still in place confers Configure on it");

        FreeStyleProject r = j.createFreeStyleProject("r");
        GrantRequest pending = request("u1", "r");
        Item replacement;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator replaces r between the check and the registration
            r.delete();
            replacement = j.jenkins.createProject(FreeStyleProject.class, "r");
        }
        assertFalse(can("u1", replacement, Item.CONFIGURE), "premise: before the registration the new r confers nothing");
        registerAsApproval(pending, r);

        assertFalse(can("u1", replacement, Item.CONFIGURE),
                "S-39-02 (a): a window approved for the deleted r must not reach the job created at its name (D-71c (3))");
    }

    /**
     * T-SEC-93 (S-39-02 (b)): u1's approved CONFIGURE window on the job {@code a}. The grants
     * directory and the window's file refuse writes while the administrator renames {@code a} to
     * {@code b} (HTTP {@code confirmRename}, 3xx); write access is restored; the administrator
     * renames {@code x} to {@code a}. u1 holds no Configure on the item now named {@code a}, and the
     * window either names {@code b} or has ended with a GRANT_REVOKE record. Skipped where this
     * process can write despite the read-only bits (root, Windows).
     */
    @Test
    public void t_sec_93_followWhoseWriteFailedLeavesNoWindowForAnItemRenamedIntoTheOldName() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("a");
        FreeStyleProject x = j.createFreeStyleProject("x");
        Grant window = approve(request("u1", "a"));
        assertTrue(can("u1", a, Item.CONFIGURE), "premise: the window confers Configure on a");
        Set<String> revokesBefore = WindowStateFixtures.revokeRecordIds();

        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        File dirFile = dir.toFile();
        File storedFile = dir.resolve(window.getId() + ".xml").toFile();
        assertTrue(storedFile.isFile(), "premise (ARCHITECTURE 5): the window is stored at " + storedFile);
        try {
            assertTrue(storedFile.setWritable(false, false), "fixture: the stored window made read-only");
            assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
            Assumptions.assumeTrue(writesRefused(dir) && !Files.isWritable(storedFile.toPath()),
                    "the file system does not refuse writes to read-only files for this process");
            assertRedirect(rename("admin", a, "b"), "the administrator renames a to b while the grant store refuses writes");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }
        assertNotNull(j.jenkins.getItemByFullName("b"), "premise: a is now b");
        assertRedirect(rename("admin", x, "a"), "the administrator renames x to a after write access was restored");
        Item nowA = j.jenkins.getItemByFullName("a");
        assertNotNull(nowA, "premise: x is now a");

        assertFalse(can("u1", nowA, Item.CONFIGURE),
                "S-39-02 (b): the item renamed into the name a failed follow left behind must get nothing from u1's window");
        Grant now = WindowStateFixtures.active(window.getId());
        if (now != null) {
            assertEquals("b", now.getScope().getFullName(), "S-39-02 (b): a window still active must name its item's name b");
        } else {
            List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(revokesBefore);
            assertTrue(revokes.stream().anyMatch(rec -> WindowStateFixtures.identifies(rec, window.getId(), "a")
                    || WindowStateFixtures.identifies(rec, window.getId(), "b")),
                    "S-39-02 (b): a window that is no longer active must have ended with a GRANT_REVOKE record, got "
                            + WindowStateFixtures.describe(revokes));
        }
    }

    /**
     * T-SEC-94 (S-39-02 (a) second form and fix direction (2)): (1) u1 requests a window on the job
     * {@code s}; the administrator deletes {@code s} before the window is registered for it, so the
     * window is left on the free name {@code s}; the administrator then renames the job
     * {@code other} to {@code s} (HTTP): u1 holds no Configure on it. (2) The same with the job
     * {@code t}, and the administrator moves {@code f/t} into the Jenkins root (HTTP
     * {@code move/move}, destination {@code /}): u1 holds no Configure on it. Guard: u1's approved
     * window on {@code p} follows the administrator's rename of {@code p} to {@code p2}; after the
     * administrator renames {@code q} to {@code p}, u1 holds no Configure on the new {@code p} and
     * still holds it on {@code p2}.
     */
    @Test
    public void t_sec_94_itemRenamedOrMovedIntoANameAWindowWasLeftOnGetsNothing() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("p");
        FreeStyleProject q = j.createFreeStyleProject("q");
        Grant followed = approve(request("u1", "p"));
        assertRedirect(rename("admin", p, "p2"), "guard: the administrator renames p to p2");
        WindowStateFixtures.assertActiveOn(j, "u1", followed.getId(), "p2", "guard: the window follows p to p2");
        assertRedirect(rename("admin", q, "p"), "guard: the administrator renames q to p");
        assertFalse(can("u1", j.jenkins.getItemByFullName("p"), Item.CONFIGURE), "guard: the job renamed into p gets nothing");
        assertTrue(can("u1", j.jenkins.getItemByFullName("p2"), Item.CONFIGURE), "guard: the window still confers on p2");

        FreeStyleProject s = j.createFreeStyleProject("s");
        FreeStyleProject other = j.createFreeStyleProject("other");
        GrantRequest onS = request("u1", "s");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes s between the check and the registration
            s.delete();
        }
        registerAsApproval(onS, s);
        assertRedirect(rename("admin", other, "s"), "the administrator renames other to s");
        Item renamedIn = j.jenkins.getItemByFullName("s");
        assertNotNull(renamedIn, "premise: other is now s");
        assertFalse(can("u1", renamedIn, Item.CONFIGURE),
                "S-39-02: the job renamed into the name s, which a window was left on, must get nothing from it");

        FreeStyleProject t = j.createFreeStyleProject("t");
        Folder f = j.jenkins.createProject(Folder.class, "f");
        FreeStyleProject ft = f.createProject(FreeStyleProject.class, "t");
        GrantRequest onT = request("u1", "t");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator deletes t between the check and the registration
            t.delete();
        }
        registerAsApproval(onT, t);
        assertRedirect(ApproverFormFixtures.post(j, "admin", ft.getUrl() + "move/move", List.of(new NameValuePair("destination", "/"))),
                "the administrator moves f/t into the root");
        Item movedIn = j.jenkins.getItemByFullName("t");
        assertNotNull(movedIn, "premise: f/t is now t");
        assertNull(f.getItem("t"), "premise: f/t left f");
        assertFalse(can("u1", movedIn, Item.CONFIGURE),
                "S-39-02: the job moved into the name t, which a window was left on, must get nothing from it");
    }

    /**
     * T-SEC-100 (S-39-02 (b), D-35c; D-75 (2); ARCHITECTURE 4 "D-35c records are updated in memory
     * first and their write is retried"; fixture as the owner settled it on 2026-10-06, note 278):
     * u1 holds a CREATE window on the folder {@code fo} and creates {@code fo/j} through it (HTTP
     * {@code createItem}; u1 configures it, D-35c; it is changed under the window, D-58a (1),
     * premise). The administrator creates {@code fo/m} over HTTP {@code createItem}, the creation
     * D-58a (2) exempts, so it is not changed under the window (premise). The grants directory and
     * the window's file refuse writes while the administrator renames {@code fo/j} to {@code fo/k}
     * (HTTP, 3xx); write access is restored; the administrator renames {@code fo/m} to
     * {@code fo/j}. u1 holds no Configure on the item now at {@code fo/j} and keeps Configure on
     * {@code fo/k}; after the next write (the periodic work at the latest) the stored window's
     * {@code createdItems} and {@code changedItems} (ARCHITECTURE 5: both follow renames and moves)
     * list {@code fo/k} and not {@code fo/j}; and {@code fo/j} is not changed under a grant: once the
     * window has ended, a script's widening of its authorization is kept and not recorded, while the
     * same widening of {@code fo/k} is reverted and recorded. Guard: the window on {@code fo} stays
     * active; before the failing phase, with writes succeeding, both lists follow the
     * administrator's rename of another created job {@code fo/g1} to {@code fo/g2}. Skipped where
     * this process can write despite the read-only bits. The twin with {@code fo/m} created as
     * SYSTEM is T-SEC-105.
     */
    @Test
    public void t_sec_100_createdItemRecordFollowsARenameWhoseWriteFailedAndNothingReachesTheOldName() throws Exception {
        FailedFollow run = failedFollowOfACreatedJob(false);

        String stored = WindowStateFixtures.storedGrant(j, run.window().getId());
        String flat = stored.replaceAll("\\s+", " ");
        String changedList = listed(stored, "changedItems");
        assertTrue(changedList.contains(">fo/k<"), "ARCHITECTURE 5 (changedItems follows renames and moves): fo/j was changed under the"
                + " window, so after its rename to fo/k the stored changedItems must list fo/k: " + flat);
        assertFalse(changedList.contains(">fo/j<"), "D-58a (2), D-75 (2): the stored changedItems must not list fo/j, which now names the"
                + " administrator's job created over HTTP: " + flat);

        afterWindows();
        assertFalse(can("u1", run.created(), Item.CONFIGURE), "premise: after the window ended u1 holds no Configure on fo/k");
        Set<String> violationsBefore = violationIds();
        assertTrue(scriptWideningKept((FreeStyleProject) run.nowJ()),
                "D-58a: fo/j, the administrator's job created over HTTP, is not changed under a grant, so after the window a script's"
                        + " widening of its authorization must be kept; GRANT_VIOLATION records since: " + describeViolationsSince(violationsBefore));
        assertTrue(violationsNaming(violationsBefore, "fo/j").isEmpty(), "D-58a: no GRANT_VIOLATION for fo/j: "
                + describeViolationsSince(violationsBefore));
        assertFalse(scriptWideningKept(run.created()), "guard (D-58a (2)): fo/k is still changed under the window, so the same widening of"
                + " it is reverted");
        assertFalse(violationsNaming(violationsBefore, "fo/k").isEmpty(), "guard (D-58a (2)): the reverted widening of fo/k is recorded"
                + " as GRANT_VIOLATION: " + describeViolationsSince(violationsBefore));
    }

    /**
     * T-SEC-105 (twin of T-SEC-100; D-58a (2) and (5), ARCHITECTURE 5 "changedItems follows renames
     * and moves"; owner decision of 2026-10-06, note 278): as T-SEC-100, but {@code fo/m} is created
     * as SYSTEM (a script, not an administrator's HTTP request) while the window is active, so it
     * enters the window's changed-under-grant state (premise: the stored {@code changedItems} lists
     * it). After the failed-write rename of {@code fo/j} to {@code fo/k} and the administrator's
     * rename of {@code fo/m} to {@code fo/j}: u1 holds no Configure on {@code fo/j}; the stored
     * {@code createdItems} lists {@code fo/k} and not {@code fo/j}; the stored {@code changedItems}
     * lists {@code fo/j} (fo/m's own state, followed) and {@code fo/k}; once the window has ended, a
     * script's widening of {@code fo/j} is reverted and recorded (it is still changed under the
     * grant).
     */
    @Test
    public void t_sec_105_systemCreatedItemKeepsItsOwnChangedStateWhenRenamedIntoTheOldName() throws Exception {
        FailedFollow run = failedFollowOfACreatedJob(true);

        String stored = WindowStateFixtures.storedGrant(j, run.window().getId());
        String flat = stored.replaceAll("\\s+", " ");
        String changedList = listed(stored, "changedItems");
        assertTrue(changedList.contains(">fo/k<"), "ARCHITECTURE 5: fo/j was changed under the window, so after its rename to fo/k the"
                + " stored changedItems must list fo/k: " + flat);
        assertTrue(changedList.contains(">fo/j<"), "D-58a (2)/(5), ARCHITECTURE 5: fo/m, created as SYSTEM inside the guarded folder, is"
                + " changed under the window and that state follows its rename to fo/j, so the stored changedItems must list fo/j: " + flat);

        afterWindows();
        Set<String> violationsBefore = violationIds();
        assertFalse(scriptWideningKept((FreeStyleProject) run.nowJ()),
                "D-58a (2): fo/j (formerly fo/m) is still changed under the grant, so after the window a script's widening of it must be"
                        + " reverted");
        assertFalse(violationsNaming(violationsBefore, "fo/j").isEmpty(), "D-58a (2): the reverted widening of fo/j is recorded as"
                + " GRANT_VIOLATION: " + describeViolationsSince(violationsBefore));
    }

    /**
     * T-SEC-112 (D-58a (1), (5); ARCHITECTURE 5 "changedItems ... loses an item when it is
     * deleted"; D-75 (2) and LIMITATIONS 11 "dropped when the item is deleted or another item takes
     * its name"; note 278): u1 holds a CREATE window on {@code fo} and creates {@code fo/j} and
     * {@code fo/g} through it (both changed under the window, premise). The directory of
     * {@code fo/j} is removed on disk and the configuration is reloaded ({@code Jenkins.reload()},
     * no deletion event). The administrator creates {@code fo/n} over HTTP {@code createItem} (not
     * changed under the window, premise) and renames it to {@code fo/j}. u1 holds no Configure on the
     * job now at {@code fo/j}; after the periodic work the stored {@code changedItems} does not list
     * {@code fo/j}; once the window has ended, a script's widening of {@code fo/j} is kept and not
     * recorded. Guard: {@code fo/g} stays listed and the same widening of it is reverted and
     * recorded. Whether the reload already dropped the stale entry is reported, not pinned.
     */
    @Test
    public void t_sec_112_staleChangedEntryDoesNotPassToAJobRenamedIntoItsName() throws Exception {
        Folder fo = j.jenkins.createProject(Folder.class, "fo");
        Grant window = approve(request("u1", "fo", GrantAction.CREATE));
        assertTrue(createJob("u1", fo, "j") < 400, "fixture: u1 creates fo/j through the CREATE window on fo");
        assertTrue(createJob("u1", fo, "g") < 400, "fixture: u1 creates fo/g through the CREATE window on fo");
        String before = WindowStateFixtures.storedGrant(j, window.getId());
        assertTrue(listed(before, "changedItems").contains(">fo/j<") && listed(before, "changedItems").contains(">fo/g<"),
                "premise (D-58a (1), ARCHITECTURE 5): the jobs u1 created through the window are changed under it: "
                        + before.replaceAll("\\s+", " "));
        j.jenkins.save(); // fixture: the security configuration must survive the reload from disk

        deleteTree(j.jenkins.getRootDir().toPath().resolve("jobs").resolve("fo").resolve("jobs").resolve("j"));
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator reloads the configuration from disk
            j.jenkins.reload();
        }
        assertNull(j.jenkins.getItemByFullName("fo/j"), "premise: fo/j vanished without a deletion event");
        Folder foNow = j.jenkins.getItemByFullName("fo", Folder.class);
        assertNotNull(foNow, "premise: fo survived the reload");
        boolean staleAfterReload = listed(WindowStateFixtures.storedGrant(j, window.getId()), "changedItems").contains(">fo/j<");
        System.out.println("T-SEC-112 observation: the stored changedItems still lists the vanished fo/j after the reload = " + staleAfterReload);

        assertTrue(createJob("admin", foNow, "n") < 400, "the administrator creates fo/n over HTTP createItem");
        FreeStyleProject n = j.jenkins.getItemByFullName("fo/n", FreeStyleProject.class);
        assertNotNull(n, "premise: fo/n exists");
        assertFalse(listed(WindowStateFixtures.storedGrant(j, window.getId()), "changedItems").contains(">fo/n<"),
                "premise (D-58a (2)): the administrator's HTTP creation is not changed under the window");
        assertRedirect(rename("admin", n, "j"), "the administrator renames fo/n to fo/j");
        assertEquals("fo/j", n.getFullName(), "premise: fo/n is now fo/j");

        assertFalse(can("u1", n, Item.CONFIGURE), "D-75 (2), LIMITATIONS 11: u1's created-item record of the vanished fo/j must not pass to"
                + " the job renamed into its name (stale entry still stored after the reload: " + staleAfterReload + ")");
        assertNotNull(WindowStateFixtures.active(window.getId()), "guard: the CREATE window on fo is still active");
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun(); // a documented retry point (ARCHITECTURE 4)
        String stored = WindowStateFixtures.storedGrant(j, window.getId());
        String flat = stored.replaceAll("\\s+", " ");
        assertFalse(listed(stored, "changedItems").contains(">fo/j<"), "ARCHITECTURE 5, D-58a: the stale changedItems entry of the vanished"
                + " fo/j must not stay on the administrator's job renamed into that name (stale entry still stored after the reload: "
                + staleAfterReload + "): " + flat);
        assertTrue(listed(stored, "changedItems").contains(">fo/g<"), "guard: fo/g stays changed under the window: " + flat);

        afterWindows();
        FreeStyleProject g = j.jenkins.getItemByFullName("fo/g", FreeStyleProject.class);
        assertNotNull(g, "premise: fo/g survived the reload");
        assertFalse(can("u1", g, Item.CONFIGURE), "premise: after the window ended u1 holds no Configure on fo/g");
        Set<String> violationsBefore = violationIds();
        assertTrue(scriptWideningKept(n), "D-58a: the administrator's job renamed into fo/j is not changed under a grant, so after the window"
                + " a script's widening of its authorization must be kept (stale entry still stored after the reload: " + staleAfterReload
                + "); GRANT_VIOLATION records since: " + describeViolationsSince(violationsBefore));
        assertTrue(violationsNaming(violationsBefore, "fo/j").isEmpty(), "D-58a: no GRANT_VIOLATION for fo/j: "
                + describeViolationsSince(violationsBefore));
        assertFalse(scriptWideningKept(g), "guard (D-58a (2)): fo/g is still changed under the window, so the same widening of it is reverted");
        assertFalse(violationsNaming(violationsBefore, "fo/g").isEmpty(), "guard (D-58a (2)): the reverted widening of fo/g is recorded as"
                + " GRANT_VIOLATION: " + describeViolationsSince(violationsBefore));
    }

    /**
     * T-SEC-101 (S-39-02 fix direction (4); SPEC 8 line 170 "creating a new item at the window's
     * name ... ends the window"): u1's CONFIGURE windows on the jobs {@code gone} and
     * {@code keep-g}. The directory of {@code gone} is removed on disk and the configuration is
     * reloaded ({@code Jenkins.reload()}, no deletion event), so no item carries the name; the
     * administrator creates the job {@code GONE} (HTTP {@code createItem}). u1 holds no Configure on
     * {@code GONE}; where Jenkins' name lookup finds {@code GONE} under the name {@code gone} (core
     * compares item names case-insensitively; premise checked, not assumed), the window on
     * {@code gone} has ended (not active, no Active list row, its page still opens). Guard: the
     * window on {@code keep-g} is active and confers. Whether the window already ended at the reload
     * is reported, not pinned (as T-08-187).
     */
    @Test
    public void t_sec_101_itemCreatedUnderACaseVariantOfAVanishedItemsNameEndsItsWindow() throws Exception {
        j.createFreeStyleProject("gone");
        j.createFreeStyleProject("keep-g");
        Grant onGone = approve(request("u1", "gone"));
        Grant onKeep = approve(request("u1", "keep-g"));
        j.jenkins.save(); // fixture: the security configuration must survive the reload from disk

        deleteTree(j.jenkins.getRootDir().toPath().resolve("jobs").resolve("gone"));
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator reloads the configuration from disk
            j.jenkins.reload();
        }
        assertNull(j.jenkins.getItemByFullName("gone"), "premise: gone vanished without a deletion event");
        boolean activeAfterReload = WindowStateFixtures.active(onGone.getId()) != null;

        ApproverFormFixtures.assertSuccess(ApproverFormFixtures.post(j, "admin", "createItem", List.of(
                new NameValuePair("name", "GONE"), new NameValuePair("mode", FreeStyleProject.class.getName()))),
                "the administrator creates the job GONE");
        FreeStyleProject upper = j.jenkins.getItemByFullName("GONE", FreeStyleProject.class);
        assertNotNull(upper, "premise: GONE exists");
        boolean caseInsensitiveLookup = j.jenkins.getItemByFullName("gone") == upper;
        System.out.println("T-SEC-101 observation: window on gone active after the reload = " + activeAfterReload
                + "; Jenkins finds GONE at the name gone = " + caseInsensitiveLookup);

        assertFalse(can("u1", upper, Item.CONFIGURE), "S-39-02: the window on the vanished gone must not reach GONE"
                + " (window still active after the reload: " + activeAfterReload + ")");
        if (caseInsensitiveLookup) {
            WindowStateFixtures.assertEnded(j, "u1", onGone.getId(), "S-39-02 (4): the window on gone after GONE, which Jenkins finds at the"
                    + " name gone, was created (window still active after the reload: " + activeAfterReload + ")");
        }
        FreeStyleProject keepNow = j.jenkins.getItemByFullName("keep-g", FreeStyleProject.class);
        assertNotNull(keepNow, "premise: keep-g survived the reload");
        WindowStateFixtures.assertActiveOn(j, "u1", onKeep.getId(), "keep-g", "guard: the untouched window on keep-g");
        assertTrue(can("u1", keepNow, Item.CONFIGURE), "guard: the window on keep-g still confers after the reload");
    }

    /**
     * T-SEC-102 (S-39-02 (a), D-71c (3) as D-75 (2) upholds it; ARCHITECTURE 4 "Registration
     * re-checks ... that the approved item object is still at its name"): u1 requests a CONFIGURE
     * window on {@code hf/rr}, a job in a test folder whose deletion handling runs a hook after
     * every item listener has seen the deletion event and before the folder frees the name. The
     * administrator deletes {@code hf/rr}; the hook plays the approval's last step: a1 registers the
     * window for the checked {@code hf/rr} (the name still carries it, premise). The window has
     * ended with the reason "its item was deleted" (not active; its stored file reads back revoked
     * with that reason), exactly one GRANT_REVOKE record identifies it and says the item was
     * deleted, and a job the administrator then creates at {@code hf/rr} gets nothing. Guard first:
     * the same registration for a job still in place ({@code hf/stay}) confers Configure on it. The
     * grant pages are not read: the row plays only the registration, so the request stays PENDING in
     * the store and the window copy carries an id of its own, which no detail page resolves.
     */
    @Test
    public void t_sec_102_windowRegisteredAfterTheDeletionEventBeforeTheNameIsFreedEnds() throws Exception {
        HookFolder hf = j.jenkins.createProject(HookFolder.class, "hf");
        FreeStyleProject stay = hf.createProject(FreeStyleProject.class, "stay");
        registerAsApproval(request("u1", "hf/stay"), stay);
        assertTrue(can("u1", stay, Item.CONFIGURE), "guard: registering for an item still in place confers Configure on it");

        FreeStyleProject rr = hf.createProject(FreeStyleProject.class, "rr");
        Grant copy = windowCopy(request("u1", "hf/rr"));
        Set<String> revokesBefore = WindowStateFixtures.revokeRecordIds();
        AtomicBoolean ran = new AtomicBoolean();
        AtomicBoolean nameTaken = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HookFolder.afterListeners = item -> {
            if (item != rr) {
                return;
            }
            nameTaken.set(hf.getItem("rr") == rr);
            try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
                GrantService.get().register(copy, item);
                ran.set(true);
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            rr.delete();
        } finally {
            HookFolder.afterListeners = null;
        }
        if (failure.get() != null) {
            throw new AssertionError("the approval's registration during the deletion threw", failure.get());
        }
        assertTrue(ran.get(), "premise: the registration ran inside the deletion, after every item listener");
        assertTrue(nameTaken.get(), "premise: when it ran, the name hf/rr still carried the deleted job");
        assertNull(hf.getItem("rr"), "premise: hf/rr is deleted");

        assertNull(WindowStateFixtures.active(copy.getId()),
                "S-39-02 (a), D-71c (3): a window registered for an item whose deletion was already announced must not be active");
        Path stored = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + copy.getId() + ".xml");
        assertTrue(Files.isRegularFile(stored), "ARCHITECTURE 4/5: the ended window is stored at " + stored);
        Grant ended = (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(stored, StandardCharsets.UTF_8));
        assertNotNull(ended.getRevokedAt(), "S-39-02 (a): the stored window must be ended (revokedAt, SPEC 3)");
        assertTrue(String.valueOf(ended.getRevokedReason()).toLowerCase(Locale.ROOT).contains(WindowStateFixtures.DELETED_REASON),
                "ARCHITECTURE 4: the window must have ended with the reason '" + WindowStateFixtures.DELETED_REASON + "', was: "
                        + ended.getRevokedReason());
        WindowStateFixtures.assertDeletionRevokeRecords(revokesBefore, new String[][] {{copy.getId(), "hf/rr"}},
                "S-39-02 (a): the window registered during the deletion");
        FreeStyleProject again;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates a job at the freed name
            again = hf.createProject(FreeStyleProject.class, "rr");
        }
        assertFalse(can("u1", again, Item.CONFIGURE), "S-39-02 (a): the job created at hf/rr gets nothing from the window");
    }

    /**
     * T-SEC-104 (D-75 (2), ARCHITECTURE 4 "a window that cannot follow its item for certain (its
     * grant file cannot be written with the new name ...) ends with a GRANT_REVOKE record"; tightens
     * T-SEC-93): u1's CONFIGURE windows on the jobs {@code fol-a} and {@code fol-keep}. The grants
     * directory and the {@code fol-a} window's file refuse writes while the administrator renames
     * {@code fol-a} to {@code fol-b} (HTTP, 3xx): at once the window is not active and u1 holds no
     * Configure on {@code fol-b}. After write access is restored there is exactly one new
     * GRANT_REVOKE record, it identifies the window and names the failed follow (D-63: the record
     * names the reason; its wording is not pinned, only the word "follow"); after the
     * periodic work the window has ended (no Active list row, its page opens) and the Ended list
     * shows it with the reason "it could not follow its item". Guard: the window on {@code fol-keep}
     * is active and confers. Skipped where this process can write despite the read-only bits.
     */
    @Test
    public void t_sec_104_windowThatCannotFollowItsItemEndsWithARevokeRecord() throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("fol-a");
        FreeStyleProject keep = j.createFreeStyleProject("fol-keep");
        Grant window = approve(request("u1", "fol-a"));
        Grant guard = approve(request("u1", "fol-keep"));
        assertTrue(can("u1", a, Item.CONFIGURE), "premise: the window confers Configure on fol-a");
        Set<String> revokesBefore = WindowStateFixtures.revokeRecordIds();

        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        File dirFile = dir.toFile();
        File storedFile = dir.resolve(window.getId() + ".xml").toFile();
        assertTrue(storedFile.isFile(), "premise (ARCHITECTURE 5): the window is stored at " + storedFile);
        try {
            assertTrue(storedFile.setWritable(false, false), "fixture: the stored window made read-only");
            assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
            Assumptions.assumeTrue(writesRefused(dir) && !Files.isWritable(storedFile.toPath()),
                    "the file system does not refuse writes to read-only files for this process");
            assertRedirect(rename("admin", a, "fol-b"), "the administrator renames fol-a to fol-b while the grant store refuses writes");
            assertEquals("fol-b", a.getFullName(), "premise: fol-a is now fol-b");
            assertNull(WindowStateFixtures.active(window.getId()), "D-75 (2): a window that could not follow its item ends at once");
            assertFalse(can("u1", a, Item.CONFIGURE), "D-75 (2): the ended window confers nothing on fol-b");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }

        List<ChangeRecord> added = WindowStateFixtures.revokeRecordsSince(revokesBefore);
        assertEquals(1, added.size(), "D-75 (2): exactly one GRANT_REVOKE record for the window that could not follow, got "
                + WindowStateFixtures.describe(added));
        ChangeRecord rec = added.get(0);
        assertTrue(WindowStateFixtures.identifies(rec, window.getId(), "fol-a") || WindowStateFixtures.identifies(rec, window.getId(), "fol-b"),
                "D-75 (2): the GRANT_REVOKE record must identify the window: " + WindowStateFixtures.describe(added));
        assertTrue(String.valueOf(rec.getDetail()).toLowerCase(Locale.ROOT).contains("follow"),
                "D-63: the GRANT_REVOKE record must name the reason (the window could not follow its item): " + WindowStateFixtures.describe(added));

        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        WindowStateFixtures.assertEnded(j, "u1", window.getId(), "D-75 (2): the window after write access returned and the periodic work ran");
        HtmlPage list = UsabilityFixtures.htmlPage(j, "u1", "batch-control/grants/");
        List<DomElement> rows = new ArrayList<>(WindowStateFixtures.endedRowsNaming(list, "fol-a"));
        rows.addAll(WindowStateFixtures.endedRowsNaming(list, "fol-b"));
        assertTrue(rows.stream().anyMatch(r -> r.asNormalizedText().toLowerCase(Locale.ROOT).contains("it could not follow its item")),
                "D-63, ARCHITECTURE 4: the Ended list must show the window with the reason 'it could not follow its item'; rows naming it: "
                        + rows.stream().map(r -> ApproverFormFixtures.excerpt(r.asNormalizedText())).collect(Collectors.toList()));
        WindowStateFixtures.assertActiveOn(j, "u1", guard.getId(), "fol-keep", "guard: the untouched window on fol-keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on fol-keep still confers");
    }

    // ---------------------------------------------------------------- helpers

    /** What {@link #failedFollowOfACreatedJob} leaves: u1's CREATE window on fo, u1's job (now fo/k) and the job now at fo/j. */
    private record FailedFollow(Grant window, FreeStyleProject created, Item nowJ) {
    }

    /**
     * The scenario T-SEC-100 and T-SEC-105 share, up to the periodic work: u1's CREATE window on
     * {@code fo}; u1 creates {@code fo/j} through it (HTTP); {@code fo/m} is created by the
     * administrator over HTTP {@code createItem} ({@code mBySystem} false) or by a script as SYSTEM
     * while the window is active ({@code mBySystem} true); guard rename of {@code fo/g1} to
     * {@code fo/g2} with writes succeeding; then, while the grants directory and the window's file
     * refuse writes, the administrator renames {@code fo/j} to {@code fo/k}; write access is
     * restored; the administrator renames {@code fo/m} to {@code fo/j}; the periodic work runs.
     * Asserts what both rows expect alike: u1 holds no Configure on the job now at {@code fo/j} and
     * keeps it on {@code fo/k}, the window stays active, and the stored {@code createdItems} lists
     * {@code fo/k} and not {@code fo/j}. Aborts (assumption) where the process can write despite the
     * read-only bits.
     */
    private FailedFollow failedFollowOfACreatedJob(boolean mBySystem) throws Exception {
        Folder fo = j.jenkins.createProject(Folder.class, "fo");
        Grant window = approve(request("u1", "fo", GrantAction.CREATE));
        assertTrue(createJob("u1", fo, "j") < 400, "fixture: u1 creates fo/j through the CREATE window on fo");
        FreeStyleProject created = j.jenkins.getItemByFullName("fo/j", FreeStyleProject.class);
        assertNotNull(created, "fixture: fo/j exists");
        assertTrue(can("u1", created, Item.CONFIGURE), "premise (D-35c): u1 configures the job it created through the window");
        FreeStyleProject m;
        if (mBySystem) {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: a script (SYSTEM, not an HTTP request) creates fo/m
                m = fo.createProject(FreeStyleProject.class, "m");
            }
        } else {
            assertTrue(createJob("admin", fo, "m") < 400, "fixture: the administrator creates fo/m over HTTP createItem");
            m = j.jenkins.getItemByFullName("fo/m", FreeStyleProject.class);
            assertNotNull(m, "fixture: fo/m exists");
        }
        assertFalse(can("u1", m, Item.CONFIGURE), "premise: fo/m, which u1 did not create, gets nothing");

        assertTrue(createJob("u1", fo, "g1") < 400, "guard fixture: u1 creates fo/g1 through the CREATE window");
        boolean g1Changed = listed(WindowStateFixtures.storedGrant(j, window.getId()), "changedItems").contains(">fo/g1<");
        assertRedirect(rename("admin", j.jenkins.getItemByFullName("fo/g1"), "g2"), "guard: the administrator renames fo/g1 to fo/g2 (writes succeed)");
        String afterGuard = WindowStateFixtures.storedGrant(j, window.getId());
        assertTrue(listed(afterGuard, "createdItems").contains(">fo/g2<") && !listed(afterGuard, "createdItems").contains(">fo/g1<"),
                "guard (ARCHITECTURE 5): with writes succeeding createdItems follows fo/g1 to fo/g2: " + afterGuard.replaceAll("\\s+", " "));
        if (g1Changed) {
            assertTrue(listed(afterGuard, "changedItems").contains(">fo/g2<") && !listed(afterGuard, "changedItems").contains(">fo/g1<"),
                    "guard (ARCHITECTURE 5): with writes succeeding changedItems follows fo/g1 to fo/g2: " + afterGuard.replaceAll("\\s+", " "));
        }

        String before = WindowStateFixtures.storedGrant(j, window.getId());
        String beforeFlat = before.replaceAll("\\s+", " ");
        assertTrue(listed(before, "createdItems").contains(">fo/j<"), "premise (ARCHITECTURE 5): the stored window lists fo/j in createdItems: "
                + beforeFlat);
        assertTrue(listed(before, "changedItems").contains(">fo/j<"), "premise (D-58a (1), ARCHITECTURE 5): fo/j, created by u1 through the"
                + " window, is listed in changedItems: " + beforeFlat);
        if (mBySystem) {
            assertTrue(listed(before, "changedItems").contains(">fo/m<"), "premise (D-58a (2)/(5)): fo/m, created as SYSTEM inside the guarded"
                    + " folder, is listed in changedItems: " + beforeFlat);
        } else {
            assertFalse(listed(before, "changedItems").contains(">fo/m<"), "premise (D-58a (2)): fo/m, created by the administrator over HTTP,"
                    + " is not listed in changedItems: " + beforeFlat);
        }

        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        File dirFile = dir.toFile();
        File storedFile = dir.resolve(window.getId() + ".xml").toFile();
        assertTrue(storedFile.isFile(), "premise (ARCHITECTURE 5): the window is stored at " + storedFile);
        try {
            assertTrue(storedFile.setWritable(false, false), "fixture: the stored window made read-only");
            assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
            Assumptions.assumeTrue(writesRefused(dir) && !Files.isWritable(storedFile.toPath()),
                    "the file system does not refuse writes to read-only files for this process");
            assertRedirect(rename("admin", created, "k"), "the administrator renames fo/j to fo/k while the grant store refuses writes");
            assertEquals("fo/k", created.getFullName(), "premise: fo/j is now fo/k");
            assertTrue(can("u1", created, Item.CONFIGURE),
                    "ARCHITECTURE 4: the created-item record follows to fo/k in memory although its write failed");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }
        assertRedirect(rename("admin", m, "j"), "the administrator renames fo/m to fo/j after write access was restored");
        Item nowJ = j.jenkins.getItemByFullName("fo/j");
        assertNotNull(nowJ, "premise: fo/m is now fo/j");

        assertFalse(can("u1", nowJ, Item.CONFIGURE),
                "S-39-02 (b), D-75 (2): the job renamed into fo/j must get nothing from u1's created-item record");
        assertTrue(can("u1", created, Item.CONFIGURE), "D-35c: u1 keeps Configure on fo/k, the job it created");
        assertNotNull(WindowStateFixtures.active(window.getId()), "guard: the CREATE window on fo is still active");
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun(); // a documented retry point (ARCHITECTURE 4)
        String stored = WindowStateFixtures.storedGrant(j, window.getId());
        String flat = stored.replaceAll("\\s+", " ");
        String createdList = listed(stored, "createdItems");
        assertTrue(createdList.contains(">fo/k<"), "ARCHITECTURE 4/5: after the retried write the stored window's createdItems lists fo/k: "
                + flat);
        assertFalse(createdList.contains(">fo/j<"), "D-75 (2): the stored window's createdItems must not list fo/j any more: " + flat);
        return new FailedFollow(window, created, nowJ);
    }

    /** Ends every window of the row: the plugin clock moves past their 30-minute lifetime (no sleep). */
    private static void afterWindows() {
        BatchClock.setForTest(Clock.fixed(Instant.now().plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
    }

    /**
     * A script (SYSTEM, not an HTTP request) adds an authorization property giving carol
     * Item/Configure to {@code job}: a widening D-58a (2) reverts on an item that is guarded and
     * leaves alone on any other. True if the entry was kept, in memory and in the stored
     * {@code config.xml} (the two must agree).
     */
    private static boolean scriptWideningKept(FreeStyleProject job) throws Exception {
        Map<Permission, Set<PermissionEntry>> entries = new HashMap<>();
        entries.put(Item.CONFIGURE, new HashSet<>(Set.of(PermissionEntry.user("carol"))));
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            job.addProperty(new AuthorizationMatrixProperty(entries, new InheritParentStrategy()));
        } catch (IOException | RuntimeException refused) {
            // the guard may refuse the save outright; the result is read below either way
        }
        boolean inMemory = job.getAllProperties().stream().filter(p -> p instanceof AuthorizationMatrixProperty)
                .map(p -> (AuthorizationMatrixProperty) p)
                .anyMatch(p -> p.getGrantedPermissionEntries().values().stream()
                        .anyMatch(s -> s.stream().anyMatch(pe -> "carol".equals(pe.getSid()))));
        boolean onDisk = job.getConfigFile().asString().contains(":carol</permission>");
        assertEquals(inMemory, onDisk, "the widening of " + job.getFullName() + " must be kept or reverted alike in memory (" + inMemory
                + ") and in config.xml (" + onDisk + ")");
        return inMemory;
    }

    private static Set<String> violationIds() {
        return violations().stream().map(ChangeRecord::getId).collect(Collectors.toSet());
    }

    /** GRANT_VIOLATION records stored since {@code before} whose target is {@code fullName} or whose detail names it. */
    private static List<ChangeRecord> violationsNaming(Set<String> before, String fullName) {
        return violations().stream().filter(r -> !before.contains(r.getId()))
                .filter(r -> fullName.equals(r.getTarget()) || String.valueOf(r.getDetail()).contains(fullName))
                .collect(Collectors.toList());
    }

    private static String describeViolationsSince(Set<String> before) {
        return WindowStateFixtures.describe(violations().stream().filter(r -> !before.contains(r.getId())).collect(Collectors.toList()));
    }

    /** GRANT_VIOLATION records of the plugin clock's month and of the real month (the plugin clock may have moved). */
    private static List<ChangeRecord> violations() {
        return ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION, Instant.now());
    }

    /** u1's PENDING CONFIGURE request on {@code fullName}, designating a1. */
    private static GrantRequest request(String user, String fullName) {
        return request(user, fullName, GrantAction.CONFIGURE);
    }

    /** {@code user}'s PENDING request for {@code action} on {@code fullName}, designating a1. */
    private static GrantRequest request(String user, String fullName, GrantAction action) {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return GrantRequestService.get().create(new GrantScope(GrantScope.Type.ITEM, fullName),
                    List.of(action), 30, "maintenance of " + fullName, "a1");
        }
    }

    private static Grant approve(GrantRequest request) {
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            Grant grant = GrantRequestService.get().approve(request.getId(), "ok");
            assertNotNull(grant, "fixture: the approval must open a window");
            return grant;
        }
    }

    /**
     * The approval's last step played by the test: as a1, a window for {@code request} that was never
     * registered is registered for {@code checked}, the item object the approval checked. The
     * {@code Grant} constructor is not public, so the window is read with core's XStream from a copy
     * of a window file the plugin wrote (ARCHITECTURE section 5, {@code grants/<id>.xml}): the
     * template window on {@link #TEMPLATE_JOB} with its id, request id and item name replaced.
     */
    private void registerAsApproval(GrantRequest request, Item checked) throws Exception {
        Grant grant = windowCopy(request);
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            GrantService.get().register(grant, checked);
        }
    }

    /** The never-registered window for {@code request}, read from a copy of the template window's file (see {@link #registerAsApproval}). */
    private Grant windowCopy(GrantRequest request) throws Exception {
        if (template == null) {
            j.createFreeStyleProject(TEMPLATE_JOB);
            templateRequest = request("u1", TEMPLATE_JOB);
            template = approve(templateRequest);
        }
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + template.getId() + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the template window is stored at " + file);
        String xml = Files.readString(file, StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        String fullName = request.getScope().getFullName();
        assertTrue(xml.contains(template.getId()) && xml.contains(templateRequest.getId()) && xml.contains(">" + TEMPLATE_JOB + "<"),
                "premise: the template window file names its id, its request and its item: " + ApproverFormFixtures.excerpt(xml));
        xml = xml.replace(template.getId(), id).replace(templateRequest.getId(), request.getId())
                .replace(">" + TEMPLATE_JOB + "<", ">" + fullName + "<");
        Grant grant = (Grant) Jenkins.XSTREAM2.fromXML(xml);
        assertEquals(id, grant.getId(), "premise: the copy is a new window");
        assertEquals("u1", grant.getUser(), "premise: the copy is u1's window");
        assertEquals(fullName, grant.getScope().getFullName(), "premise: the copy names " + fullName);
        return grant;
    }

    /** {@code POST <folder>/createItem?name=<name>} with a minimal Freestyle config.xml as {@code user}; returns the HTTP status. */
    private int createJob(String user, Folder folder, String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        java.net.URL url = new java.net.URL(wc.createCrumbedUrl(folder.getUrl() + "createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(ItemScopeTest.MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** The XML between {@code <name>} and {@code </name>} in a stored grant file (ARCHITECTURE 5 field), or "" when absent. */
    private static String listed(String xml, String name) {
        int start = xml.indexOf("<" + name + ">");
        int end = xml.indexOf("</" + name + ">");
        return start < 0 || end < start ? "" : xml.substring(start, end);
    }

    private static void deleteTree(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "premise: the item directory " + root + " exists");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /**
     * A folder whose handling of a child's deletion runs {@link #afterListeners} after every item
     * listener has seen the deletion event and before the child's name is freed; otherwise it does
     * what the folders plugin's own handling does (fire the event, remove the child, update the
     * views). Only T-SEC-102 installs its descriptor.
     */
    public static class HookFolder extends Folder {

        static volatile Consumer<TopLevelItem> afterListeners;

        public HookFolder(ItemGroup parent, String name) {
            super(parent, name);
        }

        @Override
        public void onDeleted(TopLevelItem item) throws IOException {
            ItemListener.fireOnDeleted(item);
            Consumer<TopLevelItem> hook = afterListeners;
            if (hook != null) {
                hook.accept(item);
            }
            items.remove(item.getName());
            for (View view : getViews()) {
                view.onJobRenamed(item, item.getName(), null);
            }
        }

        @TestExtension("t_sec_102_windowRegisteredAfterTheDeletionEventBeforeTheNameIsFreedEnds")
        public static class DescriptorImpl extends Folder.DescriptorImpl {
            @Override
            public TopLevelItem newInstance(ItemGroup parent, String name) {
                return new HookFolder(parent, name);
            }

            @Override
            public String getDisplayName() {
                return "Hook folder (T-SEC-102)";
            }
        }
    }

    private WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
    }

    private static void assertRedirect(WebResponse response, String what) {
        int code = response.getStatusCode();
        assertTrue(code >= 300 && code < 400, what + " must go through (redirect), got HTTP " + code + ": "
                + ApproverFormFixtures.excerpt(response.getContentAsString()));
    }

    private static boolean writesRefused(Path dir) {
        Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
        try {
            Files.createFile(probe);
            Files.delete(probe);
            return false;
        } catch (java.io.IOException expected) {
            return true;
        }
    }

    private static boolean can(String user, Item item, hudson.security.Permission permission) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), permission);
    }
}
