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
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.DomElement;
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
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertShownBound;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertShownEnded;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.assertShownUnbound;
import static io.jenkins.plugins.batchcontrol.WindowStateFixtures.storedBinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SPEC item 8 line 169 (D-71a): "a window confers something only on the very item it was approved
 * for ... so renaming, moving, swapping or re-creating items never makes a window (or several
 * windows combined) reach a different item", as D-71b keeps it: "item events end bindings for good:
 * deleting an item unbinds the windows naming it or anything below it ...; a rename or move ends
 * the windows on the old name (renaming back does not restore them) ...; creating or copying an
 * item unbinds windows bound under its name and drops stale D-35c records under that name; at
 * startup, windows whose item no longer exists are unbound" (ARCHITECTURE 4, binding paragraph).
 * Matrix rows T-08-148 .. T-08-150, T-08-152, T-08-153 and T-08-155 (note 264) and T-08-164
 * (note 266: D-71c ruling 3, security-36 S-36-03, a failed unbinding write is retried); the startup row
 * T-08-151 is in {@link ItemScopeRestartTest}, the folder rename refusal's screens T-08-154 in
 * {@link ItemIdentityBindingTest}.
 *
 * <p>Each event row measures three results: what the window confers (the item's own ACL and an
 * HTTP config.xml save, never a name-based service query), how the screens show it
 * ({@link WindowStateFixtures}: the Active list and the detail page, unbound with the exact
 * documented text) and whether the stored grant still records the item's identity (ARCHITECTURE
 * 5: absent once an item event ends the binding). Each row keeps an untouched window next to the
 * event as its guard: shown bound, stored bound, still conferring.
 *
 * <p>Users: u1 (Overall/Read, Item/Read, RequestGrant), a1 (the designated approver), admin
 * (Overall/Administer, who performs every event, through HTTP where core offers an endpoint).
 * Windows are requested through the form contract and approved by a1. Change control on, Batch
 * Control matrix strategy.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-71a/D-71b, docs/ARCHITECTURE.md sections 4 and 5,
 * the ui-dev screen contract and docs/TEST-MATRIX.md only (no src/main knowledge).
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
     * T-08-148 (D-71b "deleting an item unbinds the windows naming it or anything below it"): u1
     * holds CONFIGURE windows on the folder {@code fold}, the job {@code fold/x}, the nested job
     * {@code fold/sub/y}, the top-level job {@code fold-sibling} (whose name starts with
     * {@code fold} but is not below it) and the job {@code keep}. The administrator deletes the
     * folder {@code fold} (HTTP {@code doDelete}). The three windows on {@code fold} and below are
     * shown unbound in the Active list and on their detail pages and no longer record an identity;
     * after the administrator re-creates {@code fold}, {@code fold/x} and {@code fold/sub/y} they
     * still show unbound and confer nothing (no Configure on any; u1's save of {@code fold/x} 403,
     * "base" kept). Guards: before the deletion all five windows conferred; afterwards the windows
     * on {@code fold-sibling} and {@code keep} are shown bound, stored bound and still confer
     * (save of {@code fold-sibling} 200).
     */
    @Test
    public void t_08_148_deletingAFolderUnbindsWindowsOnItAndBelowIt() throws Exception {
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
        assertShownBound(j, "u1", onX, "guard: the window on fold/x before the deletion");

        assertSuccess(ApproverFormFixtures.post(j, "admin", fold.getUrl() + "doDelete", List.of()),
                "fixture: the administrator deletes the folder fold");
        assertNull(j.jenkins.getItemByFullName("fold"), "premise: the folder fold is gone");

        String[][] unbound = {{onFold, "fold"}, {onX, "fold/x"}, {onY, "fold/sub/y"}};
        for (String[] w : unbound) {
            assertShownUnbound(j, "u1", w[0], "D-71b: the window on " + w[1] + " after its folder fold was deleted");
            assertFalse(storedBinding(j, w[0]), "D-71b: the stored window on " + w[1] + " must no longer record an item identity");
        }

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
            assertFalse(can("u1", item, Item.CONFIGURE), "D-71b: no window may reach the re-created " + item.getFullName());
        }
        assertEquals(403, postConfigXml("u1", x2, "planted"), "u1's save of the re-created fold/x must be refused");
        assertEquals("base", reload(x2).getDescription());
        for (String[] w : unbound) {
            assertShownUnbound(j, "u1", w[0], "D-71b: the window on " + w[1] + " after the name was re-created");
        }

        for (String[] w : new String[][] {{onSibling, "fold-sibling"}, {onKeep, "keep"}}) {
            assertShownBound(j, "u1", w[0], "guard: the untouched window on " + w[1]);
            assertTrue(storedBinding(j, w[0]), "guard: the untouched window on " + w[1] + " still records its item's identity");
        }
        assertTrue(can("u1", sibling, Item.CONFIGURE), "guard: deleting fold does not unbind fold-sibling (not below fold)");
        assertEquals(200, postConfigXml("u1", sibling, "changed-sibling"), "guard: the window on fold-sibling still confers");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-149 (D-71b "a rename or move ends the windows on the old name (renaming back does not
     * restore them)"): u1's CONFIGURE window on the job {@code ren}. The administrator renames it to
     * {@code ren-tmp} and back to {@code ren} (core's {@code confirmRename}); the job is the same
     * object in the same directory, so name and identity match again. u1 holds neither Configure
     * nor EXTENDED_READ on {@code ren} (nor held Configure on {@code ren-tmp}), its save is 403 and
     * "base" is kept; the window is shown unbound in the Active list and on its detail page and no
     * longer records an identity. Guards: before the renames the window conferred; the window on
     * {@code keep} is shown bound and still confers.
     */
    @Test
    public void t_08_149_renamingBackDoesNotRestoreTheWindow() throws Exception {
        FreeStyleProject ren = j.createFreeStyleProject("ren");
        ren.setDescription("base");
        String onRen = openWindow("ren");
        String onKeep = openWindow("keep");
        assertTrue(can("u1", ren, Item.CONFIGURE), "guard: before the renames the window confers Configure on ren");
        Object keyBefore = fileKey(ren);

        assertSuccess(rename("admin", ren, "ren-tmp"), "fixture: the administrator renames ren to ren-tmp");
        assertEquals("ren-tmp", ren.getFullName(), "premise: the job was renamed");
        assertFalse(can("u1", ren, Item.CONFIGURE), "the window does not follow the job to ren-tmp");
        assertSuccess(rename("admin", ren, "ren"), "fixture: the administrator renames it back to ren");
        assertSame(ren, j.jenkins.getItemByFullName("ren"), "premise: the very job approved for carries the name ren again");
        Object keyAfter = fileKey(ren);
        if (keyBefore != null && keyAfter != null) {
            assertEquals(keyBefore, keyAfter, "premise: the job is back in the same directory, so its identity matches again");
        }

        assertFalse(can("u1", ren, Item.CONFIGURE), "D-71b: renaming back must not restore the window");
        assertFalse(can("u1", ren, Item.EXTENDED_READ), "nor its EXTENDED_READ");
        assertEquals(403, postConfigXml("u1", ren, "planted"), "u1's save of ren must be refused after the rename back");
        assertEquals("base", reload(ren).getDescription());
        assertShownUnbound(j, "u1", onRen, "D-71b: the window on ren after a rename and a rename back");
        assertFalse(storedBinding(j, onRen), "D-71b: the stored window on ren must no longer record an item identity");

        assertShownBound(j, "u1", onKeep, "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-150 (D-71b "creating or copying an item unbinds windows bound under its name"; D-71a
     * "deletion and re-creation never re-points a window"): u1's CONFIGURE windows on the jobs
     * {@code made} and {@code copied}. The administrator deletes both (HTTP {@code doDelete}), then
     * creates a Freestyle job {@code made} through {@code createItem} (mode) and copies the job
     * {@code src} to {@code copied} ({@code createItem}, {@code mode=copy}). u1 holds no Configure
     * on either new job and both saves are 403 ("base" kept); both windows are shown unbound in the
     * Active list and on their detail pages, although a live item of the same kind carries their
     * name again. Guards: before the deletions both windows conferred; the window on {@code keep}
     * is shown bound and still confers.
     */
    @Test
    public void t_08_150_itemCreatedOrCopiedUnderTheNameGetsNothing() throws Exception {
        FreeStyleProject made = j.createFreeStyleProject("made");
        FreeStyleProject copied = j.createFreeStyleProject("copied");
        FreeStyleProject src = j.createFreeStyleProject("src");
        src.setDescription("base");
        String onMade = openWindow("made");
        String onCopied = openWindow("copied");
        String onKeep = openWindow("keep");
        assertTrue(can("u1", made, Item.CONFIGURE), "guard: before the deletion the window on made confers");
        assertTrue(can("u1", copied, Item.CONFIGURE), "guard: before the deletion the window on copied confers");

        assertSuccess(ApproverFormFixtures.post(j, "admin", made.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes made");
        assertSuccess(ApproverFormFixtures.post(j, "admin", copied.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes copied");
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
            assertFalse(can("u1", item, Item.CONFIGURE), "D-71b: no window may reach the new " + item.getFullName());
            assertEquals(403, postConfigXml("u1", item, "planted"), "u1's save of the new " + item.getFullName() + " must be refused");
            assertEquals("base", reload(item).getDescription());
        }
        assertShownUnbound(j, "u1", onMade, "D-71b: the window on made after the job was deleted and created again");
        assertShownUnbound(j, "u1", onCopied, "D-71b: the window on copied after the job was deleted and copied in again");

        assertShownBound(j, "u1", onKeep, "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-152 (D-71b "a rename or move ends the windows on the old name"; folders plugin
     * Item/Move): u1's CONFIGURE window on the job {@code ops/mv}. The administrator moves it into
     * the folder {@code dest} ({@code move/move}): u1 holds no Configure on {@code dest/mv} and the
     * window is shown unbound. The administrator moves it back into {@code ops}: it is the same
     * job in the same directory under the window's name again, yet u1 holds neither Configure nor
     * EXTENDED_READ on {@code ops/mv}, its save is 403 ("base" kept), the window is still shown
     * unbound in the Active list and on its detail page and no longer records an identity. Guards:
     * before the move the window conferred; the window on {@code keep} is shown bound and still
     * confers.
     */
    @Test
    public void t_08_152_movingAJobEndsItsWindowEvenWhenMovedBack() throws Exception {
        Folder ops = j.jenkins.createProject(Folder.class, "ops");
        Folder dest = j.jenkins.createProject(Folder.class, "dest");
        FreeStyleProject mv = ops.createProject(FreeStyleProject.class, "mv");
        mv.setDescription("base");
        String onMv = openWindow("ops/mv");
        String onKeep = openWindow("keep");
        assertTrue(can("u1", mv, Item.CONFIGURE), "guard: before the move the window confers Configure on ops/mv");
        Object keyBefore = fileKey(mv);

        assertSuccess(move("admin", mv, dest), "fixture: the administrator moves ops/mv into dest");
        assertEquals("dest/mv", mv.getFullName(), "premise: the job was moved");
        assertFalse(can("u1", mv, Item.CONFIGURE), "D-71a: the window does not follow the job to dest/mv");
        assertShownUnbound(j, "u1", onMv, "D-71b: the window on ops/mv after its job was moved away");

        assertSuccess(move("admin", mv, ops), "fixture: the administrator moves it back into ops");
        assertSame(mv, j.jenkins.getItemByFullName("ops/mv"), "premise: the very job approved for carries the name ops/mv again");
        Object keyAfter = fileKey(mv);
        if (keyBefore != null && keyAfter != null) {
            assertEquals(keyBefore, keyAfter, "premise: the job is back in the same directory, so its identity matches again");
        }

        assertFalse(can("u1", mv, Item.CONFIGURE), "D-71b: moving back must not restore the window");
        assertFalse(can("u1", mv, Item.EXTENDED_READ), "nor its EXTENDED_READ");
        assertEquals(403, postConfigXml("u1", mv, "planted"), "u1's save of ops/mv must be refused after the move back");
        assertEquals("base", reload(mv).getDescription());
        assertShownUnbound(j, "u1", onMv, "D-71b: the window on ops/mv after a move and a move back");
        assertFalse(storedBinding(j, onMv), "D-71b: the stored window on ops/mv must no longer record an item identity");

        assertShownBound(j, "u1", onKeep, "guard: the untouched window on keep");
        assertTrue(can("u1", keep, Item.CONFIGURE), "guard: the window on keep still confers");
    }

    /**
     * T-08-153 (SPEC 8 line 157 "a Manage holder can revoke an active window at once, and it is
     * recorded"; line 172 "A permission window can be revoked from its own detail page as well as
     * from the list"; ui-dev: Revoke stays offered for unbound windows): u1's CONFIGURE window on
     * the job {@code gone}; the administrator deletes the job, so the window is shown unbound. Its
     * Active list row still offers a revoke control, and its detail page offers exactly one, which
     * posts to {@code batch-control/grants/<id>/revoke}. a1 (the approver, no Manage) is refused
     * with 403 there and the window stays listed (unbound). The administrator's POST succeeds: the
     * window is no longer listed among the active windows, its detail page carries no window state
     * marker (an ended window), and one GRANT_REVOKE record by the administrator is added.
     */
    @Test
    public void t_08_153_unboundWindowCanStillBeRevoked() throws Exception {
        FreeStyleProject gone = j.createFreeStyleProject("gone");
        String onGone = openWindow("gone");
        assertSuccess(ApproverFormFixtures.post(j, "admin", gone.getUrl() + "doDelete", List.of()), "fixture: the administrator deletes gone");
        assertShownUnbound(j, "admin", onGone, "premise: the window on gone after its job was deleted");

        HtmlPage list = UsabilityFixtures.htmlPage(j, "admin", "batch-control/grants/");
        DomElement row = WindowStateFixtures.activeRow(j, list, onGone);
        assertNotNull(row, "premise: the unbound window is listed");
        assertFalse(WindowStateFixtures.revokeControls(list, row).isEmpty(), "the unbound window's Active list row must still offer Revoke: "
                + ApproverFormFixtures.excerpt(row.asXml()));
        HtmlPage detail = WindowStateFixtures.detailPage(j, "admin", onGone);
        List<String> detailRevoke = WindowStateFixtures.revokeControls(detail, detail.getDocumentElement());
        String revokePath = j.getURL().getPath() + "batch-control/grants/" + onGone + "/revoke";
        assertEquals(List.of(revokePath), detailRevoke.stream().distinct().toList(),
                "the unbound window's detail page must offer exactly one revoke control, posting to grants/<id>/revoke");

        int revokesBefore = records(ChangeType.GRANT_REVOKE).size();
        String relative = "batch-control/grants/" + onGone + "/revoke";
        assertEquals(403, ApproverFormFixtures.post(j, "a1", relative, List.of()).getStatusCode(),
                "a1 (no Manage) must be refused with 403 by the revoke endpoint");
        assertShownUnbound(j, "admin", onGone, "guard: a1's refused revoke leaves the window listed");
        assertEquals(revokesBefore, records(ChangeType.GRANT_REVOKE).size(), "guard: a refused revoke records nothing");

        assertSuccess(ApproverFormFixtures.post(j, "admin", relative, List.of()), "the administrator's revoke of the unbound window");
        assertTrue(GrantService.get().listActive().stream().noneMatch(g -> onGone.equals(g.getId())),
                "the revoked window must not be active any more");
        assertShownEnded(j, "admin", onGone, "the revoked window");
        List<ChangeRecord> revokes = records(ChangeType.GRANT_REVOKE);
        assertEquals(revokesBefore + 1, revokes.size(), "the revoke must leave one GRANT_REVOKE record, got " + revokes);
        assertEquals("admin", revokes.get(revokes.size() - 1).getUser(), "the GRANT_REVOKE record names the revoker");
    }

    /**
     * T-08-155 (D-71b "creating or copying an item ... drops stale D-35c records under that name";
     * SPEC 8 line 168 "the D-35c Configure covers the items the holder created through the window
     * whose parent is that folder"): u1 holds a CREATE window on the folder {@code team} and creates
     * the job {@code team/mine} through it (u1 configures it, D-35c). The administrator deletes
     * {@code team/mine} and creates a new job {@code team/mine} (HTTP {@code createItem}): u1 holds
     * no Configure on it and its save is 403 ("base" kept). Guards: before the deletion u1
     * configured the job it created; afterwards the window still works for a new creation: u1
     * creates {@code team/mine2} and configures it.
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

        assertFalse(can("u1", byAdmin, Item.CONFIGURE), "D-71b: u1's creator Configure must not reach the administrator's new team/mine");
        assertEquals(403, postConfigXml("u1", byAdmin, "planted"), "u1's save of the administrator's team/mine must be refused");
        assertEquals("base", reload(byAdmin).getDescription());

        assertTrue(createJob("u1", team, "mine2") < 400, "guard: the CREATE window still lets u1 create team/mine2");
        FreeStyleProject mine2 = j.jenkins.getItemByFullName("team/mine2", FreeStyleProject.class);
        assertNotNull(mine2);
        assertTrue(can("u1", mine2, Item.CONFIGURE), "guard: D-35c still applies to an item u1 created through the window");
    }

    /**
     * T-08-164 (security-36 S-36-03 (ii); D-71c ruling 3 "a failed write of an unbinding is
     * retried"): u1's CONFIGURE windows on the jobs {@code gone} and {@code keep} are stored bound
     * (premise). The grants directory and the {@code gone} window's file are made read-only and the
     * administrator deletes {@code gone} (HTTP {@code doDelete}), so the unbinding cannot be written:
     * the stored grant still records the identity (premise that the write failed). Write access is
     * restored; the stored grant is unchanged until the expiry periodic work runs once
     * ({@code ExpiryPeriodicWork.doRun()}, matrix note 2). Then it records no identity, and the window
     * is still active (an unbound window stays until it ends or is revoked, D-71b). Guard: the window
     * on {@code keep} is still stored bound. Skipped where this process can write despite the
     * read-only bits (root, Windows), because the write failure cannot be produced there.
     */
    @Test
    public void t_08_164_failedUnbindingWriteIsRetriedByThePeriodicWork() throws Exception {
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
            assertTrue(storedBinding(j, onGone), "premise: the unbinding could not be written, the stored grant still records the identity");
        } finally {
            dirFile.setWritable(true);
            storedFile.setWritable(true);
        }
        assertTrue(Files.isWritable(stored), "fixture: write access restored");
        assertTrue(storedBinding(j, onGone), "premise: nothing has rewritten the stored grant before the periodic work runs");

        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();

        assertFalse(storedBinding(j, onGone), "D-71c (3): the periodic work retries the failed unbinding, the stored grant records no identity");
        assertTrue(GrantService.get().listActive().stream().anyMatch(g -> onGone.equals(g.getId())),
                "the window stays active (unbound) after the retry");
        assertTrue(storedBinding(j, onKeep), "guard: the window on keep is still stored bound");
    }

    // ---------------------------------------------------------------- helpers

    /** Files a window on {@code fullName} through the form as u1 (CONFIGURE unless named); a1 approves it. Returns the window's id. */
    private String openWindow(String fullName, String... actions) throws Exception {
        List<String> requested = actions.length == 0 ? List.of("CONFIGURE") : Arrays.asList(actions);
        String id = submitGrantOk(j, "u1", fullName, requested, 30, "maintenance of " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        String windowId = WindowStateFixtures.windowId("u1", fullName);
        assertTrue(storedBinding(j, windowId), "premise (ARCHITECTURE 5): the approved window on " + fullName + " records its item's identity");
        return windowId;
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

    /** The file system's key of the item's directory, or null where the platform has none. */
    private static Object fileKey(AbstractItem item) throws Exception {
        return Files.readAttributes(item.getRootDir().toPath(), BasicFileAttributes.class).fileKey();
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
