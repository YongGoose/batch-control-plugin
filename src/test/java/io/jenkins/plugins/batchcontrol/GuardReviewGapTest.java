package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 3, scenarios L3-06 and L3-07 (the rows that need no restart): marked runs on the review
 * surface follow their job, and review and guard edge cases. Matrix rows T-GAP-319 .. T-GAP-321 and
 * T-GAP-323 .. T-GAP-328 (note 279); the restart row of L3-06 is T-GAP-322 in {@link StoreRestartGapTest}.
 *
 * <p>Basis: SPEC 2 lines 46-48 (D-58, D-58a, D-58b, D-58c: guarded items, "Guarding follows renames and
 * moves", "guarding covers the item and every item below it", "The state ends only through the explicit
 * 'Mark as reviewed' action (POST, native Item/Configure or Overall/Administer, not by a grant), which
 * writes a GUARD_REVIEWED record", "the review surfaces list such runs"); D-58a (1) "Coverage and the
 * 'changed under a grant' state follow renames and moves", (4) "An administrator can see which items are
 * in the 'changed under a grant' state"; LIMITATIONS 35 (the guarded items: "every item an active grant
 * names; every item whose configuration was changed under a grant ... until the item is marked as
 * reviewed"; "Guarding covers the item and everything below it"; the review "offered to administrators
 * next to each item on the Manage Jenkins monitor, and to users who hold Item/Configure natively ... on
 * the item's Batch Control page"); docs/reports/security-29.md S-29-02 (a reviewer clears only entries
 * they may review) and security-30 S-30-04 (an item moved out of a changed folder stays guarded); SPEC 2
 * line 51 (a link only for a viewer who may open it).
 *
 * <p>The monitor is read from Manage Jenkins as the administrator: {@code div[data-monitor-id=
 * batch-control-strategy]}, one list item per listed item (its full name in {@code code}), the marked runs
 * of that item as links inside it. A widening is a {@code config.xml} save adding an authorization property
 * that gives another user Item/Configure; "reverted" means the entry is not kept and a GRANT_VIOLATION is
 * written (T-02-58 / T-02-65 are the pattern).
 *
 * <p>Batch Control matrix strategy, change control on, run control off; builds run as a fixed low-privilege
 * account (Authorize Project), so only changed items activate the monitor. u1 .. u3 hold RequestGrant; carol
 * holds native Item/Configure on the folders {@code F} and {@code H} (folder authorization properties) and
 * BatchControl/Request; a1 approves.
 *
 * <p>Written from docs/SPEC.md item 2, docs/DECISIONS.md D-58 .. D-58c, docs/LIMITATIONS.md item 35 and
 * docs/reports/security-29.md / security-30.md only (no src/main knowledge).
 */
@WithJenkins
public class GuardReviewGapTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchControlMatrixAuthorizationStrategy strategy = new BatchControlMatrixAuthorizationStrategy();
        strategy.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String userId : new String[] {"u1", "u2", "u3", "a1", "carol"}) {
            strategy.add(Jenkins.READ, PermissionEntry.user(userId));
            strategy.add(Item.READ, PermissionEntry.user(userId));
        }
        for (String userId : new String[] {"u1", "u2", "u3"}) {
            strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user(userId));
        }
        strategy.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        strategy.add(BatchControlPermissions.REQUEST, PermissionEntry.user("carol"));
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.configureBuildAuthenticator();
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();
    }

    // ------------------------------------------------------------------ L3-06

    /**
     * T-GAP-319 (L3-06; D-58c "the review surfaces list such runs", D-58a (4), SPEC 2 line 51): folder
     * {@code F} with the Pipeline {@code F/P} (build #1); u1's CONFIGURE window on {@code F/P}, u1 replays
     * #1 (run #2 is marked, {@code F/P} changed); u2's CONFIGURE window on {@code F}, u2 saves {@code F}
     * ({@code F} changed). The monitor lists {@code F} with {@code F/P}'s marked run and {@code F/P} with
     * the same run, each linked to the build page {@code job/F/job/P/2/}, which the administrator opens.
     */
    @Test
    public void t_gap_319_monitorListsMarkedRunsUnderTheJobAndItsFolder() throws Exception {
        markedPipelineInChangedFolder("F");
        Map<String, List<String>> listed = monitor();
        assertTrue(listed.containsKey("F") && listed.containsKey("F/P"), "D-58a (4): the monitor lists F and F/P: " + listed);
        String build = new URL(j.getURL(), "job/F/job/P/2/").getPath();
        assertTrue(listed.get("F/P").contains(build), "D-58c: F/P's row lists its marked run #2 linked to its build page: " + listed);
        assertTrue(listed.get("F").contains(build), "D-58c: F's row shows the marked run of the job inside it: " + listed);
        assertEquals(200, ApproverFormFixtures.get(j, "admin", "job/F/job/P/2/").getStatusCode(), "SPEC 2 line 51: the administrator opens the link");
    }

    /**
     * T-GAP-320 (L3-06; SPEC 2 line 46 "Guarding follows renames and moves", D-58a (1)): with the fixture of
     * T-GAP-319, the administrator renames {@code F} to {@code G}: the monitor lists {@code G} and
     * {@code G/P}, each with the marked run under its new URL {@code job/G/job/P/2/}, and nothing under
     * {@code F}.
     */
    @Test
    public void t_gap_320_markedRunsFollowAFolderRename() throws Exception {
        markedPipelineInChangedFolder("F");
        adminDo(() -> ((Folder) j.jenkins.getItemByFullName("F")).renameTo("G"));
        Map<String, List<String>> listed = monitor();
        String build = new URL(j.getURL(), "job/G/job/P/2/").getPath();
        assertTrue(listed.containsKey("G") && listed.containsKey("G/P"), "D-58a (1): the monitor lists G and G/P: " + listed);
        assertTrue(listed.get("G/P").contains(build) && listed.get("G").contains(build),
                "D-58c: both rows list the marked run under its new name: " + listed);
        assertFalse(listed.keySet().stream().anyMatch(k -> k.equals("F") || k.startsWith("F/")), "nothing is listed under F: " + listed);
        assertFalse(listed.values().stream().anyMatch(runs -> runs.stream().anyMatch(r -> r.contains("/job/F/"))),
                "no run is listed under F: " + listed);
    }

    /**
     * T-GAP-321 (L3-06; D-58a (1), ARCHITECTURE 5 "changedItems ... loses an item when it is deleted"):
     * after the rename of T-GAP-320, the administrator deletes an unrelated job: the monitor lists the
     * same items and runs as before; then the administrator deletes {@code G/P}: its marked run is listed
     * nowhere and {@code G/P} is not listed.
     */
    @Test
    public void t_gap_321_deletingTheJobRemovesItsMarkedRunFromEveryRow() throws Exception {
        markedPipelineInChangedFolder("F");
        adminDo(() -> ((Folder) j.jenkins.getItemByFullName("F")).renameTo("G"));
        FreeStyleProject unrelated = j.createFreeStyleProject("unrelated");
        Map<String, List<String>> before = monitor();
        assertTrue(before.values().stream().anyMatch(runs -> runs.stream().anyMatch(r -> r.contains("/job/G/job/P/2/"))),
                "premise: the marked run G/P#2 is listed before the deletion: " + before);
        adminDo(unrelated::delete);
        assertEquals(before, monitor(), "deleting an unrelated job changes nothing listed");
        adminDo(() -> j.jenkins.getItemByFullName("G/P").delete());
        Map<String, List<String>> after = monitor();
        assertFalse(after.containsKey("G/P"), "the deleted G/P is not listed: " + after);
        assertFalse(after.values().stream().anyMatch(runs -> runs.stream().anyMatch(r -> r.contains("/job/P/"))),
                "the deleted job's marked run is listed nowhere: " + after);
    }

    // ------------------------------------------------------------------ L3-07

    /**
     * T-GAP-323 (L3-07; D-58a (1) the state follows moves, D-58b (3), LIMITATIONS 35 guarded items): u1's
     * CREATE window on {@code F}; u1 creates {@code F/x} (changed under the window); the administrator
     * moves it into {@code H}: carol's widening on {@code H/x} is reverted with a GRANT_VIOLATION (the
     * state followed the move). The administrator marks {@code H/x} reviewed and revokes u1's window: the
     * same widening by carol is kept, with no GRANT_VIOLATION. Whether {@code H/x} is guarded between the
     * review and the revocation (the window is active; its created-item record follows the item, but D-71
     * matches it by the parent folder) is not documented; the outcome there is printed (note 279).
     */
    @Test
    public void t_gap_323_createdMovedAndReviewedItemIsGuardedOnlyAsTheDocumentsSay() throws Exception {
        folders();
        openWindow("u1", "F", "CREATE");
        createAs("u1", "F", "x");
        adminDo(() -> Items.move((FreeStyleProject) j.jenkins.getItemByFullName("F/x"), (Folder) j.jenkins.getItemByFullName("H")));
        FreeStyleProject hx = (FreeStyleProject) j.jenkins.getItemByFullName("H/x");
        assertNotNull(hx, "fixture: F/x is now H/x");
        assertTrue(WindowStoreFaultGapTest.can("carol", hx, Item.CONFIGURE), "premise: carol configures H/x natively");
        assertReverted(hx, "zed", "D-58a (1): the changed-under-a-grant state followed the move");

        assertTrue(markReviewed("H/x") < 400, "fixture: the administrator marks H/x reviewed");
        boolean keptWhileActive = widen("carol", hx, "yan");
        System.out.println("T-GAP-323 observation: after the review, with u1's window active, carol's widening on H/x kept = " + keptWhileActive);
        adminDo(() -> GrantService.get().revoke(WindowStateFixtures.windowId("u1", "F")));
        int violations = violations();
        assertTrue(widen("carol", hx, "wes"), "LIMITATIONS 35: reviewed and named by no active grant, H/x keeps carol's widening");
        assertEquals(violations, violations(), "and no GRANT_VIOLATION is written");
    }

    /**
     * T-GAP-324 (L3-07; D-58a (4), SPEC 8 the monitor): builds run as a low-privilege account and the Batch
     * Control strategy is installed, so with no changed item the batch-control-strategy monitor is not shown
     * on Manage Jenkins (guard); after u1 and u2 each save a job under a CONFIGURE window, it is shown and
     * lists both jobs.
     */
    @Test
    public void t_gap_324_monitorIsShownOnlyWhileItemsAreChanged() throws Exception {
        FreeStyleProject one = j.createFreeStyleProject("one");
        FreeStyleProject two = j.createFreeStyleProject("two");
        assertFalse(StrategyFixtures.strategyMonitor().isActivated(), "guard: with no changed item the monitor is not active");
        assertTrue(UsabilityFixtures.htmlPage(j, "admin", "manage/").querySelector("div[data-monitor-id=batch-control-strategy]") == null,
                "guard: with no changed item the monitor is not shown");
        openWindow("u1", "one", "CONFIGURE");
        openWindow("u2", "two", "CONFIGURE");
        saveAs("u1", one);
        saveAs("u2", two);
        assertTrue(StrategyFixtures.strategyMonitor().isActivated(), "D-58a (4): the monitor is active");
        Map<String, List<String>> listed = monitor();
        assertTrue(listed.containsKey("one") && listed.containsKey("two"), "D-58a (4): the monitor lists both jobs: " + listed);
    }

    /**
     * T-GAP-325 (L3-07; D-58b (3) "Mark as reviewed (POST, native Item/Configure or Overall/Administer, not
     * by a grant) offered on the batch-control-strategy monitor ... and on the item's Batch Control page",
     * LIMITATIONS 35, S-29-02): {@code F} and {@code F/secret} are both changed under windows
     * ({@code F/secret} blocks inheritance, so carol may not configure it). carol, who holds native
     * Configure on {@code F} and BatchControl/Request, tries to mark {@code F} reviewed on {@code F}'s Batch
     * Control page and on the monitor. Neither answer is a server error; if both are refused, {@code F} and
     * {@code F/secret} stay listed and no GUARD_REVIEWED record is written (the record agrees with the
     * screen); if one is accepted, {@code F} leaves the list, {@code F/secret} stays, and the GUARD_REVIEWED
     * record names carol and {@code F/secret} as not cleared (S-29-02). Which surface, if any, offers a
     * folder's review to a non-administrator is not documented (note 279); the answers are printed.
     */
    @Test
    public void t_gap_325_folderReviewByANativeConfigureHolderAgreesWithTheScreen() throws Exception {
        folders();
        FreeStyleProject secret = secretJob();
        openWindow("u2", "F", "CONFIGURE");
        openWindow("u3", "F/secret", "CONFIGURE");
        saveAs("u2", (Folder) j.jenkins.getItemByFullName("F"));
        saveAs("u3", secret);
        Map<String, List<String>> before = monitor();
        assertTrue(before.containsKey("F") && before.containsKey("F/secret"), "premise: F and F/secret are listed: " + before);
        assertFalse(WindowStoreFaultGapTest.can("carol", secret, Item.CONFIGURE), "premise: carol may not configure F/secret");
        int reviews = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED).size();
        int page = postAs("carol", "job/F/batch-control/markReviewed");
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "carol");
        int viaMonitor = wc.getPage(new WebRequest(new URL(wc.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed")
                .toExternalForm() + "&item=F"), HttpMethod.POST)).getWebResponse().getStatusCode();
        System.out.println("T-GAP-325 observation: carol's review of F answered HTTP " + page + " on F's page and " + viaMonitor + " on the monitor");
        assertTrue(page < 500 && viaMonitor < 500, "neither review answers with a server error");
        Map<String, List<String>> after = monitor();
        List<ChangeRecord> records = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED);
        List<ChangeRecord> added = records.subList(reviews, records.size());
        assertTrue(after.containsKey("F/secret"), "S-29-02: F/secret, which carol may not review, stays listed: " + after);
        if (page >= 400 && viaMonitor >= 400) {
            assertTrue(after.containsKey("F"), "both reviews were refused, so F stays listed: " + after);
            assertTrue(added.isEmpty(), "a refused review writes no GUARD_REVIEWED record: " + WindowStateFixtures.describe(added));
        } else {
            assertFalse(after.containsKey("F"), "D-58b (3): an accepted review takes F off the list: " + after);
            assertEquals(1, added.size(), "one GUARD_REVIEWED record: " + WindowStateFixtures.describe(added));
            assertEquals("carol", added.get(0).getUser(), "the record names the reviewer");
            assertTrue(String.valueOf(added.get(0).getDetail()).contains("F/secret"), "S-29-02: the record names F/secret as not cleared: "
                    + added.get(0).getDetail());
        }
    }

    /**
     * T-GAP-326 (L3-07; D-58b (1) "guarding covers the item and every item below it", D-58b (3)): {@code F}
     * is changed under a window, the job {@code F/j} is not; carol (native Configure on {@code F/j} through
     * {@code F}) POSTs {@code F/j}'s {@code markReviewed}: no GUARD_REVIEWED record claims an entry was
     * cleared, and {@code F/j} is still guarded: carol's widening on it is reverted with a GRANT_VIOLATION.
     */
    @Test
    public void t_gap_326_reviewOfAChildDoesNotLiftItsChangedFolder() throws Exception {
        folders();
        FreeStyleProject fj = asAdmin(() -> ((Folder) j.jenkins.getItemByFullName("F")).createProject(FreeStyleProject.class, "j"));
        openWindow("u2", "F", "CONFIGURE");
        saveAs("u2", (Folder) j.jenkins.getItemByFullName("F"));
        assertTrue(monitor().containsKey("F") && !monitor().containsKey("F/j"), "premise: F is listed, F/j is not: " + monitor());
        int reviews = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED).size();
        int code = postAs("carol", fj.getUrl() + "batch-control/markReviewed");
        System.out.println("T-GAP-326 observation: carol's review of F/j answered HTTP " + code);
        assertTrue(code < 500, "the review answers without a server error, got " + code);
        List<ChangeRecord> records = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED);
        List<ChangeRecord> added = records.subList(reviews, records.size());
        assertTrue(added.stream().noneMatch(r -> WindowStoreFaultGapTest.claimsClear(r.getDetail())),
                "nothing was changed at F/j, so no GUARD_REVIEWED record may claim a clear: " + WindowStateFixtures.describe(added));
        assertTrue(monitor().containsKey("F"), "F stays listed");
        assertReverted(fj, "zed", "D-58b (1): F/j stays guarded through its changed folder");
    }

    /**
     * T-GAP-327 (L3-07; D-58b (3), S-29-02): u1's CREATE window on {@code F} created {@code F/a} and
     * {@code F/b}, u2's created {@code F/c} (all three changed); the administrator reviews {@code F/a} on the
     * monitor: {@code F/a} leaves the list with a GUARD_REVIEWED record by the administrator that says an
     * entry was cleared, and {@code F/b} and {@code F/c} are still listed.
     */
    @Test
    public void t_gap_327_reviewClearsOnlyTheReviewedItem() throws Exception {
        folders();
        openWindow("u1", "F", "CREATE");
        openWindow("u2", "F", "CREATE");
        createAs("u1", "F", "a");
        createAs("u1", "F", "b");
        createAs("u2", "F", "c");
        Map<String, List<String>> before = monitor();
        assertTrue(before.keySet().containsAll(List.of("F/a", "F/b", "F/c")), "premise: F/a, F/b and F/c are listed: " + before);
        int reviews = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED).size();
        assertTrue(markReviewed("F/a") < 400, "the administrator's review of F/a succeeds");
        Map<String, List<String>> after = monitor();
        assertFalse(after.containsKey("F/a"), "D-58b (3): F/a leaves the list: " + after);
        assertTrue(after.containsKey("F/b") && after.containsKey("F/c"), "F/b and F/c stay listed: " + after);
        List<ChangeRecord> records = ApproverFormFixtures.records(ChangeType.GUARD_REVIEWED);
        assertEquals(reviews + 1, records.size(), "one GUARD_REVIEWED record");
        ChangeRecord rec = records.get(records.size() - 1);
        assertTrue("admin".equals(rec.getUser()) && WindowStoreFaultGapTest.claimsClear(rec.getDetail()),
                "the record names the administrator and says an entry was cleared: " + WindowStateFixtures.describe(List.of(rec)));
    }

    /**
     * T-GAP-328 (L3-07; security-30 S-30-04, D-58a (1), D-58b (1)): {@code F} is changed under u2's
     * window; the administrator moves the job {@code F/k} into {@code H}: {@code H/k} is listed as changed
     * and carol's widening on it is reverted with a GRANT_VIOLATION. Guard: on {@code H/other}, which was
     * never in {@code F}, carol's widening is kept.
     */
    @Test
    public void t_gap_328_itemMovedOutOfAChangedFolderStaysGuarded() throws Exception {
        folders();
        Folder f = (Folder) j.jenkins.getItemByFullName("F");
        Folder h = (Folder) j.jenkins.getItemByFullName("H");
        adminDo(() -> f.createProject(FreeStyleProject.class, "k"));
        FreeStyleProject other = asAdmin(() -> h.createProject(FreeStyleProject.class, "other"));
        openWindow("u2", "F", "CONFIGURE");
        saveAs("u2", f);
        assertTrue(monitor().containsKey("F"), "premise: F is listed");
        adminDo(() -> Items.move((FreeStyleProject) j.jenkins.getItemByFullName("F/k"), h));
        FreeStyleProject hk = (FreeStyleProject) j.jenkins.getItemByFullName("H/k");
        assertNotNull(hk, "fixture: F/k is now H/k");
        assertTrue(monitor().containsKey("H/k"), "S-30-04: H/k, moved out of the changed folder, is listed as changed: " + monitor());
        assertReverted(hk, "zed", "S-30-04: H/k stays guarded");
        int violations = violations();
        assertTrue(widen("carol", other, "zed"), "guard: on H/other, never in F, carol's widening is kept");
        assertEquals(violations, violations(), "guard: and no GRANT_VIOLATION is written");
    }

    // ------------------------------------------------------------------ fixtures and helpers

    private interface Body<T> {
        T run() throws Exception;
    }

    private interface Step {
        void run() throws Exception;
    }

    private static <T> T asAdmin(Body<T> body) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            return body.run();
        }
    }

    private static void adminDo(Step step) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            step.run();
        }
    }

    /** The L3-06 fixture: folder {@code name} with Pipeline {@code P}; u1 replays #1 under a window; u2 saves the folder under one. */
    private void markedPipelineInChangedFolder(String name) throws Exception {
        Folder folder = j.jenkins.createProject(Folder.class, name);
        WorkflowJob p = folder.createProject(WorkflowJob.class, "P");
        p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        j.buildAndAssertSuccess(p);
        openWindow("u1", name + "/P", "CONFIGURE");
        try (ACLContext ignored = ACL.as2(User.getById("u1", true).impersonate2())) {
            p.getBuildByNumber(1).getAction(ReplayAction.class).run("echo 'replayed'", new LinkedHashMap<>());
        }
        j.waitUntilNoActivity();
        assertNotNull(p.getBuildByNumber(2), "fixture: u1's replay ran as #2");
        assertEquals(1, ApproverFormFixtures.records(ChangeType.REPLAY_UNDER_GRANT).size(), "fixture (D-58c): the replay is marked");
        openWindow("u2", name, "CONFIGURE");
        saveAs("u2", folder);
    }

    /** Folders {@code F} and {@code H}, on each of which carol holds native Item/Configure (folder property). */
    private void folders() throws Exception {
        for (String name : new String[] {"F", "H"}) {
            Folder folder = j.jenkins.createProject(Folder.class, name);
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty property =
                    new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
            property.add(Item.CONFIGURE, PermissionEntry.user("carol"));
            folder.addProperty(property);
            assertTrue(WindowStoreFaultGapTest.can("carol", folder, Item.CONFIGURE), "fixture: carol configures " + name);
        }
    }

    /** {@code F/secret}: a job whose authorization property blocks inheritance; carol and u3 may read it, only u3 (via a window) configure it. */
    private FreeStyleProject secretJob() throws Exception {
        FreeStyleProject secret = asAdmin(() -> ((Folder) j.jenkins.getItemByFullName("F")).createProject(FreeStyleProject.class, "secret"));
        AuthorizationMatrixProperty property = new AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        property.setInheritanceStrategy(new NonInheritingStrategy());
        for (String userId : new String[] {"u3", "a1", "carol"}) {
            property.add(Item.READ, PermissionEntry.user(userId));
        }
        secret.addProperty(property);
        assertFalse(WindowStoreFaultGapTest.can("carol", secret, Item.CONFIGURE), "fixture: carol may not configure F/secret");
        return secret;
    }

    private String openWindow(String userId, String fullName, String action) throws Exception {
        String id = submitGrantOk(j, userId, fullName, List.of(action), 30, "work on " + fullName, null, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        return id;
    }

    private void createAs(String user, String folder, String name) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest req = new WebRequest(new URL(wc.createCrumbedUrl(((TopLevelItem) j.jenkins.getItemByFullName(folder)).getUrl() + "createItem")
                .toExternalForm() + "&name=" + name), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody("<?xml version='1.1' encoding='UTF-8'?><project><builders/><publishers/><buildWrappers/></project>");
        int code = wc.getPage(req).getWebResponse().getStatusCode();
        assertTrue(code < 400, "fixture: " + user + " creates " + folder + "/" + name + ", got " + code);
    }

    /** {@code user} saves {@code item} through {@code config.xml} with a changed description (no authorization change). */
    private void saveAs(String user, hudson.model.AbstractItem item) throws Exception {
        String xml = item.getConfigFile().asString();
        String edited = withDescription(xml, "edited by " + user);
        assertFalse(edited.equals(xml), "fixture: the description changes");
        assertTrue(postXml(user, item, edited) < 400, "fixture: " + user + " saves " + item.getFullName());
    }

    /** Adds an authorization property giving {@code sid} Item/Configure, saved by {@code user}; true if the entry is kept. */
    private boolean widen(String user, FreeStyleProject job, String sid) throws Exception {
        String xml = job.getConfigFile().asString();
        String property = "<hudson.security.AuthorizationMatrixProperty>"
                + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                + "<permission>USER:hudson.model.Item.Configure:" + sid + "</permission></hudson.security.AuthorizationMatrixProperty>";
        String widened;
        if (xml.contains("<properties/>")) {
            widened = xml.replace("<properties/>", "<properties>" + property + "</properties>");
        } else if (xml.contains("<properties>")) {
            widened = xml.replaceFirst("<properties>", "<properties>" + property);
        } else {
            widened = xml.replaceFirst("(?s)(\\?>\\s*<project(?:\\s[^>]*)?>)", "$1<properties>" + property + "</properties>");
        }
        assertFalse(widened.equals(xml), "fixture: the widening changes config.xml");
        postXml(user, job, widened);
        FreeStyleProject current = (FreeStyleProject) j.jenkins.getItemByFullName(job.getFullName());
        AuthorizationMatrixProperty amp = current.getProperty(AuthorizationMatrixProperty.class);
        return amp != null && amp.getGrantedPermissionEntries().values().stream()
                .anyMatch(set -> set.stream().anyMatch(pe -> sid.equals(pe.getSid())));
    }

    private void assertReverted(FreeStyleProject job, String sid, String what) throws Exception {
        int violations = violations();
        assertFalse(widen("carol", job, sid), what + ": carol's widening on " + job.getFullName() + " is reverted");
        assertEquals(violations + 1, violations(), what + ": the reverted widening is recorded as GRANT_VIOLATION");
    }

    private int postXml(String user, hudson.model.AbstractItem item, String xml) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        WebRequest post = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "config.xml"), HttpMethod.POST);
        post.setAdditionalHeader("Content-Type", "application/xml");
        post.setRequestBody(xml);
        return wc.getPage(post).getWebResponse().getStatusCode();
    }

    private int postAs(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, user);
        return wc.getPage(new WebRequest(wc.createCrumbedUrl(path), HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    private int markReviewed(String item) throws Exception {
        JenkinsRule.WebClient wc = ApproverFormFixtures.client(j, "admin");
        URL url = new URL(wc.createCrumbedUrl("manage/administrativeMonitor/batch-control-strategy/markReviewed").toExternalForm()
                + "&item=" + java.net.URLEncoder.encode(item, StandardCharsets.UTF_8));
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    private static int violations() {
        return ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).size();
    }

    /** Listed item full name -> the paths of the run links in its row, as the administrator sees the monitor. */
    private Map<String, List<String>> monitor() throws Exception {
        HtmlPage manage = UsabilityFixtures.htmlPage(j, "admin", "manage/");
        Map<String, List<String>> out = new LinkedHashMap<>();
        DomNode monitor = manage.querySelector("div[data-monitor-id=batch-control-strategy]");
        if (monitor == null) {
            return out;
        }
        for (DomNode code : monitor.querySelectorAll("li > a > code")) {
            DomElement li = (DomElement) code.getParentNode().getParentNode();
            List<String> runs = new ArrayList<>();
            for (DomElement child : li.getChildElements()) {
                if ("ul".equals(child.getTagName())) {
                    for (DomElement a : child.getElementsByTagName("a")) {
                        if (a.hasAttribute("href")) {
                            runs.add(manage.getFullyQualifiedUrl(a.getAttribute("href")).getPath());
                        }
                    }
                }
            }
            out.put(code.asNormalizedText().trim(), runs);
        }
        return out;
    }

    /** Sets the description in an item's config.xml text, whatever form the element and the root have. */
    static String withDescription(String xml, String text) {
        if (xml.contains("<description/>")) {
            return xml.replace("<description/>", "<description>" + text + "</description>");
        }
        if (xml.matches("(?s).*<description>.*?</description>.*")) {
            return xml.replaceFirst("(?s)<description>.*?</description>", "<description>" + text + "</description>");
        }
        return xml.replaceFirst("(?s)(\\?>\\s*<[\\w.\\-]+(?:\\s[^>]*)?>)", "$1<description>" + text + "</description>");
    }
}
