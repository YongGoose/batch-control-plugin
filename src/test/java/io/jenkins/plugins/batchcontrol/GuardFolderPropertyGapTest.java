package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage lane 2, scenarios L2-01, L2-02 and L2-03 (matrix rows T-GAP-201 .. T-GAP-206, note 277):
 * the per-item authorization guard on folder authorization properties and on items created inside
 * guarded folders.
 *
 * <p>Basis: SPEC item 2 "a user whose Configure comes only from a grant cannot change an item's
 * authorization property; the change is reverted and recorded as GRANT_VIOLATION" and "A Create grant
 * leaves no permanent authorization entry on the item it created, whether ... a copied item ... would
 * have added it" (D-35b, D-35c); SPEC item 2, the D-58 line "any change that widens access on it ...
 * is reverted and recorded as GRANT_VIOLATION whoever makes it, unless it is an HTTP save by an
 * Overall/Administer holder" (including "authorization entries on an item created inside a guarded
 * folder"); the D-48 line (403 with a plain message); DECISIONS D-35d (1) ("only from a grant" is
 * decided by permission) and D-58a; LIMITATIONS 35 (only items a grant has touched are affected).
 *
 * <p>Users come from {@link StrategyFixtures}: bob and carol hold RequestGrant, a1 approves; the
 * folder's own authorization property is set by the administrator before any window exists, so the
 * fixture itself is never guarded.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md, docs/LIMITATIONS.md and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class GuardFolderPropertyGapTest {

    private static final String FOLDER_PROPERTY = "com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty";

    private JenkinsRule j;
    private BatchControlMatrixAuthorizationStrategy strategy;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        strategy = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(strategy);
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    // ------------------------------------------------------------------ L2-01

    /**
     * T-GAP-201 (L2-01, SPEC 2 D-35b + D-48): folder {@code team} whose own authorization property
     * gives alice Item/Configure (and bob Item/Read, so the form has a row for him); bob holds a
     * CONFIGURE window on {@code team} and no native Configure. bob ticks Configure for himself in
     * the folder's matrix on the configuration form and edits the description. The answer is 403
     * with the D-48 notice; bob's entry is gone (memory and disk), alice's entry stays, the
     * description change is saved, and one GRANT_VIOLATION names bob and {@code team}. Guard: a
     * description-only form save by bob is answered normally and writes no GRANT_VIOLATION.
     */
    @Test
    public void t_gap_201_grantOnlyFolderFormSelfEntryIsRevertedWithNotice() throws Exception {
        Folder team = folderWithProperty("team", "alice", "bob-read");
        assertFalse(has(team, "bob", Item.CONFIGURE), "premise: bob holds no native Configure on the folder");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(team, "bob", Item.CONFIGURE), "premise: the window confers Configure on the folder");

        // guard: a save that does not touch authorization is answered normally
        int before = violations().size();
        HtmlPage plain = configurePage("bob", "job/team/configure");
        HtmlForm plainForm = plain.getFormByName("config");
        setDescription(plainForm, "plain-folder-edit");
        Page plainAnswer = j.submit(plainForm);
        assertTrue(plainAnswer.getWebResponse().getStatusCode() < 400, "guard: a description-only save of the folder must"
                + " succeed, got " + plainAnswer.getWebResponse().getStatusCode());
        assertEquals("plain-folder-edit", folder("team").getDescription(), "guard: the description-only save is kept");
        assertEquals(before, violations().size(), "guard: a save that does not touch authorization records nothing");

        HtmlPage configure = configurePage("bob", "job/team/configure");
        HtmlForm form = configure.getFormByName("config");
        setDescription(form, "folder-desc-after");
        HtmlCheckBoxInput box = configureBoxOf(configure, "bob");
        assertFalse(box.isChecked(), "fixture: bob's Configure box must start unticked");
        box.setChecked(true);
        Page answer = j.submit(form);

        assertEquals(403, answer.getWebResponse().getStatusCode(), "the form save whose authorization change was reverted"
                + " must answer 403 (D-48), got " + answer.getWebResponse().getStatusCode());
        GrantSelfGrantFeedbackTest.assertGuardFeedback("folder form save", UsabilityFixtures.text(answer), "team");
        Folder current = folder("team");
        assertEquals("folder-desc-after", current.getDescription(), "the other changes of the save must be kept");
        assertFalse(folderGrants(current, Item.CONFIGURE, "bob"), "bob's Configure entry on the folder must be removed");
        assertTrue(folderGrants(current, Item.CONFIGURE, "alice"), "alice's existing entry must stay");
        assertFalse(current.getConfigFile().asString().contains("Item.Configure:bob"), "the removal must be on disk");
        List<ChangeRecord> v = violations();
        assertEquals(before + 1, v.size(), "one GRANT_VIOLATION must be written: " + describe(v));
        assertEquals("bob", v.get(v.size() - 1).getUser(), "the GRANT_VIOLATION names bob");
        assertEquals("team", v.get(v.size() - 1).getTarget(), "the GRANT_VIOLATION names the folder");
    }

    /**
     * T-GAP-202 (L2-01, SPEC 2 D-58 line, D-58a (5)): carol holds Item/Configure on {@code team}
     * natively (through the folder's own property, not an administrator); bob's CONFIGURE window on
     * {@code team} makes it guarded. carol's {@code config.xml} POST adding an entry for bob is undone:
     * no entry for bob, alice's entry stays, one GRANT_VIOLATION. Guard: before bob's window exists
     * the same POST by carol is kept and records nothing.
     */
    @Test
    public void t_gap_202_nativeConfigureWideningOnGuardedFolderIsUndone() throws Exception {
        Folder team = folderWithProperty("team", "alice", "carol-configure");
        Folder free = folderWithProperty("free", "alice", "carol-configure");
        assertTrue(nativelyConfigures("carol", team), "premise: carol configures team natively");

        // guard: the same widening on a folder no window ever touched is kept
        int before = violations().size();
        int freeCode = postConfigXml("carol", free, withEntries(free, "hudson.model.Item.Build", "bob"));
        assertTrue(freeCode < 400, "guard: carol's widening on an untouched folder is saved, got " + freeCode);
        assertTrue(folderGrants(folder("free"), Item.BUILD, "bob"), "guard: the widening on an untouched folder is kept");
        assertEquals(before, violations().size(), "guard: no GRANT_VIOLATION on an untouched folder");

        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CONFIGURE));
        int code = postConfigXml("carol", team, withEntries(team, "hudson.model.Item.Build", "bob"));
        assertEquals(403, code, "carol's widening of the guarded folder must answer 403 (D-48, D-58a (5))");
        Folder current = folder("team");
        assertFalse(folderMentions(current, "bob"), "carol's entry for bob on the guarded folder must be undone");
        assertTrue(folderGrants(current, Item.CONFIGURE, "alice"), "alice's entry must stay");
        assertEquals(before + 1, violations().size(), "the undone widening is one GRANT_VIOLATION: " + describe(violations()));
        assertEquals("carol", violations().get(violations().size() - 1).getUser(), "the GRANT_VIOLATION names carol");
    }

    /**
     * T-GAP-203 (L2-01, SPEC 2 D-58 line): on the guarded folder carol adds 25 widening entries in
     * one {@code config.xml} POST (25 distinct users with Item/Build). All are undone and exactly one
     * GRANT_VIOLATION is written for the save. Guard: alice's entry stays.
     */
    @Test
    public void t_gap_203_manyWideningEntriesAreAllUndoneWithOneRecord() throws Exception {
        Folder team = folderWithProperty("team", "alice", "carol-configure");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CONFIGURE));
        int before = violations().size();
        String[] sids = new String[25];
        for (int i = 0; i < sids.length; i++) {
            sids[i] = "wide" + i;
        }
        int code = postConfigXml("carol", team, withEntries(team, "hudson.model.Item.Build", sids));
        assertEquals(403, code, "the reverted widening must answer 403");
        Folder current = folder("team");
        for (String sid : sids) {
            assertFalse(folderMentions(current, sid), "every widening entry must be undone, " + sid + " is still there");
        }
        assertTrue(folderGrants(current, Item.CONFIGURE, "alice"), "guard: alice's entry stays");
        assertEquals(before + 1, violations().size(), "one save of 25 entries writes exactly one GRANT_VIOLATION: "
                + describe(violations()));
    }

    // ------------------------------------------------------------------ L2-02

    /**
     * T-GAP-204 (L2-02, D-35d (1), LIMITATIONS 35): dave holds Item/Configure natively on job
     * {@code a-job} (its own property) and a CONFIGURE window on job {@code b-job}. dave's
     * {@code config.xml} POST adding an authorization entry on {@code a-job} is kept and writes no
     * GRANT_VIOLATION; removing {@code a-job}'s authorization property is kept too. Guard: the same
     * entry added by dave on {@code b-job}, where his Configure comes only from the window, is
     * reverted and recorded.
     */
    @Test
    public void t_gap_204_nativeConfigureHolderWithAnUnrelatedWindowIsNotGuarded() throws Exception {
        strategy.add(jenkins.model.Jenkins.READ, PermissionEntry.user("dave"));
        strategy.add(Item.READ, PermissionEntry.user("dave"));
        strategy.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("dave"));
        FreeStyleProject a = j.createFreeStyleProject("a-job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("dave"));
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        a.addProperty(amp);
        FreeStyleProject b = j.createFreeStyleProject("b-job");
        AuthorizationMatrixProperty bp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        bp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        b.addProperty(bp);
        StrategyFixtures.grant("dave", "b-job", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(a, "dave", Item.CONFIGURE) && has(b, "dave", Item.CONFIGURE), "premise: dave configures both jobs");
        int before = violations().size();

        String close = "</hudson.security.AuthorizationMatrixProperty>";
        String widened = a.getConfigFile().asString().replace(close, "<permission>USER:hudson.model.Item.Build:carol</permission>" + close);
        assertEquals(200, postJobConfigXml("dave", "job/a-job/", widened), "dave's widening of a-job must be saved normally");
        assertTrue(jobGrants("a-job", Item.BUILD, "carol"), "the entry dave added on a-job must be kept");
        assertEquals(before, violations().size(), "a native Configure holder's change on an untouched job records nothing");

        String xml = j.jenkins.getItemByFullName("a-job", FreeStyleProject.class).getConfigFile().asString();
        int start = xml.indexOf("<hudson.security.AuthorizationMatrixProperty>");
        int end = xml.indexOf(close) + close.length();
        assertTrue(start > 0 && end > start, "fixture: a-job's property must be in its config.xml: " + xml);
        assertEquals(200, postJobConfigXml("dave", "job/a-job/", xml.substring(0, start) + xml.substring(end)),
                "dave's removal of a-job's property must be saved normally");
        assertEquals(null, j.jenkins.getItemByFullName("a-job", FreeStyleProject.class).getProperty(AuthorizationMatrixProperty.class),
                "the removal of a-job's authorization property must be kept");
        assertEquals(before, violations().size(), "removing the property of an untouched job records nothing");

        String onB = b.getConfigFile().asString().replace(close, "<permission>USER:hudson.model.Item.Build:carol</permission>" + close);
        assertEquals(403, postJobConfigXml("dave", "job/b-job/", onB), "guard: on b-job dave's Configure comes only from the window");
        assertFalse(jobGrants("b-job", Item.BUILD, "carol"), "guard: the entry on b-job is reverted");
        assertEquals(before + 1, violations().size(), "guard: the revert on b-job is recorded");
    }

    // ------------------------------------------------------------------ L2-03

    /**
     * T-GAP-205 (L2-03, SPEC 2 D-35c "a copied item", D-58 "authorization entries on an item created
     * inside a guarded folder"): bob holds only a CREATE window on folder {@code dest}. Part 1: the
     * folder {@code src-e} carries a folder authorization property (alice Item/Configure, and bob
     * Item/Configure so that he may read the source) and no children; bob copies it into {@code dest}:
     * {@code dest/copy-e} keeps no authorization entry of the source (in memory and on disk) and a
     * GRANT_VIOLATION names bob. Part 2 (the scenario as written): the folder {@code src-f} has the same
     * property and contains job {@code src-f/j} with alice's entry; bob copies it into {@code dest}.
     * Whatever the copy leaves at {@code dest/copy-f} (and the job inside it) keeps no authorization
     * entry of the source, in memory and on disk, and the removal is recorded as GRANT_VIOLATION.
     * Guard: the source folders and job keep their properties.
     */
    @Test
    public void t_gap_205_copiedFolderUnderCreateWindowKeepsNoAuthorizationEntries() throws Exception {
        folderWithProperty("src-e", "alice", "bob-configure");
        Folder src = folderWithProperty("src-f", "alice", "bob-configure");
        FreeStyleProject inner = src.createProject(FreeStyleProject.class, "j");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        inner.addProperty(amp);
        j.jenkins.createProject(Folder.class, "dest");
        StrategyFixtures.grant("bob", "dest", Arrays.asList(GrantAction.CREATE));

        // part 1: a folder without children
        int before = violations().size();
        Page empty = createItem("bob", "job/dest/", "name=copy-e&mode=copy&from=/src-e", null);
        Folder copyE = j.jenkins.getItemByFullName("dest/copy-e", Folder.class);
        assertNotNull(copyE, "fixture: bob's copy of the childless folder must have been created (HTTP "
                + empty.getWebResponse().getStatusCode() + "): " + UsabilityFixtures.excerpt(UsabilityFixtures.text(empty)));
        assertFalse(folderMentions(copyE, "alice") || folderMentions(copyE, "bob"),
                "the copied folder must keep no authorization entry of the source in memory");
        assertFalse(copyE.getConfigFile().asString().contains(":alice</permission>")
                        || copyE.getConfigFile().asString().contains(":bob</permission>"),
                "the copied folder must keep no authorization entry of the source on disk: " + copyE.getConfigFile().asString());
        List<ChangeRecord> afterEmpty = violations();
        assertEquals(before + 1, afterEmpty.size(), "the removal from the copied folder must be one GRANT_VIOLATION: "
                + describe(afterEmpty));
        assertEquals("bob", afterEmpty.get(afterEmpty.size() - 1).getUser(), "the GRANT_VIOLATION names bob");

        // part 2: the folder with a job inside, as the scenario describes it
        int beforeF = violations().size();
        Page answer = createItem("bob", "job/dest/", "name=copy-f&mode=copy&from=/src-f", null);
        String outcome = "HTTP " + answer.getWebResponse().getStatusCode() + ": " + UsabilityFixtures.excerpt(UsabilityFixtures.text(answer));
        Folder copy = j.jenkins.getItemByFullName("dest/copy-f", Folder.class);
        if (copy != null) {
            assertFalse(folderMentions(copy, "alice") || folderMentions(copy, "bob"),
                    "the folder left by the copy must keep no authorization entry of the source in memory (" + outcome + ")");
            String disk = copy.getConfigFile().asString();
            assertFalse(disk.contains(":alice</permission>") || disk.contains(":bob</permission>"),
                    "the folder left by the copy must keep no authorization entry of the source on disk (" + outcome + "): " + disk);
            FreeStyleProject copiedJob = j.jenkins.getItemByFullName("dest/copy-f/j", FreeStyleProject.class);
            if (copiedJob != null) {
                AuthorizationMatrixProperty jp = copiedJob.getProperty(AuthorizationMatrixProperty.class);
                assertTrue(jp == null || !mentions(jp.getGrantedPermissionEntries(), "alice"),
                        "the job inside the copy must keep no authorization entry of the source");
                assertFalse(copiedJob.getConfigFile().asString().contains(":alice</permission>"),
                        "the job inside the copy must keep no authorization entry of the source on disk");
            }
            assertTrue(violations().size() > beforeF, "the removal must be recorded as GRANT_VIOLATION (" + outcome + "): "
                    + describe(violations()));
        }
        assertTrue(folderMentions(folder("src-f"), "alice") && folderMentions(folder("src-e"), "alice"),
                "guard: the source folders keep their properties");
        AuthorizationMatrixProperty srcJob = j.jenkins.getItemByFullName("src-f/j", FreeStyleProject.class)
                .getProperty(AuthorizationMatrixProperty.class);
        assertTrue(srcJob != null && mentions(srcJob.getGrantedPermissionEntries(), "alice"), "guard: the source job keeps its property");
    }

    /**
     * T-GAP-206 (L2-03, SPEC 2 D-58 line, D-58a (2)): folder {@code guarded} is guarded by bob's
     * CONFIGURE window on it; carol holds Item/Create in {@code guarded} natively and Item/Configure on
     * the source folder {@code src-f} natively. carol copies {@code src-f} (whose job {@code j} carries
     * alice's entry) into {@code guarded}: the job inside the copy loses its authorization entries and
     * a GRANT_VIOLATION is recorded. Guard: the same copy into an untouched folder keeps the job's
     * entries and records nothing.
     */
    @Test
    public void t_gap_206_copyIntoGuardedFolderByNativeCreatorLosesEntries() throws Exception {
        Folder src = folderWithProperty("src-f", "alice", "carol-configure");
        FreeStyleProject inner = src.createProject(FreeStyleProject.class, "j");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        inner.addProperty(amp);
        folderWithProperty("guarded", "alice", "carol-create");
        folderWithProperty("open", "alice", "carol-create");

        // guard: copying into a folder no window touched keeps the entries
        int before = violations().size();
        createItem("carol", "job/open/", "name=copy-f&mode=copy&from=/src-f", null);
        FreeStyleProject openJob = j.jenkins.getItemByFullName("open/copy-f/j", FreeStyleProject.class);
        assertNotNull(openJob, "guard fixture: carol's copy into the untouched folder must carry the job");
        AuthorizationMatrixProperty openProp = openJob.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(openProp != null && mentions(openProp.getGrantedPermissionEntries(), "alice"),
                "guard: the copy into an untouched folder keeps the job's entries");
        assertEquals(before, violations().size(), "guard: no GRANT_VIOLATION outside guarded folders");

        StrategyFixtures.grant("bob", "guarded", Arrays.asList(GrantAction.CONFIGURE));
        createItem("carol", "job/guarded/", "name=copy-f&mode=copy&from=/src-f", null);
        FreeStyleProject copiedJob = j.jenkins.getItemByFullName("guarded/copy-f/j", FreeStyleProject.class);
        assertNotNull(copiedJob, "fixture: carol's copy into the guarded folder must carry the job");
        AuthorizationMatrixProperty jp = copiedJob.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(jp == null || !mentions(jp.getGrantedPermissionEntries(), "alice"),
                "the job inside the copy in a guarded folder must lose its authorization entries");
        assertFalse(copiedJob.getConfigFile().asString().contains(":alice</permission>"), "the removal must be on disk");
        assertTrue(violations().size() > before, "the removal must be recorded as GRANT_VIOLATION: " + describe(violations()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A folder whose own authorization property (set by the administrator, before any window) gives
     * {@code configurer} Item/Configure and, per {@code extra}, "bob-read" (bob Item/Read),
     * "bob-configure" (bob Item/Configure), "carol-configure" (carol Item/Configure) or "carol-create"
     * (carol Item/Create).
     */
    private Folder folderWithProperty(String name, String configurer, String extra) throws Exception {
        Folder f = j.jenkins.createProject(Folder.class, name);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<Permission, Set<String>>());
        fp.add(Item.CONFIGURE, PermissionEntry.user(configurer));
        switch (extra) {
            case "bob-read":
                fp.add(Item.READ, PermissionEntry.user("bob"));
                break;
            case "bob-configure":
                fp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
                break;
            case "carol-configure":
                fp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
                break;
            case "carol-create":
                fp.add(Item.CREATE, PermissionEntry.user("carol"));
                break;
            default:
                throw new IllegalArgumentException(extra);
        }
        f.addProperty(fp);
        assertTrue(folderGrants(f, Item.CONFIGURE, configurer), "fixture: the folder property must be in place");
        return f;
    }

    private Folder folder(String name) {
        return j.jenkins.getItemByFullName(name, Folder.class);
    }

    private static com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty folderProperty(Folder f) {
        return f.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
    }

    private static boolean folderGrants(Folder f, Permission p, String sid) {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp = folderProperty(f);
        if (fp == null) {
            return false;
        }
        Set<PermissionEntry> entries = fp.getGrantedPermissionEntries().get(p);
        return entries != null && entries.contains(PermissionEntry.user(sid));
    }

    private static boolean folderMentions(Folder f, String sid) {
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp = folderProperty(f);
        return fp != null && mentions(fp.getGrantedPermissionEntries(), sid);
    }

    private boolean jobGrants(String name, Permission p, String sid) {
        AuthorizationMatrixProperty amp = j.jenkins.getItemByFullName(name, FreeStyleProject.class)
                .getProperty(AuthorizationMatrixProperty.class);
        if (amp == null) {
            return false;
        }
        Set<PermissionEntry> entries = amp.getGrantedPermissionEntries().get(p);
        return entries != null && entries.contains(PermissionEntry.user(sid));
    }

    private static boolean mentions(Map<Permission, Set<PermissionEntry>> entries, String sid) {
        return entries.values().stream().flatMap(Set::stream).anyMatch(e -> sid.equals(e.getSid()));
    }

    /** Whether {@code user} holds Configure on {@code item} through the strategy alone (no window conferred yet). */
    private static boolean nativelyConfigures(String user, Item item) {
        return has(item, user, Item.CONFIGURE)
                && io.jenkins.plugins.batchcontrol.security.GrantService.get().listActive().stream()
                .noneMatch(g -> user.equals(g.getUser()));
    }

    /** The folder's config.xml with one entry {@code permissionId} added per sid to its folder authorization property. */
    private static String withEntries(Folder f, String permissionId, String... sids) throws Exception {
        String xml = f.getConfigFile().asString();
        String close = "</" + FOLDER_PROPERTY + ">";
        assertTrue(xml.contains(close), "fixture: the folder must carry its authorization property: " + xml);
        StringBuilder add = new StringBuilder();
        for (String sid : sids) {
            add.append("<permission>USER:").append(permissionId).append(':').append(sid).append("</permission>");
        }
        return xml.replace(close, add + close);
    }

    private HtmlPage configurePage(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        HtmlPage page = wc.goTo(path);
        assertEquals(200, page.getWebResponse().getStatusCode(), "fixture: " + user + " must reach " + path);
        return page;
    }

    private static void setDescription(HtmlForm form, String value) {
        for (String name : new String[] {"description", "_.description"}) {
            try {
                HtmlTextArea area = form.getTextAreaByName(name);
                area.setText(value);
                return;
            } catch (org.htmlunit.ElementNotFoundException next) {
                // try the other name
            }
        }
        throw new AssertionError("fixture: the configuration form must carry a description field");
    }

    /** The Configure checkbox in {@code sid}'s row of the matrix table (matrix-auth's form). */
    private static HtmlCheckBoxInput configureBoxOf(HtmlPage page, String sid) {
        List<HtmlCheckBoxInput> boxes = page.getByXPath("//*[@*[contains(., 'USER:" + sid + "') or . = '" + sid
                + "']]//input[@type='checkbox' and contains(@name, 'hudson.model.Item.Configure')]");
        assertEquals(1, boxes.size(), "fixture: the form must offer exactly one Configure box in " + sid + "'s matrix row");
        return boxes.get(0);
    }

    private int postConfigXml(String user, Folder f, String xml) throws Exception {
        return postJobConfigXml(user, f.getUrl(), xml);
    }

    private int postJobConfigXml(String user, String itemUrl, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(itemUrl + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        req.setRequestBody(xml);
        return wc.getPage(req).getWebResponse().getStatusCode();
    }

    private Page createItem(String user, String containerUrl, String query, String body) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req);
    }

    private static List<ChangeRecord> violations() {
        return StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
    }

    private static String describe(List<ChangeRecord> records) {
        StringBuilder out = new StringBuilder("[");
        for (ChangeRecord r : records) {
            out.append(r.getUser()).append(' ').append(r.getTarget()).append(' ').append(r.getDetail()).append("; ");
        }
        return out.append(']').toString();
    }
}
