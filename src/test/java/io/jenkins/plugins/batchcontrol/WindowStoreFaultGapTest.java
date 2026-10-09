package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.ops.ExpiryPeriodicWork;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.excerpt;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-02 and L3-03: windows whose grant file cannot be read (chmod 000) or has
 * vanished (deleted while Jenkins runs) when their item is deleted, re-created, renamed or reviewed, or
 * when change control is turned off. Matrix rows T-GAP-305 .. T-GAP-313 (note 279).
 *
 * <p>Basis: LIMITATIONS 11 ("Deleting the item ends its windows ... each is revoked ... with the reason
 * 'its item was deleted' and a GRANT_REVOKE record"; "Ending a window is built to survive a failed write
 * ... marked ended in memory first, so it stops applying at once"; "A grant file that cannot be written is
 * retried before every later grant write, on every item event and by the periodic work"; the D-35c
 * created-item record "is dropped when the item is deleted or another item takes its name"; "A window
 * therefore either applies to its item, under whatever name the item has now, or has ended"); LIMITATIONS
 * 34 (turning change control off revokes every open window, one GRANT_REVOKE record each); D-63 (the
 * switch-off reason); D-35c; D-58a (1) and (4) ("Coverage and the 'changed under a grant' state follow
 * renames and moves", "An administrator can see which items are in the 'changed under a grant' state");
 * D-58b (3) (the review writes a GUARD_REVIEWED record); ARCHITECTURE 5 ({@code grants/<id>.xml};
 * {@code changedItems} follows renames); SPEC 8 line 170; SPEC 6 usability (no error page).
 *
 * <p>Every case of L3-02 runs twice, first without the fault (the guard: the same action gives the same
 * result) and then with the window's grant file made unreadable and unwritable immediately before the
 * action ({@code chmod 000}, skipped where this process can still read it); the windows are listed once
 * ({@code GrantService.get().listActive()}) before any fault. The strategy monitor's list is read from
 * Manage Jenkins ({@code div[data-monitor-id=batch-control-strategy]}: one list item per item, the
 * item's full name in {@code code}).
 *
 * <p>Batch Control matrix strategy, change control on; u1 .. u5 hold Overall/Read, Item/Read and
 * RequestGrant; a1 approves.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35c, D-58a, D-58b, D-63, D-74 and D-75,
 * docs/LIMITATIONS.md and docs/ARCHITECTURE.md only (no src/main knowledge).
 */
@WithJenkins
public class WindowStoreFaultGapTest {

    static final String DELETED_REASON = "its item was deleted";

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "u3", "u4", "u5", "a1"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "u2", "u3", "u4", "u5"}) {
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.configureBuildAuthenticator();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    // ------------------------------------------------------------------ L3-02 (unreadable grant file)

    /**
     * T-GAP-305 (L3-02 W1; LIMITATIONS 11): u1's CONFIGURE window on job {@code w1-<case>}; the
     * administrator deletes the job: the window is not active at once and one GRANT_REVOKE record names it;
     * the administrator creates a new job at that name, on which u1 holds no Configure; after the fault is
     * removed and the periodic work has run, the stored window says it was revoked. Guard first without the
     * fault, then with the window's grant file unreadable.
     */
    @Test
    public void t_gap_305_deletionEndsAWindowWhoseFileCannotBeRead() throws Exception {
        for (boolean fault : new boolean[] {false, true}) {
            String name = fault ? "w1-fault" : "w1-guard";
            j.createFreeStyleProject(name);
            String window = openWindow("u1", name, "CONFIGURE");
            GrantService.get().listActive();
            Set<String> before = WindowStateFixtures.revokeRecordIds();
            Path file = grantFile(window);
            withFault(fault, file, () -> {
                asAdmin(() -> j.jenkins.getItemByFullName(name).delete());
                assertNull(WindowStateFixtures.active(window), what(fault) + ": LIMITATIONS 11: the window stops applying at once");
                List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before);
                assertEquals(1, revokes.stream().filter(r -> WindowStateFixtures.identifies(r, window, name)).count(),
                        what(fault) + ": one GRANT_REVOKE record names the window: " + WindowStateFixtures.describe(revokes));
                asAdmin(() -> j.jenkins.createProject(FreeStyleProject.class, name));
                assertFalse(can("u1", j.jenkins.getItemByFullName(name), Item.CONFIGURE),
                        what(fault) + ": SPEC 8 line 170: the new job at the name gets nothing");
            });
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            assertNotNull(stored(window).getRevokedAt(), what(fault) + ": after the fault is removed the stored window says it was revoked");
        }
    }

    /**
     * T-GAP-306 (L3-02 W2; LIMITATIONS 11 created-item record, D-35c): u2's CREATE window on folder
     * {@code f2-<case>}; u2 creates {@code f2-<case>/x} through it (premise: u2 holds Configure on it);
     * the administrator marks it reviewed on the monitor; the administrator deletes it and creates a new
     * {@code f2-<case>/x}: u2 holds no Configure on the new one, and the CREATE window is still active.
     * Guard first without the fault, then with the window's grant file unreadable during the deletion and
     * the re-creation.
     */
    @Test
    public void t_gap_306_recreatedChildGetsNothingWhileTheCreateWindowFileCannotBeRead() throws Exception {
        for (boolean fault : new boolean[] {false, true}) {
            String folder = fault ? "f2-fault" : "f2-guard";
            j.jenkins.createProject(Folder.class, folder);
            String window = openWindow("u2", folder, "CREATE");
            createAs("u2", folder, "x");
            Item created = j.jenkins.getItemByFullName(folder + "/x");
            assertNotNull(created, what(fault) + ": fixture: u2 created " + folder + "/x");
            assertTrue(can("u2", created, Item.CONFIGURE), what(fault) + ": premise (D-35c): u2 configures the item created through the window");
            int review = markReviewed(folder + "/x");
            assertTrue(review < 400, what(fault) + ": fixture: the administrator marks " + folder + "/x reviewed, got " + review);
            GrantService.get().listActive();
            withFault(fault, grantFile(window), () -> {
                asAdmin(() -> {
                    j.jenkins.getItemByFullName(folder + "/x").delete();
                    ((Folder) j.jenkins.getItemByFullName(folder)).createProject(FreeStyleProject.class, "x");
                });
                assertFalse(can("u2", j.jenkins.getItemByFullName(folder + "/x"), Item.CONFIGURE),
                        what(fault) + ": LIMITATIONS 11: the created-item record is dropped, the new " + folder + "/x gives u2 nothing");
                assertNotNull(WindowStateFixtures.active(window), what(fault) + ": the CREATE window on the folder is still active");
            });
        }
    }

    /**
     * T-GAP-307 (L3-02 W3; D-58a (1) and (4), ARCHITECTURE 5 "changedItems follows renames and moves",
     * LIMITATIONS 11 retry): u3's CONFIGURE window on job {@code d3-<case>}; u3 saves it through
     * {@code config.xml} (no authorization change), so the monitor lists it as changed under a grant
     * (premise); the administrator renames it to {@code d3-<case>-2}: the monitor lists the new name and
     * not the old one; after the fault is removed and the periodic work has run, the stored window's
     * {@code changedItems} names the new name and not the old one. Guard first without the fault.
     */
    @Test
    public void t_gap_307_changedStateFollowsARenameWhileTheWindowFileCannotBeRead() throws Exception {
        for (boolean fault : new boolean[] {false, true}) {
            String name = fault ? "d3-fault" : "d3-guard";
            String renamed = name + "-2";
            FreeStyleProject job = j.createFreeStyleProject(name);
            String window = openWindow("u3", name, "CONFIGURE");
            saveAs("u3", job, "edited by u3");
            assertTrue(monitorItems().contains(name), what(fault) + ": premise (D-58a (4)): the monitor lists " + name + ": " + monitorItems());
            GrantService.get().listActive();
            withFault(fault, grantFile(window), () -> {
                asAdmin(() -> ((FreeStyleProject) j.jenkins.getItemByFullName(name)).renameTo(renamed));
                List<String> listed = monitorItems();
                assertTrue(listed.contains(renamed) && !listed.contains(name),
                        what(fault) + ": D-58a (1): the monitor lists " + renamed + " and not " + name + ": " + listed);
            });
            ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
            String changed = listed(Files.readString(grantFile(window), StandardCharsets.UTF_8), "changedItems");
            assertTrue(changed.contains(">" + renamed + "<") && !changed.contains(">" + name + "<"),
                    what(fault) + ": ARCHITECTURE 5: the stored changedItems follows to " + renamed + ": " + changed);
        }
    }

    /**
     * T-GAP-308 (L3-02 W4; LIMITATIONS 34, D-63): u4's CONFIGURE window W4 on job {@code e4} and u5's W5
     * on job {@code e5}; W4's grant file is made unreadable; the administrator turns change control off on
     * the Batch Control configuration page. Both windows are revoked; after the fault is removed and the
     * periodic work has run, W4's stored revocation reason equals W5's (the same switch without the fault,
     * the guard) and is not empty; exactly one GRANT_REVOKE record names W4, and one names W5.
     */
    @Test
    public void t_gap_308_switchOffRevokesAWindowWhoseFileCannotBeReadWithTheSameReason() throws Exception {
        j.createFreeStyleProject("e4");
        j.createFreeStyleProject("e5");
        String w4 = openWindow("u4", "e4", "CONFIGURE");
        String w5 = openWindow("u5", "e5", "CONFIGURE");
        GrantService.get().listActive();
        Set<String> before = WindowStateFixtures.revokeRecordIds();
        withFault(true, grantFile(w4), () -> {
            Page saved = switchChangeControlOff("admin");
            assertTrue(saved.getWebResponse().getStatusCode() < 400, "the administrator's save turning change control off succeeds");
            assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "premise: change control is off");
            assertNull(WindowStateFixtures.active(w4), "LIMITATIONS 34: W4 is revoked");
            assertNull(WindowStateFixtures.active(w5), "LIMITATIONS 34: W5 is revoked");
        });
        ExtensionList.lookupSingleton(ExpiryPeriodicWork.class).doRun();
        Grant stored4 = stored(w4);
        Grant stored5 = stored(w5);
        assertNotNull(stored5.getRevokedReason(), "guard (D-63): W5's stored reason names the switch");
        assertEquals(stored5.getRevokedReason(), stored4.getRevokedReason(), "D-63: W4's stored reason equals W5's");
        assertNotNull(stored4.getRevokedAt(), "W4 is stored as revoked");
        List<ChangeRecord> revokes = WindowStateFixtures.revokeRecordsSince(before);
        assertEquals(1, revokes.stream().filter(r -> WindowStateFixtures.identifies(r, w4, "e4")).count(),
                "LIMITATIONS 34: exactly one GRANT_REVOKE record for W4: " + WindowStateFixtures.describe(revokes));
        assertEquals(1, revokes.stream().filter(r -> WindowStateFixtures.identifies(r, w5, "e5")).count(),
                "guard: exactly one GRANT_REVOKE record for W5: " + WindowStateFixtures.describe(revokes));
    }

    // ------------------------------------------------------------------ L3-03 (vanished grant file)

    /**
     * T-GAP-309 (L3-03 W1; SPEC 8 line 170, LIMITATIONS 11 "A window therefore either applies to its item,
     * under whatever name the item has now, or has ended"): u1's window on job {@code v1}; its grant file
     * is deleted while Jenkins runs (premise first: the window confers); the administrator renames
     * {@code v1} to {@code v1-2} (the rename succeeds) and creates a new {@code v1}. u1 holds no Configure
     * on the new {@code v1}; and either the window is active on {@code v1-2} and confers there, or it is not
     * active and confers on neither.
     */
    @Test
    public void t_gap_309_renameOfAnItemWhoseWindowFileVanished() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("v1");
        String window = openWindow("u1", "v1", "CONFIGURE");
        GrantService.get().listActive();
        assertTrue(can("u1", job, Item.CONFIGURE), "guard: before the deletion the window confers");
        Files.delete(grantFile(window));
        asAdmin(() -> job.renameTo("v1-2"));
        assertNotNull(j.jenkins.getItemByFullName("v1-2"), "the rename succeeds");
        asAdmin(() -> j.jenkins.createProject(FreeStyleProject.class, "v1"));
        assertFalse(can("u1", j.jenkins.getItemByFullName("v1"), Item.CONFIGURE), "SPEC 8 line 170: the new v1 gets nothing");
        Grant active = WindowStateFixtures.active(window);
        boolean confersOnRenamed = can("u1", j.jenkins.getItemByFullName("v1-2"), Item.CONFIGURE);
        System.out.println("T-GAP-309 observation: window active=" + (active != null) + ", confers on v1-2=" + confersOnRenamed);
        if (active != null) {
            assertEquals("v1-2", active.getScope().getFullName(), "LIMITATIONS 11: an active window names its item's current name");
            assertTrue(confersOnRenamed, "LIMITATIONS 11: an active window applies to its item");
        } else {
            assertFalse(confersOnRenamed, "LIMITATIONS 11: an ended window confers nothing");
        }
    }

    /**
     * T-GAP-310 (L3-03 W2; LIMITATIONS 11 "Deleting the item ends its windows"): u2's window on job
     * {@code v2}; its grant file is deleted (premise first: the window confers); the administrator deletes
     * {@code v2}: the deletion succeeds and the window is not active.
     */
    @Test
    public void t_gap_310_deletionOfAnItemWhoseWindowFileVanished() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("v2");
        String window = openWindow("u2", "v2", "CONFIGURE");
        GrantService.get().listActive();
        assertTrue(can("u2", job, Item.CONFIGURE), "guard: before the deletion the window confers");
        Files.delete(grantFile(window));
        asAdmin(job::delete);
        assertNull(j.jenkins.getItemByFullName("v2"), "the deletion succeeds");
        assertNull(WindowStateFixtures.active(window), "LIMITATIONS 11: the window is not active");
    }

    /**
     * T-GAP-311 (L3-03 W3; LIMITATIONS 11 created-item record): u3's CREATE window on folder {@code v3};
     * u3 created {@code v3/y} through it (premise: u3 configures it); the window's grant file is deleted;
     * the administrator deletes {@code v3/y} and creates a new {@code v3/y}: u3 holds no Configure on it.
     */
    @Test
    public void t_gap_311_recreatedChildGetsNothingAfterTheCreateWindowFileVanished() throws Exception {
        j.jenkins.createProject(Folder.class, "v3");
        String window = openWindow("u3", "v3", "CREATE");
        createAs("u3", "v3", "y");
        assertTrue(can("u3", j.jenkins.getItemByFullName("v3/y"), Item.CONFIGURE), "guard: u3 configures the item created through the window");
        GrantService.get().listActive();
        Files.delete(grantFile(window));
        asAdmin(() -> {
            j.jenkins.getItemByFullName("v3/y").delete();
            ((Folder) j.jenkins.getItemByFullName("v3")).createProject(FreeStyleProject.class, "y");
        });
        assertFalse(can("u3", j.jenkins.getItemByFullName("v3/y"), Item.CONFIGURE), "LIMITATIONS 11: the new v3/y gives u3 nothing");
    }

    /**
     * T-GAP-312 (L3-03 W4; LIMITATIONS 34, SPEC 1 CONFIG_TOGGLE, SPEC 6 usability): u4's window on job
     * {@code v4}; its grant file is deleted (premise first: the window confers); the administrator turns
     * change control off on the Batch Control configuration page: the save answers without an error
     * page, the switch is off with a CONFIG_TOGGLE record, and no window is active.
     */
    @Test
    public void t_gap_312_switchOffWithAVanishedWindowFile() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("v4");
        String window = openWindow("u4", "v4", "CONFIGURE");
        GrantService.get().listActive();
        assertTrue(can("u4", job, Item.CONFIGURE), "guard: before the deletion the window confers");
        Files.delete(grantFile(window));
        int toggles = ApproverFormFixtures.records(ChangeType.CONFIG_TOGGLE).size();
        Page saved = switchChangeControlOff("admin");
        assertTrue(saved.getWebResponse().getStatusCode() < 400, "the save answers without an error: " + excerpt(UsabilityFixtures.text(saved)));
        UsabilityFixtures.assertPlainRefusal("the switch-off save", UsabilityFixtures.text(saved), null);
        assertFalse(BatchControlGlobalConfiguration.get().isChangeControlEnabled(), "the switch is off");
        assertEquals(toggles + 1, ApproverFormFixtures.records(ChangeType.CONFIG_TOGGLE).size(), "SPEC 1: one CONFIG_TOGGLE record");
        assertTrue(GrantService.get().listActive().isEmpty(), "LIMITATIONS 34: no window is active");
    }

    /**
     * T-GAP-313 (L3-03 W5; D-58b (3) "Mark as reviewed ... writes a GUARD_REVIEWED record", D-58a (1) and
     * (4), SPEC 6 usability "recorded history names who did what"): u5's window on job {@code v5}; u5 saved
     * it, so the monitor lists it as changed (premise); the window's grant file is deleted; the administrator
     * marks {@code v5} reviewed on the monitor (no error page) and then renames it to {@code v5-2}. The
     * record agrees with the screen: either neither name is listed any more and the newest GUARD_REVIEWED
     * record (naming the administrator) says an entry was cleared, or the item is still listed (as
     * {@code v5-2}) and no GUARD_REVIEWED record claims an entry was cleared.
     */
    @Test
    public void t_gap_313_reviewOfAnItemWhoseWindowFileVanishedAgreesWithTheScreen() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("v5");
        String window = openWindow("u5", "v5", "CONFIGURE");
        saveAs("u5", job, "edited by u5");
        assertTrue(monitorItems().contains("v5"), "premise (D-58a (4)): the monitor lists v5: " + monitorItems());
        GrantService.get().listActive();
        assertTrue(can("u5", job, Item.CONFIGURE), "guard: before the deletion the window confers");
        Files.delete(grantFile(window));
        List<String> afterDeletion = monitorItems();
        int reviews = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED).size();
        int code = markReviewed("v5");
        assertTrue(code < 500, "the review answers without a server error, got " + code);
        List<String> afterReview = monitorItems();
        System.out.println("T-GAP-313 observation: listed after the file vanished " + afterDeletion + ", after the review " + afterReview
                + ", GUARD_REVIEWED records added by the review " + (ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED).size() - reviews));
        asAdmin(() -> ((FreeStyleProject) j.jenkins.getItemByFullName("v5")).renameTo("v5-2"));
        List<String> listed = monitorItems();
        List<ChangeRecord> newer = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED);
        newer = newer.subList(reviews, newer.size());
        boolean claimed = newer.stream().anyMatch(r -> claimsClear(r.getDetail()));
        System.out.println("T-GAP-313 observation: review HTTP " + code + ", listed " + listed + ", records " + WindowStateFixtures.describe(newer));
        if (!listed.contains("v5") && !listed.contains("v5-2")) {
            assertTrue(claimed && newer.stream().anyMatch(r -> "admin".equals(r.getUser())),
                    "D-58a (1), D-58b (3): the item left the list (listed after the file vanished " + afterDeletion + ", after the review "
                            + afterReview + ", after the rename " + listed + "), so a GUARD_REVIEWED record by admin must say an entry was"
                            + " cleared; the state ends only through the review and otherwise follows the rename. GUARD_REVIEWED records: "
                            + WindowStateFixtures.describe(newer));
        } else {
            assertTrue(listed.contains("v5-2"), "D-58a (1): a still-listed item is listed under its new name: " + listed);
            assertFalse(claimed, "the item is still listed, so no GUARD_REVIEWED record may claim an entry was cleared: "
                    + WindowStateFixtures.describe(newer));
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface Step {
        void run() throws Exception;
    }

    private static String what(boolean fault) {
        return fault ? "with the grant file unreadable" : "guard without the fault";
    }

    /** Runs {@code step}; with {@code fault}, while {@code file} has no permission bits (chmod 000), restored afterwards. */
    static void withFault(boolean fault, Path file, Step step) throws Exception {
        if (!fault) {
            step.run();
            return;
        }
        PlatformFixtures.assumeCanMakeUnreadable();
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(file), "the file system does not refuse reads of an unreadable file for this process");
            step.run();
        } finally {
            if (Files.exists(file)) {
                Files.setPosixFilePermissions(file, original);
            }
        }
    }

    /** Requests a 30-minute window through the form and has a1 approve it; returns the window's id. */
    private String openWindow(String userId, String fullName, String action) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of(action), 30, "work on " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return WindowStateFixtures.windowId(userId, fullName);
    }

    private Path grantFile(String id) {
        Path file = j.jenkins.getRootDir().toPath().resolve("batch-control/grants/" + id + ".xml");
        assertTrue(Files.isRegularFile(file), "premise (ARCHITECTURE 5): the window is stored at " + file);
        return file;
    }

    private Grant stored(String id) throws Exception {
        return (Grant) Jenkins.XSTREAM2.fromXML(Files.readString(grantFile(id), StandardCharsets.UTF_8));
    }

    private static void asAdmin(Step step) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            step.run();
        }
    }

    static boolean can(String userId, Item item, Permission permission) {
        return item.getACL().hasPermission2(User.getById(userId, true).impersonate2(), permission);
    }

    /** {@code user} creates {@code folder/name} through {@code createItem} with a Freestyle payload. */
    void createAs(String user, String folder, String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest req = new WebRequest(new URL(wc.createCrumbedUrl(((TopLevelItem) j.jenkins.getItemByFullName(folder)).getUrl() + "createItem")
                .toExternalForm() + "&name=" + name), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody("<?xml version='1.1' encoding='UTF-8'?><project><builders/><publishers/><buildWrappers/></project>");
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " creates " + folder + "/" + name + ", got " + code);
    }

    /** {@code user} saves {@code job} through {@code config.xml} with a changed description (no authorization change). */
    void saveAs(String user, FreeStyleProject job, String description) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest post = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(StoreFaultGapTest.withDescription(job.getConfigFile().asString(), description));
        int code = wc.getPage(post).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " saves " + job.getFullName() + ", got " + code);
    }

    /** The administrator's "Mark as reviewed" on the monitor for {@code item}; returns the HTTP status. */
    int markReviewed(String item) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        URL url = new URL(wc.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed").toExternalForm()
                + "&item=" + java.net.URLEncoder.encode(item, StandardCharsets.UTF_8));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    /** The item full names the batch-control-strategy monitor lists on Manage Jenkins (empty when it is not shown). */
    List<String> monitorItems() throws Exception {
        return monitorItems(j);
    }

    static List<String> monitorItems(JenkinsRule j) throws Exception {
        HtmlPage manage = UsabilityFixtures.htmlPage(j, "admin", "manage/");
        List<String> out = new ArrayList<>();
        DomNode monitor = manage.querySelector("div[data-monitor-id=batch-control-strategy]");
        if (monitor == null) {
            return out;
        }
        for (DomNode n : monitor.querySelectorAll("li > a > code")) {
            out.add(n.asNormalizedText().trim());
        }
        return out;
    }

    /**
     * True if a GUARD_REVIEWED detail says that at least one entry was cleared: a number above zero
     * before the word "cleared" in the same sentence, and not "not cleared".
     */
    static boolean claimsClear(String detail) {
        if (detail == null) {
            return false;
        }
        for (String sentence : detail.split("\\.\\s")) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("cleared");
            if (at < 0 || lower.contains("not cleared")) {
                continue;
            }
            Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(lower.substring(0, at));
            while (m.find()) {
                if (Integer.parseInt(m.group(1)) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The text of the stored element {@code name} (one of the grant file's item lists), or "". */
    static String listed(String xml, String name) {
        Matcher m = Pattern.compile("(?s)<" + name + "[^>]*>(.*?)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** Opens the Batch Control configuration page as {@code user}, unticks change control and saves. */
    Page switchChangeControlOff(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        HtmlPage page = wc.getPage(new WebRequest(new URL(j.getURL(), "batch-control-configuration/"), HttpMethod.GET));
        HtmlForm form = null;
        for (HtmlForm f : page.getForms()) {
            if (UsabilityFixtures.hasField(f, "pendingTimeoutHours")) {
                form = f;
            }
        }
        assertNotNull(form, "fixture: the configuration page carries the settings form; forms: " + UsabilityFixtures.formActions(page));
        boolean found = false;
        for (DomElement e : form.getElementsByTagName("input")) {
            if (e instanceof HtmlCheckBoxInput box && String.valueOf(box.getAttribute("name")).endsWith("changeControlEnabled")) {
                box.setChecked(false);
                found = true;
            }
        }
        assertTrue(found, "fixture: the form offers the change control switch");
        return j.submit(form);
    }
}
