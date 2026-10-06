package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertActiveOn;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertDeletionRevokeRecords;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertEnded;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertEndedByDeletion;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.revokeRecordIds;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.revokeRecordsSince;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SPEC item 8 line 170 (D-74, which replaces the D-71a/D-71b identity binding and its unbound
 * state): "a window applies to its item, not to a name: when an administrator or a user with their
 * own permissions renames or moves the item (no window allows a rename, D-71c), the window follows
 * it -- windows on the items below a renamed or moved folder follow too -- and deleting the item ends
 * the window, as do creating a new item at the window's name and starting Jenkins after the item
 * vanished; so renaming, moving, swapping or re-creating items never makes a window reach an item
 * nobody approved." ARCHITECTURE 4 ("Following the item"): deleting an item ends (revokes, reason
 * "its item was deleted") the windows naming it or anything below it; D-35c created-item records
 * follow renames and moves too.
 *
 * <p>Matrix rows T-08-148 .. T-08-150, T-08-152, T-08-153, T-08-155 and T-08-164 (written for D-71b
 * in notes 264 and 266, converted for D-74 in note 270: the intent of each row is kept, the expected
 * outcome follows the decision) and T-08-187 (note 270, "creating a new item at the window's name
 * ends the window") and T-08-188 (note 270, every item kind follows). The startup row T-08-151
 * and the restart of a followed window T-08-189 are in
 * {@link ItemScopeRestartTest}; the rename and move rows of the security-34 layout (T-08-131,
 * T-08-146) are in {@link ItemIdentityBindingTest}.
 *
 * <p>Each event row measures what the window confers (the item's own ACL and an HTTP config.xml
 * save, never a name-based service query), whether the window is active and on which item, or has
 * ended ({@link WindowStateFixtures}: the service and the grants page's Active and Ended lists; the
 * "No longer applies" display is not asserted either way), and for a deletion the GRANT_REVOKE
 * records and their reason. Each row keeps an untouched window next to the event as its guard.
 *
 * <p>Users: u1 (Overall/Read, Item/Read, RequestGrant), a1 (the designated approver), admin
 * (Overall/Administer, who performs every event, through HTTP where core offers an endpoint).
 * Windows are requested through the form contract and approved by a1. Change control on, Batch
 * Control matrix strategy.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-63/D-71c/D-74, docs/ARCHITECTURE.md sections 4
 * and 5 and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class ItemBindingEventsTest {

    private JenkinsRule j;
    private FreeStyleProject keep;

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

        keep = j.createFreeStyleProject("keep");
        keep.setDescription("base");
    }

    /**
     * T-08-148 (D-74 "deleting the item ends the window"; ARCHITECTURE 4 "deleting an item ends
     * (revokes, reason 'its item was deleted') the windows naming it or anything below it"; converted
     * from the D-71b unbinding, note 270): u1 holds CONFIGURE windows on the folder {@code fold}, the
     * job {@code fold/x}, the nested job {@code fold/sub/y}, the top-level job {@code fold-sibling}
     * (whose name starts with {@code fold} but is not below it) and the job {@code keep}. The
     * administrator deletes the folder {@code fold} (HTTP {@code doDelete}). The three windows on
     * {@code fold} and below have ended: not active, no Active list row, an Ended list row naming the
     * item with the reason "its item was deleted", and exactly one GRANT_REVOKE record each giving
     * that reason. After the administrator re-creates {@code fold}, {@code fold/x}, {@code fold/sub}
     * and {@code fold/sub/y} the windows are still ended and confer nothing (no Configure on any; u1's
     * save of {@code fold/x} 403, "base" kept). Guards: before the deletion all five windows
     * conferred; afterwards the windows on {@code fold-sibling} and {@code keep} are active on their
     * items and still confer (save of {@code fold-sibling} 200), and no GRANT_REVOKE record names
     * them.
     */
    @Test
    public void t_08_148_deletingAFolderEndsWindowsOnItAndBelowIt() throws Exception {
        Folder fold = j.jenkins.createProject(Folder.class, "fold");
        FreeStyleProject x = fold.createProject(FreeStyleProject.class, "x");
        Folder sub = fold.createProject(Folder.class, "sub");
        FreeStyleProject y = sub.createProject(FreeStyleProject.class, "y");
        FreeStyleProject sibling = j.createFreeStyleProject("fold-sibling");
        sibling.setDescription("base");
        String onFold = openWindow("fold");
        String onX = openWindow("fold/x");
        String onY = openWindow("fold/sub/y");
        String onSibling = openWindow("fold-sibling");
        String onKeep = openWindow("keep");
        for (Item item : new Item[] {fold, x, y, sibling, keep}) {
            assertTrue(can("u1", item, Item.CONFIGURE), "guard: before the deletion the window on " + item.getFullName() + " confers");
        }
        Set<String> revokesBefore = revokeRecordIds();

        assertSuccess(ApproverFormFixtures.post(j, "admin", fold.getUrl() + "doDelete", List.of()),
                "fixture: the administrator deletes the folder fold");
        assertNull(j.jenkins.getItemByFullName("fold"), "premise: the folder fold is gone");

        String[][] ended = {{onFold, "fold"}, {onX, "fold/x"}, {onY, "fold/sub/y"}};
        for (String[] w : ended) {
            assertEndedByDeletion(j, "u1", w[0], w[1], "D-74: the window on " + w[1] + " after its folder fold was deleted");
        }
        assertDeletionRevokeRecords(revokesBefore, ended, "D-74: deleting the folder fold");

        Folder fold2;
        FreeStyleProject x2;
        Folder sub2;
        FreeStyleProject y2;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator re-creates the names
            fold2 = j.jenkins.createProject(Folder.class, "fold");
            x2 = fold2.createProject(FreeStyleProject.class, "x");
            x2.setDescription("base");
            sub2 = fold2.createProject(Folder.class, "sub");
            y2 = sub2.createProject(FreeStyleProject.class, "y");
        }
        for (Item item : new Item[] {fold2, x2, sub2, y2}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "D-74: no window may reach the re-created " + item.getFullName());
        }
        assertEquals(403, postConfigXml("u1", x2, "planted"), "u1's save of the re-created fold/x must be refused");
        assertEquals("base", reload(x2).getDescription());
        for (String[] w : ended) {
            assertEnded(j, "u1", w[0], "D-74: the window on " + w[1] + " stays ended after the name was re-created");
        }

        assertActiveOn(j, "u1", onSibling, "fold-sibling", "guard: the untouched window on fold-sibling (a name prefix, not below fold)");
        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
        assertTrue(can("u1", sibling, Item.CONFIGURE), "guard: deleting fold does not end fold-sibling's window (not below fold)");
        assertEquals(200, postConfigXml("u1", sibling, "changed-sibling"), "guard: the window on fold-sibling still confers");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
        assertEquals(ended.length, revokeRecordsSince(revokesBefore).size(),
                "guard: no further GRANT_REVOKE record (the re-creation and the guards end nothing): "
                        + WindowStateFixtures.describe(revokeRecordsSince(revokesBefore)));
    }

    /**
     * T-08-149 (D-74 "the window follows it"; converted from D-71b "renaming back does not restore
     * the window", note 270; the intent kept: an item taking the old name never gets the window):
     * u1's CONFIGURE window on the job {@code ren}. The administrator renames it to {@code ren-tmp}
     * (core's {@code confirmRename}): the window follows (active on {@code ren-tmp}, the Active list
     * names it), u1 holds Configure and EXTENDED_READ on the renamed job and saves it (200). The
     * administrator creates a new job {@code ren}: u1 holds nothing on it (save 403, "base" kept) and
     * the window stays on {@code ren-tmp}; the administrator deletes that new {@code ren} (a name
     * that is a prefix of the window's name): the window on {@code ren-tmp} stays active and no
     * GRANT_REVOKE record is written. The administrator renames {@code ren-tmp} back to {@code ren}:
     * the window follows again and confers; a new job created at {@code ren-tmp} gets nothing.
     * Guards: before the renames the window conferred; the window on {@code keep} stays active and
     * confers.
     */
    @Test
    public void t_08_149_windowFollowsARenameAndARenameBack() throws Exception {
        FreeStyleProject ren = j.createFreeStyleProject("ren");
        ren.setDescription("base");
        String onRen = openWindow("ren");
        String onKeep = openWindow("keep");
        assertTrue(can("u1", ren, Item.CONFIGURE), "guard: before the renames the window confers Configure on ren");

        assertSuccess(rename("admin", ren, "ren-tmp"), "fixture: the administrator renames ren to ren-tmp");
        assertEquals("ren-tmp", ren.getFullName(), "premise: the job was renamed");
        assertActiveOn(j, "u1", onRen, "ren-tmp", "D-74: the window follows its job to ren-tmp");
        assertTrue(can("u1", ren, Item.CONFIGURE), "D-74: the window still confers Configure on the renamed job");
        assertTrue(can("u1", ren, Item.EXTENDED_READ), "and its EXTENDED_READ");
        assertEquals(200, postConfigXml("u1", ren, "saved-as-ren-tmp"), "u1 saves the renamed job through the window");

        FreeStyleProject atOldName = systemJob("ren");
        assertFalse(can("u1", atOldName, Item.CONFIGURE), "D-74: the new job that took the old name ren gets nothing");
        assertEquals(403, postConfigXml("u1", atOldName, "planted"), "u1's save of the new ren must be refused");
        assertEquals("base", reload(atOldName).getDescription());
        assertActiveOn(j, "u1", onRen, "ren-tmp", "D-74: creating an item at the old name does not move the window back");

        Set<String> revokesBefore = revokeRecordIds();
        assertSuccess(ApproverFormFixtures.post(j, "admin", atOldName.getUrl() + "doDelete", List.of()),
                "fixture: the administrator deletes the new job ren");
        assertActiveOn(j, "u1", onRen, "ren-tmp", "guard: deleting ren (a prefix of ren-tmp) does not end the window on ren-tmp");
        assertTrue(revokeRecordsSince(revokesBefore).isEmpty(), "guard: deleting ren ends no window: "
                + WindowStateFixtures.describe(revokeRecordsSince(revokesBefore)));

        assertSuccess(rename("admin", ren, "ren"), "fixture: the administrator renames ren-tmp back to ren");
        assertSame(ren, j.jenkins.getItemByFullName("ren"), "premise: the job approved for carries the name ren again");
        assertActiveOn(j, "u1", onRen, "ren", "D-74: the window follows the rename back");
        assertTrue(can("u1", ren, Item.CONFIGURE), "D-74: the window confers Configure on ren again");
        assertEquals(200, postConfigXml("u1", ren, "saved-as-ren"), "u1 saves ren through the window");
        FreeStyleProject atTmp = systemJob("ren-tmp");
        assertFalse(can("u1", atTmp, Item.CONFIGURE), "D-74: a new job at ren-tmp gets nothing");

        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-150 (D-74 "deleting the item ends the window ... so re-creating items never makes a window
     * reach an item nobody approved"; converted from the D-71b unbinding, note 270): u1's CONFIGURE
     * windows on the jobs {@code made} and {@code copied}. The administrator deletes both (HTTP
     * {@code doDelete}): both windows have ended with the reason "its item was deleted" (one
     * GRANT_REVOKE record each). The administrator then creates a Freestyle job {@code made} through
     * {@code createItem} (mode) and copies the job {@code src} to {@code copied} ({@code createItem},
     * {@code mode=copy}): u1 holds no Configure on either new job, both saves are 403 ("base" kept),
     * and both windows are still ended although a live item of the same kind carries their name.
     * Guards: before the deletions both windows conferred; the window on {@code keep} stays active and
     * confers.
     */
    @Test
    public void t_08_150_itemCreatedOrCopiedUnderAnEndedWindowsNameGetsNothing() throws Exception {
        FreeStyleProject made = j.createFreeStyleProject("made");
        FreeStyleProject copied = j.createFreeStyleProject("copied");
        FreeStyleProject src = j.createFreeStyleProject("src");
        src.setDescription("base");
        String onMade = openWindow("made");
        String onCopied = openWindow("copied");
        String onKeep = openWindow("keep");
        assertTrue(can("u1", made, Item.CONFIGURE), "guard: before the deletion the window on made confers");
        assertTrue(can("u1", copied, Item.CONFIGURE), "guard: before the deletion the window on copied confers");
        Set<String> revokesBefore = revokeRecordIds();

        assertSuccess(ApproverFormFixtures.post(j, "admin", made.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes made");
        assertSuccess(ApproverFormFixtures.post(j, "admin", copied.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes copied");
        assertEndedByDeletion(j, "u1", onMade, "made", "D-74: the window on made after its job was deleted");
        assertEndedByDeletion(j, "u1", onCopied, "copied", "D-74: the window on copied after its job was deleted");
        assertDeletionRevokeRecords(revokesBefore, new String[][] {{onMade, "made"}, {onCopied, "copied"}}, "D-74: deleting made and copied");

        assertSuccess(ApproverFormFixtures.post(j, "admin", "createItem", List.of(
                new NameValuePair("name", "made"), new NameValuePair("mode", FreeStyleProject.class.getName()))),
                "fixture: the administrator creates a new job made");
        assertSuccess(ApproverFormFixtures.post(j, "admin", "createItem", List.of(
                new NameValuePair("name", "copied"), new NameValuePair("mode", "copy"), new NameValuePair("from", "src"))),
                "fixture: the administrator copies src to copied");
        FreeStyleProject newMade = j.jenkins.getItemByFullName("made", FreeStyleProject.class);
        FreeStyleProject newCopied = j.jenkins.getItemByFullName("copied", FreeStyleProject.class);
        assertNotNull(newMade, "premise: a Freestyle job carries the name made again");
        assertNotNull(newCopied, "premise: a Freestyle job carries the name copied again");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the description the saves would change
            newMade.setDescription("base");
        }
        assertEquals("base", newCopied.getDescription(), "premise: the copy carries the source's description");

        for (FreeStyleProject item : new FreeStyleProject[] {newMade, newCopied}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "D-74: no window may reach the new " + item.getFullName());
            assertEquals(403, postConfigXml("u1", item, "planted"), "u1's save of the new " + item.getFullName() + " must be refused");
            assertEquals("base", reload(item).getDescription());
        }
        assertEnded(j, "u1", onMade, "D-74: the window on made stays ended after a job was created at its name");
        assertEnded(j, "u1", onCopied, "D-74: the window on copied stays ended after a job was copied to its name");

        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-152 (D-74 "the window follows it -- windows on the items below a renamed or moved folder
     * follow too"; folders plugin Item/Move; converted from D-71b "a move ends the window even when
     * moved back", note 270): u1's CONFIGURE windows on the job {@code ops/mv}, the folder
     * {@code ops/inner}, the job {@code ops/inner/j} and the job {@code keep}. The administrator moves
     * {@code ops/mv} into the folder {@code dest} ({@code move/move}): the window follows (active on
     * {@code dest/mv}), u1 configures and saves it (200); a new job created at {@code ops/mv} gets
     * nothing (save 403). After that job is removed, the administrator moves the job back into
     * {@code ops}: the window follows again and confers on {@code ops/mv}. The administrator moves the
     * folder {@code ops/inner} into {@code dest}: the windows on the folder and on the job inside it
     * follow (active on {@code dest/inner} and {@code dest/inner/j}) and confer there; a folder and
     * a job created at the old names {@code ops/inner} and {@code ops/inner/j} get nothing. Guards:
     * before the moves every window conferred; the window on {@code keep} stays active and confers.
     */
    @Test
    public void t_08_152_windowsFollowAMoveOfTheirItemAndOfItsFolder() throws Exception {
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        Folder dest = j.jenkins.createProject(Folder.class, "dest");
        FreeStyleProject mv = ops.createProject(FreeStyleProject.class, "mv");
        mv.setDescription("base");
        Folder inner = ops.createProject(Folder.class, "inner");
        inner.setDescription("base");
        FreeStyleProject innerJob = inner.createProject(FreeStyleProject.class, "j");
        innerJob.setDescription("base");
        String onMv = openWindow("ops/mv");
        String onInner = openWindow("ops/inner");
        String onInnerJob = openWindow("ops/inner/j");
        String onKeep = openWindow("keep");
        for (Item item : new Item[] {mv, inner, innerJob}) {
            assertTrue(can("u1", item, Item.CONFIGURE), "guard: before the moves the window on " + item.getFullName() + " confers");
        }

        assertSuccess(move("admin", mv, dest), "fixture: the administrator moves ops/mv into dest");
        assertEquals("dest/mv", mv.getFullName(), "premise: the job was moved");
        assertActiveOn(j, "u1", onMv, "dest/mv", "D-74: the window follows its job to dest/mv");
        assertTrue(can("u1", mv, Item.CONFIGURE), "D-74: the window confers Configure on dest/mv");
        assertEquals(200, postConfigXml("u1", mv, "saved-in-dest"), "u1 saves dest/mv through the window");
        FreeStyleProject atOldName;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates a job at the old name
            atOldName = ops.createProject(FreeStyleProject.class, "mv");
            atOldName.setDescription("base");
        }
        assertFalse(can("u1", atOldName, Item.CONFIGURE), "D-74: the new job at ops/mv gets nothing");
        assertEquals(403, postConfigXml("u1", atOldName, "planted"), "u1's save of the new ops/mv must be refused");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator frees the name again
            atOldName.delete();
        }

        assertSuccess(move("admin", mv, ops), "fixture: the administrator moves it back into ops");
        assertSame(mv, j.jenkins.getItemByFullName("ops/mv"), "premise: the job approved for carries the name ops/mv again");
        assertActiveOn(j, "u1", onMv, "ops/mv", "D-74: the window follows the move back");
        assertTrue(can("u1", mv, Item.CONFIGURE), "D-74: the window confers Configure on ops/mv again");

        assertSuccess(move("admin", inner, dest), "fixture: the administrator moves the folder ops/inner into dest");
        assertEquals("dest/inner/j", innerJob.getFullName(), "premise: the job inside moved with its folder");
        assertActiveOn(j, "u1", onInner, "dest/inner", "D-74: the window on the moved folder follows it");
        assertActiveOn(j, "u1", onInnerJob, "dest/inner/j", "D-74: the window on the job below the moved folder follows too");
        assertTrue(can("u1", inner, Item.CONFIGURE), "D-74: the window confers Configure on dest/inner");
        assertTrue(can("u1", innerJob, Item.CONFIGURE), "D-74: the window confers Configure on dest/inner/j");
        assertEquals(200, postConfigXml("u1", innerJob, "saved-below-moved-folder"), "u1 saves dest/inner/j through the window");
        Folder innerAgain;
        FreeStyleProject innerJobAgain;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator re-creates the old names
            innerAgain = ops.createProject(Folder.class, "inner");
            innerJobAgain = innerAgain.createProject(FreeStyleProject.class, "j");
        }
        assertFalse(can("u1", innerAgain, Item.CONFIGURE), "D-74: a folder created at ops/inner gets nothing");
        assertFalse(can("u1", innerJobAgain, Item.CONFIGURE), "D-74: a job created at ops/inner/j gets nothing");

        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-153 (SPEC 8 line 157 "a Manage holder can revoke an active window at once, and it is
     * recorded"; line 173 "A permission window can be revoked from its own detail page as well as
     * from the list"; converted from "an unbound window can still be revoked", note 270: under D-74
     * a window either applies to its item or has ended). Part 1: u1's CONFIGURE window on the job
     * {@code moved} follows the administrator's rename to {@code moved-new}; its Active list row
     * offers a revoke control and its detail page exactly one, posting to
     * {@code batch-control/grants/<id>/revoke}. a1 (the approver, no Manage) is refused with 403
     * there and the window stays active; the administrator's POST revokes it: not active, no Active
     * list row, one GRANT_REVOKE record by the administrator, and u1 holds no Configure on
     * {@code moved-new}. Part 2: u1's window on the job {@code gone} ends when the administrator
     * deletes the job; its detail page still opens and offers no revoke control; a1's POST to its
     * revoke URL is refused with 403; the administrator's POST does not fail with a server error,
     * adds no second GRANT_REVOKE record and leaves the window ended.
     */
    @Test
    public void t_08_153_followedWindowIsRevokedFromItsPageAndAnEndedOneIsNotRevokedTwice() throws Exception {
        FreeStyleProject moved = j.createFreeStyleProject("moved");
        String onMoved = openWindow("moved");
        assertSuccess(rename("admin", moved, "moved-new"), "fixture: the administrator renames moved to moved-new");
        assertActiveOn(j, "admin", onMoved, "moved-new", "premise: the window followed its job");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        org.htmlunit.html.DomElement row = WindowStateFixtures.activeRow(j, list, onMoved);
        assertNotNull(row, "premise: the followed window is listed");
        assertFalse(WindowStateFixtures.revokeControls(list, row).isEmpty(), "the followed window's Active list row must offer Revoke: "
                + ApproverFormFixtures.excerpt(row.asXml()));
        HtmlPage detail = WindowStateFixtures.detailPage(j, "admin", onMoved);
        String revokePath = j.getURL().getPath() + "batch-control/grants/" + onMoved + "/revoke";
        assertEquals(List.of(revokePath), WindowStateFixtures.revokeControls(detail, detail.getDocumentElement()).stream().distinct().toList(),
                "the followed window's detail page must offer exactly one revoke control, posting to grants/<id>/revoke");

        Set<String> revokesBefore = revokeRecordIds();
        String relative = "batch-control/grants/" + onMoved + "/revoke";
        assertEquals(403, ApproverFormFixtures.post(j, "a1", relative, List.of()).getStatusCode(),
                "a1 (no Manage) must be refused with 403 by the revoke endpoint");
        assertActiveOn(j, "admin", onMoved, "moved-new", "guard: a1's refused revoke leaves the window active");
        assertTrue(revokeRecordsSince(revokesBefore).isEmpty(), "guard: a refused revoke records nothing");

        assertSuccess(ApproverFormFixtures.post(j, "admin", relative, List.of()), "the administrator's revoke of the followed window");
        assertEnded(j, "admin", onMoved, "the revoked window");
        List<ChangeRecord> revokes = revokeRecordsSince(revokesBefore);
        assertEquals(1, revokes.size(), "the revoke must leave one GRANT_REVOKE record, got " + WindowStateFixtures.describe(revokes));
        assertEquals("admin", revokes.get(0).getUser(), "the GRANT_REVOKE record names the revoker");
        assertFalse(can("u1", moved, Item.CONFIGURE), "after the revocation u1 holds no Configure on moved-new");

        FreeStyleProject gone = j.createFreeStyleProject("gone");
        String onGone = openWindow("gone");
        assertSuccess(ApproverFormFixtures.post(j, "admin", gone.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes gone");
        assertEndedByDeletion(j, "admin", onGone, "gone", "premise: the window on gone after its job was deleted");
        HtmlPage endedDetail = WindowStateFixtures.detailPage(j, "admin", onGone);
        assertTrue(WindowStateFixtures.revokeControls(endedDetail, WindowStateFixtures.mainPanel(endedDetail)).isEmpty(),
                "an ended window's detail page must offer no revoke control (SPEC 6: only controls the user can use)");
        Set<String> afterDeletion = revokeRecordIds();
        String goneRevoke = "batch-control/grants/" + onGone + "/revoke";
        assertEquals(403, ApproverFormFixtures.post(j, "a1", goneRevoke, List.of()).getStatusCode(),
                "a1 (no Manage) must be refused with 403 by the revoke endpoint of the ended window");
        WebResponse again = ApproverFormFixtures.post(j, "admin", goneRevoke, List.of());
        assertTrue(again.getStatusCode() < 500, "revoking an ended window must not fail with a server error, got HTTP "
                + again.getStatusCode() + ": " + ApproverFormFixtures.excerpt(again.getContentAsString()));
        assertTrue(revokeRecordsSince(afterDeletion).isEmpty(), "an ended window is not revoked a second time: "
                + WindowStateFixtures.describe(revokeRecordsSince(afterDeletion)));
        assertEnded(j, "admin", onGone, "the window on gone stays ended");
    }

    /**
     * T-08-155 (D-35c, SPEC 8 line 168 "the D-35c Configure covers the items the holder created
     * through the window whose parent is that folder"; D-74 "re-creating items never makes a window
     * reach an item nobody approved", ARCHITECTURE 4 "D-35c created-item records" follow renames;
     * converted from D-71b "creating ... drops stale D-35c records under that name", note 270): u1
     * holds a CREATE window on the folder {@code team} and creates the job {@code team/mine} through
     * it (u1 configures it, D-35c). The administrator deletes {@code team/mine} and creates a new job
     * {@code team/mine} (HTTP {@code createItem}): u1 holds no Configure on it and its save is 403
     * ("base" kept). The window still lets u1 create {@code team/mine2}, which u1 configures; after
     * the administrator renames it to {@code team/mine3} u1 still configures it (the created-item
     * record follows the rename) and saves it (200), and a new job created at {@code team/mine2} gets
     * no creator Configure.
     */
    @Test
    public void t_08_155_itemCreatedByOthersUnderACreatedItemsNameGetsNoCreatorConfigure() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        openWindow("team", "CREATE");
        assertTrue(createJob("u1", team, "mine") < 400, "fixture: u1 creates team/mine through the CREATE window");
        FreeStyleProject mine = j.jenkins.getItemByFullName("team/mine", FreeStyleProject.class);
        assertNotNull(mine, "fixture: team/mine exists");
        assertTrue(can("u1", mine, Item.CONFIGURE), "guard: D-35c, u1 configures the job it created");

        assertSuccess(ApproverFormFixtures.post(j, "admin", mine.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes team/mine");
        assertTrue(createJob("admin", team, "mine") < 400, "fixture: the administrator creates a new team/mine");
        FreeStyleProject byAdmin = j.jenkins.getItemByFullName("team/mine", FreeStyleProject.class);
        assertNotNull(byAdmin, "premise: a job carries the name team/mine again");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the description the save would change
            byAdmin.setDescription("base");
        }

        assertFalse(can("u1", byAdmin, Item.CONFIGURE), "D-74: u1's creator Configure must not reach the administrator's new team/mine");
        assertEquals(403, postConfigXml("u1", byAdmin, "planted"), "u1's save of the administrator's team/mine must be refused");
        assertEquals("base", reload(byAdmin).getDescription());

        assertTrue(createJob("u1", team, "mine2") < 400, "guard: the CREATE window still lets u1 create team/mine2");
        FreeStyleProject mine2 = j.jenkins.getItemByFullName("team/mine2", FreeStyleProject.class);
        assertNotNull(mine2);
        assertTrue(can("u1", mine2, Item.CONFIGURE), "guard: D-35c still applies to an item u1 created through the window");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the description the save would change
            mine2.setDescription("base");
        }

        assertSuccess(rename("admin", mine2, "mine3"), "fixture: the administrator renames team/mine2 to team/mine3");
        assertEquals("team/mine3", mine2.getFullName(), "premise: the created job was renamed inside team");
        assertTrue(can("u1", mine2, Item.CONFIGURE), "D-74: the created-item record follows the rename, u1 still configures team/mine3");
        assertEquals(200, postConfigXml("u1", mine2, "saved-as-mine3"), "u1 saves team/mine3 through its D-35c Configure");
        FreeStyleProject atOldName;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates a job at the old name
            atOldName = team.createProject(FreeStyleProject.class, "mine2");
        }
        assertFalse(can("u1", atOldName, Item.CONFIGURE), "D-74: a new job at team/mine2 gets no creator Configure");
    }

    /**
     * T-08-164 (security-36 S-36-03 (ii) intent, kept under D-74: a failed write never leaves a
     * window reaching an item nobody approved; converted from "a failed unbinding write is retried",
     * note 270, since D-74 removes the binding): u1's CONFIGURE windows on the jobs {@code gone} and
     * {@code keep}. The grants directory and the {@code gone} window's file are made read-only and
     * the administrator deletes {@code gone} (HTTP {@code doDelete}), so the window's end cannot be
     * written: the window is nonetheless not active. Write access is restored, the expiry periodic
     * work runs once ({@code ExpiryPeriodicWork.doRun()}) and the administrator creates a new job
     * {@code gone}: u1 holds no Configure on it (save 403, "base" kept), and the window has ended
     * (not active, no Active list row). Guard: the window on {@code keep} is active and confers. Skipped where this process can
     * write despite the read-only bits (root, Windows), because the write failure cannot be produced
     * there.
     */
    @Test
    public void t_08_164_failedWriteOfAWindowsEndNeverLeavesItConferring() throws Exception {
        FreeStyleProject gone = j.createFreeStyleProject("gone");
        gone.setDescription("base");
        String onGone = openWindow("gone");
        String onKeep = openWindow("keep");
        Path dir = j.jenkins.getRootDir().toPath().resolve("batch-control/grants");
        Path stored = dir.resolve(onGone + ".xml");
        assertTrue(Files.isRegularFile(stored), "premise (ARCHITECTURE 5): the window is stored at " + stored);
        File dirFile = dir.toFile();
        File storedFile = stored.toFile();
        try {
            assertTrue(storedFile.setWritable(false, false), "fixture: the stored grant made read-only");
            assertTrue(dirFile.setWritable(false, false), "fixture: the grants directory made read-only");
            boolean enforced;
            Path probe = dir.resolve("probe-" + System.nanoTime() + ".tmp");
            try {
                Files.createFile(probe);
                Files.delete(probe);
                enforced = false;
            } catch (IOException expected) {
                enforced = true;
            }
            assumeTrue(enforced && !Files.isWritable(stored), "the file system does not refuse writes to read-only files for this process");

            assertSuccess(ApproverFormFixtures.post(j, "admin", gone.getUrl() + "doDelete", List.of()), "the administrator deletes gone");
            assertNull(j.jenkins.getItemByFullName("gone"), "premise: gone is deleted");
            assertNull(WindowStateFixtures.active(onGone), "D-74: the window on gone is not active even though its end could not be written");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }
        assertTrue(Files.isWritable(stored), "fixture: write access restored");

        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        FreeStyleProject again = systemJob("gone");
        assertFalse(can("u1", again, Item.CONFIGURE), "D-74: the window must not reach the job re-created at gone");
        assertEquals(403, postConfigXml("u1", again, "planted"), "u1's save of the re-created gone must be refused");
        assertEquals("base", reload(again).getDescription());
        assertEnded(j, "u1", onGone, "D-74: the window on gone after write access returned, the periodic work ran and the name was re-created");
        assertActiveOn(j, "u1", onKeep, "keep", "guard: the window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-187 (SPEC 8 line 170 "deleting the item ends the window, as do creating a new item at the
     * window's name ..."; ARCHITECTURE 4 "creating an item at a window's name ends that window";
     * note 270): u1's CONFIGURE windows on the jobs {@code vanish} and {@code vanish2} and on
     * {@code keep}. The directories of {@code vanish} and {@code vanish2} are removed on disk and
     * the configuration is reloaded from disk ({@code Jenkins.reload()}, which fires no deletion
     * event), so no item carries either name. The administrator then creates a Freestyle job
     * {@code vanish} ({@code createItem}, mode) and copies {@code src} to {@code vanish2}
     * ({@code mode=copy}). Both windows have ended (not active, no Active list row), u1 holds no
     * Configure on either new job and both saves are 403 ("base" kept). Guard: the window on
     * {@code keep} (a new object after the reload) is active and confers. Whether the windows end at
     * the reload already or only at the creation is not pinned; the end state is.
     */
    @Test
    public void t_08_187_itemCreatedAtAWindowsNameEndsTheWindow() throws Exception {
        j.createFreeStyleProject("vanish");
        j.createFreeStyleProject("vanish2");
        FreeStyleProject src = j.createFreeStyleProject("src");
        src.setDescription("base");
        String onVanish = openWindow("vanish");
        String onVanish2 = openWindow("vanish2");
        String onKeep = openWindow("keep");
        j.jenkins.save(); // fixture: the security configuration must survive the reload from disk

        Path home = j.jenkins.getRootDir().toPath();
        for (String name : new String[] {"vanish", "vanish2"}) {
            deleteTree(home.resolve("jobs").resolve(name));
        }
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator reloads the configuration from disk
            j.jenkins.reload();
        }
        assertNull(j.jenkins.getItemByFullName("vanish"), "premise: vanish is gone after the reload");
        assertNull(j.jenkins.getItemByFullName("vanish2"), "premise: vanish2 is gone after the reload");

        assertSuccess(ApproverFormFixtures.post(j, "admin", "createItem", List.of(
                new NameValuePair("name", "vanish"), new NameValuePair("mode", FreeStyleProject.class.getName()))),
                "fixture: the administrator creates a new job vanish");
        assertSuccess(ApproverFormFixtures.post(j, "admin", "createItem", List.of(
                new NameValuePair("name", "vanish2"), new NameValuePair("mode", "copy"), new NameValuePair("from", "src"))),
                "fixture: the administrator copies src to vanish2");
        FreeStyleProject newVanish = j.jenkins.getItemByFullName("vanish", FreeStyleProject.class);
        FreeStyleProject newVanish2 = j.jenkins.getItemByFullName("vanish2", FreeStyleProject.class);
        assertNotNull(newVanish, "premise: a Freestyle job carries the name vanish again");
        assertNotNull(newVanish2, "premise: a Freestyle job carries the name vanish2 again");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the description the save would change
            newVanish.setDescription("base");
        }

        assertEnded(j, "u1", onVanish, "D-74: the window on vanish after a new job was created at its name");
        assertEnded(j, "u1", onVanish2, "D-74: the window on vanish2 after a job was copied to its name");
        for (FreeStyleProject item : new FreeStyleProject[] {newVanish, newVanish2}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "D-74: no window may reach the new " + item.getFullName());
            assertEquals(403, postConfigXml("u1", item, "planted"), "u1's save of the new " + item.getFullName() + " must be refused");
            assertEquals("base", reload(item).getDescription());
        }

        FreeStyleProject keepNow = j.jenkins.getItemByFullName("keep", FreeStyleProject.class);
        assertNotNull(keepNow, "premise: keep survived the reload");
        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
        assertTrue(can("u1", keepNow, Item.CONFIGURE), "guard: the window on keep still confers after the reload");
    }

    /**
     * T-08-188 (D-74 "the window follows it" for every item kind a window can name, D-71 "a job or a
     * folder of any kind"; coverage inventory section 2, column "window follows admin rename/move";
     * note 270): u1 holds CONFIGURE windows on a Pipeline {@code pipe}, a Folder {@code box}, a
     * Multibranch Pipeline {@code mb}, an Organization Folder {@code org} and a multi-configuration
     * project {@code mx}. The administrator renames each to {@code <name>-renamed} (core's
     * {@code confirmRename}). Each window is
     * active on the new name and confers Configure and EXTENDED_READ on the renamed item; an item of
     * the same kind the administrator then creates at the old name gets nothing, and the window stays
     * on its item. Guard: before the renames every window conferred; the window on {@code keep} stays
     * active. (Maven projects are covered by the same rule but not tested: no maven-plugin test
     * dependency, D-74.)
     */
    @Test
    public void t_08_188_windowFollowsARenameForEveryItemKind() throws Exception {
        List<Class<? extends hudson.model.TopLevelItem>> kinds = List.of(
                org.jenkinsci.plugins.workflow.job.WorkflowJob.class, Folder.class,
                org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject.class,
                jenkins.branch.OrganizationFolder.class, hudson.matrix.MatrixProject.class);
        String[] names = {"pipe", "box", "mb", "org", "mx"};
        String[] windows = new String[names.length];
        hudson.model.TopLevelItem[] items = new hudson.model.TopLevelItem[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = j.jenkins.createProject(kinds.get(i), names[i]);
            windows[i] = openWindow(names[i]);
            assertTrue(can("u1", items[i], Item.CONFIGURE), "guard: before the rename the window confers Configure on " + names[i]);
        }
        String onKeep = openWindow("keep");

        for (int i = 0; i < names.length; i++) {
            String renamed = names[i] + "-renamed";
            assertSuccess(rename("admin", items[i], renamed), "fixture: the administrator renames " + names[i] + " (confirmRename)");
            assertEquals(renamed, items[i].getFullName(), "premise: " + names[i] + " was renamed");
            String kind = kinds.get(i).getSimpleName();
            assertActiveOn(j, "u1", windows[i], renamed, "D-74: the window on the " + kind + " follows it to " + renamed);
            assertTrue(can("u1", items[i], Item.CONFIGURE), "D-74: the window confers Configure on the renamed " + kind);
            assertTrue(can("u1", items[i], Item.EXTENDED_READ), "D-74: and EXTENDED_READ on the renamed " + kind);
            hudson.model.TopLevelItem atOldName;
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) { // fixture: the administrator creates the same kind at the old name
                atOldName = j.jenkins.createProject(kinds.get(i), names[i]);
            }
            assertFalse(can("u1", atOldName, Item.CONFIGURE), "D-74: the new " + kind + " at the old name " + names[i] + " gets nothing");
            assertActiveOn(j, "u1", windows[i], renamed, "D-74: the window on the " + kind + " stays on its item");
        }
        assertActiveOn(j, "u1", onKeep, "keep", "guard: the untouched window on keep");
    }

    // ---------------------------------------------------------------- helpers

    /** Files a window on {@code fullName} through the form as u1 (CONFIGURE unless named); a1 approves it. Returns the window's id. */
    private String openWindow(String fullName, String... actions) throws Exception {
        List<String> requested = actions.length == 0 ? List.of("CONFIGURE") : Arrays.asList(actions);
        String id = submitGrantOk(j, "u1", fullName, requested, 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return WindowStateFixtures.windowId("u1", fullName);
    }

    /** A top-level Freestyle job {@code name} created by the administrator (SYSTEM fixture), description "base". */
    private FreeStyleProject systemJob(String name) throws Exception {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            FreeStyleProject job = j.jenkins.createProject(FreeStyleProject.class, name);
            job.setDescription("base");
            return job;
        }
    }

    /** {@code POST <item>/confirmRename} with {@code newName} (core's rename endpoint), redirects not followed. */
    private WebResponse rename(String user, Item item, String newName) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "confirmRename", List.of(new NameValuePair("newName", newName)));
    }

    /** {@code POST <item>/move/move} with {@code destination=/<folder>} (folders plugin). */
    private WebResponse move(String user, Item item, Folder destination) throws Exception {
        return ApproverFormFixtures.post(j, user, item.getUrl() + "move/move",
                List.of(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    /** {@code POST <folder>/createItem?name=<name>} with a minimal Freestyle config.xml; returns the HTTP status. */
    private int createJob(String user, Folder folder, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, user);
        java.net.URL url = new java.net.URL(wc.createCrumbedUrl(folder.getUrl() + "createItem").toExternalForm() + "&name=" + name);
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(ItemScopeTest.MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static void deleteTree(Path root) throws IOException {
        assertTrue(Files.isDirectory(root), "premise: the item directory " + root + " exists");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    static boolean can(String user, Item target, hudson.security.Permission p) {
        return target.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private AbstractItem reload(AbstractItem item) {
        AbstractItem now = j.jenkins.getItemByFullName(item.getFullName(), AbstractItem.class);
        assertNotNull(now, "fixture: " + item.getFullName() + " must exist");
        return now;
    }

    /** POSTs the item's config.xml with its description swapped; returns the HTTP status. */
    private int postConfigXml(String userId, AbstractItem target, String newDescription) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        String xml = target.getConfigFile().asString()
                .replace("<description>" + target.getDescription() + "</description>",
                        "<description>" + newDescription + "</description>");
        assertTrue(xml.contains(newDescription), "fixture: " + target.getFullName() + "'s config.xml must carry its description");
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(xml);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }
}
