package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 2, D-35b and D-35c: a user whose Configure comes only from a grant cannot turn it
 * into a permanent authorization entry, and a Create grant leaves no permanent entry on the
 * item it created. Matrix rows T-02-22 (PoC-5 row 14, job property through POST config.xml),
 * T-02-23 (its negative twin), T-02-24 (the folder property) and T-02-25 (PoC-5 row 15).
 *
 * <p>Every row ends by moving the clock past the window and asserting the permission is gone:
 * that is the guarantee D-35b/c exist for. The on-disk config.xml is checked as well, because a
 * restore that lives only in memory would come back at the next restart.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35b/c and docs/POC-RESULTS.md PoC-5 only
 * (no src/main knowledge).
 */
@WithJenkins
public class GrantSelfGrantGuardTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        StrategyFixtures.changeControlOn();
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    private void afterWindow() {
        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));
    }

    private static boolean mentions(Map<Permission, Set<PermissionEntry>> entries, String sid) {
        return entries.values().stream().flatMap(Set::stream).anyMatch(e -> sid.equals(e.getSid()));
    }

    private static boolean grants(Map<Permission, Set<PermissionEntry>> entries, Permission p, String sid) {
        Set<PermissionEntry> sids = entries.get(p);
        return sids != null && sids.contains(PermissionEntry.user(sid));
    }

    private int postConfigXml(String user, FreeStyleProject job, String xml) throws Exception {
        return postConfigXmlPage(user, job, xml).getWebResponse().getStatusCode();
    }

    private org.htmlunit.Page postConfigXmlPage(String user, FreeStyleProject job, String xml) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        WebRequest req = new WebRequest(wc.createCrumbedUrl(job.getUrl() + "config.xml"), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(xml);
        return wc.getPage(req);
    }

    private static String withExtraEntry(String xml, String permissionId, String sid) {
        String close = "</hudson.security.AuthorizationMatrixProperty>";
        assertTrue(xml.contains(close), "fixture: the job config must carry an AuthorizationMatrixProperty, got:\n" + xml);
        return xml.replace(close, "<permission>USER:" + permissionId + ":" + sid + "</permission>" + close);
    }

    private FreeStyleProject jobWithAliceProperty(String name) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject(name);
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);
        assertTrue(has(p, "alice", Item.CONFIGURE), "fixture: alice's per-job entry must be effective");
        return p;
    }

    /**
     * T-02-22 (D-35b, PoC-5 row 14): bob holds Item/Configure on the job only through a grant and
     * POSTs a config.xml that adds a permanent Item/Configure entry for himself to the job's
     * authorization property. The previous property is restored (alice's entry kept, no entry for
     * bob, in memory and on disk), a GRANT_VIOLATION record names bob, the job and the grant, and
     * after the window bob has no Configure — also after a reload from disk.
     */
    @Test
    public void t_02_22_grantOnlyConfigureCannotWriteJobAuthorizationEntry() throws Exception {
        FreeStyleProject p = jobWithAliceProperty("job");
        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob holds no native Configure");
        Grant grant = StrategyFixtures.grant("bob", "job", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(p, "bob", Item.CONFIGURE), "premise: the grant confers Configure on the job");
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(), "premise: no violation recorded yet");

        postConfigXml("bob", p, withExtraEntry(p.getConfigFile().asString(), "hudson.model.Item.Configure", "bob"));

        AuthorizationMatrixProperty after = p.getProperty(AuthorizationMatrixProperty.class);
        assertNotNull(after, "the previous property must be restored, not removed");
        assertTrue(grants(after.getGrantedPermissionEntries(), Item.CONFIGURE, "alice"), "the restored property must keep alice's entry");
        assertFalse(mentions(after.getGrantedPermissionEntries(), "bob"), "the restored property must carry no entry for bob");
        assertFalse(p.getConfigFile().asString().contains(":bob</permission>"),
                "the restore must be persisted: the job's config.xml on disk must not name bob");

        List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "exactly one GRANT_VIOLATION record must be written, got " + violations.size());
        ChangeRecord v = violations.get(0);
        assertEquals("bob", v.getUser(), "the record must name the user");
        assertEquals("job", v.getTarget(), "the record must name the item");
        assertNotNull(v.getDetail(), "the record must name the grant");
        assertTrue(v.getDetail().contains(grant.getId()), "the record's detail must name the grant " + grant.getId() + ", got: " + v.getDetail());
        assertNotNull(v.getAt());

        afterWindow();
        assertFalse(has(p, "bob", Item.CONFIGURE), "past the window bob must hold no Configure on the job");
        assertTrue(has(p, "alice", Item.CONFIGURE));
        j.jenkins.reload();
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
        assertFalse(has(reloaded, "bob", Item.CONFIGURE), "after a reload from disk bob must still hold no Configure");
        assertTrue(has(reloaded, "alice", Item.CONFIGURE));
    }

    /**
     * T-02-23 (negative twin of T-02-22, revised for D-58a (5)): bob's ordinary config change
     * inside the window is saved without a record. c1, whose Item/Configure is native but who is
     * not an administrator, adds an authorization entry for carol to the job while it is in the
     * active grant's scope: the save answers 403 with the D-48 message, the entry is reverted and
     * one GRANT_VIOLATION is recorded (note 177).
     */
    @Test
    public void t_02_23_ordinarySaveIsKeptAndNativeConfigureWideningIsReverted() throws Exception {
        FreeStyleProject p = jobWithAliceProperty("job");
        StrategyFixtures.grant("bob", "job", Arrays.asList(GrantAction.CONFIGURE));

        String xml = p.getConfigFile().asString();
        // A new job's config.xml may carry no <description> element at all: drop whatever form it
        // has and insert one right after the root element, so the payload really edits the job.
        String changed = xml.replaceAll("<description/>|<description>[^<]*</description>", "")
                .replaceFirst("<project(\\s[^>]*)?>", "$0<description>edited-in-window</description>");
        assertTrue(changed.contains("<description>edited-in-window</description>") && !changed.equals(xml),
                "fixture: the payload must differ from the saved config, got:\n" + changed);
        assertEquals(200, postConfigXml("bob", p, changed), "bob's ordinary save inside the window must succeed");
        assertEquals("edited-in-window", j.jenkins.getItemByFullName("job", FreeStyleProject.class).getDescription());

        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(),
                "no GRANT_VIOLATION record may be written for bob's ordinary save");

        assertFalse(has(p, "carol", Item.CONFIGURE), "premise: carol has no Configure on the job");
        org.htmlunit.Page answer = postConfigXmlPage("c1", p,
                withExtraEntry(p.getConfigFile().asString(), "hudson.model.Item.Configure", "carol"));
        assertEquals(403, answer.getWebResponse().getStatusCode(), "on a job in an active grant's scope a native Configure"
                + " holder who is not an administrator is not exempt: the widening answers 403 (D-58a (5))");
        GrantSelfGrantFeedbackTest.assertGuardFeedback("c1's widening", UsabilityFixtures.text(answer), "job");
        FreeStyleProject current = j.jenkins.getItemByFullName("job", FreeStyleProject.class);
        assertFalse(has(current, "carol", Item.CONFIGURE), "c1's widening must be reverted");
        assertEquals(1, StrategyFixtures.records(ChangeType.GRANT_VIOLATION).size(),
                "c1's reverted widening must be recorded as one GRANT_VIOLATION");
    }

    /**
     * T-02-24 (D-35b, folder property), rewritten for D-71: bob holds Configure on the folder
     * {@code team/sub} only through a CONFIGURE window on that folder (before D-71 a FOLDER window
     * on {@code team} reached it; since D-71 a window names the folder itself) and adds a folder
     * authorization property to {@code team/sub} that gives himself Item/Configure. The change is
     * reverted (no entry for bob), a GRANT_VIOLATION record names bob and {@code team/sub}, and
     * after the window bob has no Configure on the job inside it. The window never reached that
     * job (D-71), which the row asserts as well.
     */
    @Test
    public void t_02_24_grantOnlyConfigureCannotWriteFolderAuthorizationEntry() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        Folder sub = team.createProject(Folder.class, "sub");
        FreeStyleProject job = sub.createProject(FreeStyleProject.class, "job");
        Grant grant = StrategyFixtures.grant("bob", "team/sub", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(sub, "bob", Item.CONFIGURE), "premise: the window on team/sub confers Configure on team/sub");
        assertFalse(has(job, "bob", Item.CONFIGURE), "D-71: the window on the folder does not reach the job inside it");

        StrategyFixtures.as("bob", () -> {
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                    new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                            new HashMap<Permission, Set<String>>());
            fp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
            sub.addProperty(fp);
            return null;
        });

        Folder current = j.jenkins.getItemByFullName("team/sub", Folder.class);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                current.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        assertTrue(fp == null || !mentions(fp.getGrantedPermissionEntries(), "bob"),
                "the folder's previous authorization property (none) must be restored");
        assertFalse(current.getConfigFile().asString().contains(":bob</permission>"), "the restore must be persisted on disk");

        List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "exactly one GRANT_VIOLATION record must be written");
        assertEquals("bob", violations.get(0).getUser());
        assertEquals("team/sub", violations.get(0).getTarget());
        assertTrue(violations.get(0).getDetail().contains(grant.getId()), "the record must name the grant");

        afterWindow();
        assertFalse(has(job, "bob", Item.CONFIGURE), "past the window bob must hold no Configure inside team/sub");
        assertFalse(has(current, "bob", Item.CONFIGURE));
    }

    /**
     * T-02-25 (D-35c, PoC-5 row 15): bob holds only a CREATE window on the folder {@code team} and
     * creates {@code team/new}. During the window he may configure the item he created (and not
     * a pre-existing one); matrix-auth's creator listener therefore adds nothing, so past the
     * window bob holds no Configure on {@code team/new}, no authorization entry names him (in
     * memory, on disk, after a reload), and no GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_02_25_createGrantLeavesNoPermanentEntry() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject old = team.createProject(FreeStyleProject.class, "old");
        assertFalse(has(team, "bob", Item.CREATE), "premise: bob cannot create before the grant");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));
        assertTrue(has(team, "bob", Item.CREATE), "premise: the CREATE grant confers Item/Create on the folder");

        FreeStyleProject created = StrategyFixtures.as("bob", () -> team.createProject(FreeStyleProject.class, "new"));
        assertTrue(has(created, "bob", Item.CONFIGURE), "D-35c: during the window the Create grant confers Configure on the item bob created");
        assertFalse(has(old, "bob", Item.CONFIGURE), "guard: a Create-only grant must not confer Configure on an item bob did not create");

        afterWindow();
        assertFalse(has(created, "bob", Item.CONFIGURE), "past the window bob must hold no Configure on the item he created");
        AuthorizationMatrixProperty amp = created.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || !mentions(amp.getGrantedPermissionEntries(), "bob"),
                "no permanent authorization entry may name bob on the created item");
        assertFalse(created.getConfigFile().asString().contains(":bob</permission>"), "no entry for bob may be on disk");
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(), "creating an item is not a violation");

        j.jenkins.reload();
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("team/new", FreeStyleProject.class);
        assertNotNull(reloaded);
        assertFalse(has(reloaded, "bob", Item.CONFIGURE), "after a reload bob must still hold no Configure on team/new");
    }

    private static final String PAYLOAD_PROPERTY = "<hudson.security.AuthorizationMatrixProperty>"
            + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
            + "<permission>USER:hudson.model.Item.Configure:bob</permission>"
            + "<permission>USER:hudson.model.Item.Configure:carol</permission>"
            + "</hudson.security.AuthorizationMatrixProperty>";

    private int postCreateItem(String user, String containerUrl, String query, String body) throws Exception {
        return postCreateItemPage(user, containerUrl, query, body).getWebResponse().getStatusCode();
    }

    private org.htmlunit.Page postCreateItemPage(String user, String containerUrl, String query, String body)
            throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        java.net.URL url = new java.net.URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm() + "&" + query);
        WebRequest req = new WebRequest(url, HttpMethod.POST);
        if (body != null) {
            req.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
            req.setRequestBody(body);
        }
        return wc.getPage(req);
    }

    /** The payload's property is gone: no entry names bob or carol (in memory and on disk), and past the window neither configures. */
    private void assertPayloadPropertyRemoved(String fullName) throws Exception {
        FreeStyleProject created = j.jenkins.getItemByFullName(fullName, FreeStyleProject.class);
        assertNotNull(created, "premise: " + fullName + " must have been created");
        AuthorizationMatrixProperty amp = created.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || (!mentions(amp.getGrantedPermissionEntries(), "bob") && !mentions(amp.getGrantedPermissionEntries(), "carol")),
                "the payload's authorization property must be removed from " + fullName);
        String disk = created.getConfigFile().asString();
        assertFalse(disk.contains(":bob</permission>") || disk.contains(":carol</permission>"),
                "the removal must be persisted: " + fullName + "'s config.xml must name neither bob nor carol");
        assertFalse(has(created, "carol", Item.CONFIGURE), "carol must gain no Configure from the payload");

        List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "exactly one GRANT_VIOLATION record must be written, got " + violations.size());
        assertEquals("bob", violations.get(0).getUser(), "the record must name the creator");
        assertEquals(fullName, violations.get(0).getTarget(), "the record must name the created item");

        afterWindow();
        assertFalse(has(created, "bob", Item.CONFIGURE), "past the window bob must hold no Configure on " + fullName);
    }

    /** Adds a whole AuthorizationMatrixProperty giving {@code sid} Item/Configure to a job config that has none. */
    private static String withNewPropertyFor(String xml, String sid) {
        String prop = "<hudson.security.AuthorizationMatrixProperty>"
                + "<inheritanceStrategy class=\"org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy\"/>"
                + "<permission>USER:hudson.model.Item.Configure:" + sid + "</permission>"
                + "</hudson.security.AuthorizationMatrixProperty>";
        assertFalse(xml.contains("<hudson.security.AuthorizationMatrixProperty"), "fixture: the job must start without a property");
        String out;
        if (xml.contains("<properties/>")) {
            out = xml.replace("<properties/>", "<properties>" + prop + "</properties>");
        } else if (xml.contains("<properties>")) {
            out = xml.replaceFirst("<properties>", "<properties>" + java.util.regex.Matcher.quoteReplacement(prop));
        } else {
            out = xml.replaceFirst("<project(\\s[^>]*)?>", "$0<properties>" + java.util.regex.Matcher.quoteReplacement(prop) + "</properties>");
        }
        assertTrue(out.contains(":" + sid + "</permission>"), "fixture: the payload must carry the property, got:\n" + out);
        return out;
    }

    /**
     * T-02-38 (security-05 S-01, D-35d(1)), rewritten for D-71: carol holds a CONFIGURE window on
     * the folder {@code S}. Before D-71 it reached {@code S/F/j} by inheritance and the row
     * asserted that the guard still caught the authorization entry she added there; D-71
     * withdraws that reach, so the row now asserts that her POST of {@code S/F/j/config.xml} with
     * a property for herself is refused (403) and leaves no entry (note 260). S-01's guard half (a
     * Configure that comes only from a window is decided through the parent strategy, not by a
     * name lookup) is kept on the window's own item: a folder authorization property carol adds
     * to {@code S} is reverted, and one GRANT_VIOLATION names carol, {@code S} and the grant. Past
     * the window carol has no Configure on either.
     */
    @Test
    public void t_02_38_inheritedFolderGrantCannotWriteAuthorizationEntry() throws Exception {
        Folder s = j.jenkins.createProject(Folder.class, "S");
        Folder f = s.createProject(Folder.class, "F");
        FreeStyleProject job = f.createProject(FreeStyleProject.class, "j");
        assertFalse(has(job, "carol", Item.CONFIGURE), "premise: carol holds no native Configure");
        Grant grant = StrategyFixtures.grant("carol", "S", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(s, "carol", Item.CONFIGURE), "premise: the window confers Configure on S itself");
        assertFalse(has(job, "carol", Item.CONFIGURE), "D-71: the window on S must not reach S/F/j");

        assertEquals(403, postConfigXml("carol", job, withNewPropertyFor(job.getConfigFile().asString(), "carol")),
                "D-71: carol's save of S/F/j must be refused, the window on S does not cover it");
        FreeStyleProject current = j.jenkins.getItemByFullName("S/F/j", FreeStyleProject.class);
        AuthorizationMatrixProperty amp = current.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || !mentions(amp.getGrantedPermissionEntries(), "carol"),
                "the refused save must leave no authorization entry for carol");
        assertFalse(current.getConfigFile().asString().contains(":carol</permission>"), "nothing for carol on disk");
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(), "a refused save changes nothing to revert");

        StrategyFixtures.as("carol", () -> {
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                    new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(
                            new HashMap<Permission, Set<String>>());
            fp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
            s.addProperty(fp);
            return null;
        });
        Folder sNow = j.jenkins.getItemByFullName("S", Folder.class);
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty fp =
                sNow.getProperties().get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        assertTrue(fp == null || !mentions(fp.getGrantedPermissionEntries(), "carol"),
                "the authorization property carol added to the window's folder must be reverted");
        assertFalse(sNow.getConfigFile().asString().contains(":carol</permission>"), "the revert must be persisted on disk");
        List<ChangeRecord> violations = StrategyFixtures.records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "exactly one GRANT_VIOLATION record must be written, got " + violations.size());
        assertEquals("carol", violations.get(0).getUser());
        assertEquals("S", violations.get(0).getTarget());
        assertTrue(violations.get(0).getDetail().contains(grant.getId()), "the record must name the grant");

        afterWindow();
        assertFalse(has(current, "carol", Item.CONFIGURE), "past the window carol must hold no Configure on S/F/j");
        assertFalse(has(sNow, "carol", Item.CONFIGURE), "past the window carol must hold no Configure on S");
    }

    /**
     * T-02-39 (security-05 S-01, D-35d(1)), rewritten for D-71: bob holds only a CREATE window on
     * {@code S} and creates the folder {@code S/F} directly inside it; the administrator creates
     * {@code S/F/j} inside that. D-35c confers Configure on exactly what bob created: {@code S/F}
     * yes, {@code S/F/j} no. bob's POST of {@code S/F/j/config.xml} adding a property for himself
     * leaves no entry. Since D-71 the window's CREATE applies directly inside {@code S} only, so
     * bob can no longer create {@code S/F/mine} inside the folder he created (the former positive
     * twin, now asserted refused, note 260); the positive twin moves to {@code S/mine}, created
     * directly in {@code S}: bob may configure it, but a property he adds to it is reverted and
     * recorded (Configure there comes only from the window). Past the window bob has Configure on
     * none of them.
     */
    @Test
    public void t_02_39_createGrantConfigureDoesNotReachOthersChildren() throws Exception {
        Folder s = j.jenkins.createProject(Folder.class, "S");
        StrategyFixtures.grant("bob", "S", Arrays.asList(GrantAction.CREATE));
        Folder f = StrategyFixtures.as("bob", () -> s.createProject(Folder.class, "F"));
        assertTrue(has(f, "bob", Item.CONFIGURE), "premise (D-35c): bob configures the folder he created");
        FreeStyleProject others = f.createProject(FreeStyleProject.class, "j"); // created by the administrator (SYSTEM)

        assertFalse(has(others, "bob", Item.CONFIGURE),
                "D-35d: Create-grant Configure must not reach a child of S/F that bob did not create");
        postConfigXml("bob", others, withNewPropertyFor(others.getConfigFile().asString(), "bob"));
        FreeStyleProject othersNow = j.jenkins.getItemByFullName("S/F/j", FreeStyleProject.class);
        AuthorizationMatrixProperty amp = othersNow.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(amp == null || !mentions(amp.getGrantedPermissionEntries(), "bob"), "bob must not be able to write S/F/j's property");

        assertFalse(has(f, "bob", Item.CREATE), "D-71: CREATE never applies inside a nested folder, not even one bob created");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> StrategyFixtures.as("bob", () -> f.createProject(FreeStyleProject.class, "mine")),
                "D-71: bob must not create inside S/F");
        assertTrue(f.getItem("mine") == null, "D-71: nothing may be created inside S/F");

        FreeStyleProject mine = StrategyFixtures.as("bob", () -> s.createProject(FreeStyleProject.class, "mine"));
        assertTrue(has(mine, "bob", Item.CONFIGURE), "guard: bob configures the job he created directly in S (D-35c)");
        postConfigXml("bob", mine, withNewPropertyFor(mine.getConfigFile().asString(), "bob"));
        FreeStyleProject mineNow = j.jenkins.getItemByFullName("S/mine", FreeStyleProject.class);
        AuthorizationMatrixProperty mineAmp = mineNow.getProperty(AuthorizationMatrixProperty.class);
        assertTrue(mineAmp == null || !mentions(mineAmp.getGrantedPermissionEntries(), "bob"),
                "a property bob adds to his own created job must be reverted (grant-only Configure)");
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).stream().anyMatch(
                rec -> "bob".equals(rec.getUser()) && "S/mine".equals(rec.getTarget())),
                "the revert on S/mine must be recorded as GRANT_VIOLATION");

        afterWindow();
        assertFalse(has(othersNow, "bob", Item.CONFIGURE));
        assertFalse(has(mineNow, "bob", Item.CONFIGURE));
        assertFalse(has(j.jenkins.getItemByFullName("S/F", Folder.class), "bob", Item.CONFIGURE));
    }

    /**
     * T-02-40 (security-05 S-05, D-35d(4)): job {@code j}'s property gives carol Item/Build (and
     * alice Configure). The administrator removes carol's entry on disk and reloads the item
     * (POST {@code job/j/reload}). bob, with a Configure grant, then saves a description-only
     * change. carol's entry must not come back and no GRANT_VIOLATION is recorded.
     */
    @Test
    public void t_02_40_reloadFromDiskDoesNotMakeGuardRestoreStaleProperty() throws Exception {
        FreeStyleProject p = jobWithAliceProperty("j");
        p.getProperty(AuthorizationMatrixProperty.class).add(Item.BUILD, PermissionEntry.user("carol"));
        p.save();
        assertTrue(has(p, "carol", Item.BUILD), "premise: carol builds through the job property");
        StrategyFixtures.grant("bob", "j", Arrays.asList(GrantAction.CONFIGURE));

        java.nio.file.Path file = p.getConfigFile().getFile().toPath();
        String disk = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        String line = "<permission>USER:hudson.model.Item.Build:carol</permission>";
        assertTrue(disk.contains(line), "fixture: carol's entry must be on disk, got:\n" + disk);
        java.nio.file.Files.writeString(file, disk.replace(line, ""), java.nio.charset.StandardCharsets.UTF_8);
        JenkinsRule.WebClient admin = j.createWebClient().login("admin");
        admin.getPage(new WebRequest(admin.createCrumbedUrl(p.getUrl() + "reload"), HttpMethod.POST));
        FreeStyleProject reloaded = j.jenkins.getItemByFullName("j", FreeStyleProject.class);
        assertFalse(has(reloaded, "carol", Item.BUILD), "premise: the reload from disk removed carol's entry");

        String xml = reloaded.getConfigFile().asString();
        String changed = xml.replaceAll("<description/>|<description>[^<]*</description>", "")
                .replaceFirst("<project(\\s[^>]*)?>", "$0<description>after-reload</description>");
        assertEquals(200, postConfigXml("bob", reloaded, changed), "bob's description-only save must succeed");

        FreeStyleProject now = j.jenkins.getItemByFullName("j", FreeStyleProject.class);
        assertEquals("after-reload", now.getDescription(), "premise: bob's save took effect");
        assertFalse(has(now, "carol", Item.BUILD), "the guard must not restore the pre-reload entry for carol");
        assertFalse(now.getConfigFile().asString().contains(":carol</permission>"), "carol's entry must not return on disk");
        assertTrue(has(now, "alice", Item.CONFIGURE), "the rest of the property is untouched");
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(),
                "a description-only save after a reload is not a violation");
    }

    /**
     * T-02-34 (D-35c, creation payload): bob holds only a CREATE window on the folder {@code team} and
     * POSTs {@code job/team/createItem} with a config.xml carrying an AuthorizationMatrixProperty
     * that gives himself and carol Item/Configure. The item is created, the payload's property is
     * removed (memory and disk) and one GRANT_VIOLATION names bob and {@code team/new}; past the
     * window bob holds no Configure. Guard: the same POST without the property writes no record
     * (T-02-25 covers the listener side).
     */
    @Test
    public void t_02_34_createItemPayloadCannotCarryAuthorizationProperty() throws Exception {
        j.jenkins.createProject(Folder.class, "team");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));

        String plain = "<?xml version='1.1' encoding='UTF-8'?><project><properties/><builders/><publishers/><buildWrappers/></project>";
        int guard = postCreateItem("bob", "job/team/", "name=clean", plain);
        assertTrue(guard < 400, "premise: the Create grant lets bob create through createItem, got HTTP " + guard);
        assertTrue(StrategyFixtures.records(ChangeType.GRANT_VIOLATION).isEmpty(), "guard: a payload without a property is no violation");

        postCreateItem("bob", "job/team/", "name=new", plain.replace("<properties/>", "<properties>" + PAYLOAD_PROPERTY + "</properties>"));
        assertPayloadPropertyRemoved("team/new");
    }

    /**
     * T-02-35 (D-35c, copied item): the source {@code team/src} carries an authorization property
     * (bob and carol Item/Configure, set by the administrator). bob, with only a CREATE window
     * on the folder {@code team}, copies it through {@code createItem?mode=copy&from=src}. The copy carries no
     * authorization property of the source, one GRANT_VIOLATION names bob and {@code team/copy},
     * and past the window bob holds no Configure on the copy. Guard: the source keeps its property.
     */
    @Test
    public void t_02_35_copiedItemCannotCarryAuthorizationProperty() throws Exception {
        Folder team = j.jenkins.createProject(Folder.class, "team");
        FreeStyleProject src = team.createProject(FreeStyleProject.class, "src");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("bob"));
        amp.add(Item.CONFIGURE, PermissionEntry.user("carol"));
        src.addProperty(amp);
        assertTrue(has(src, "bob", Item.EXTENDED_READ), "premise: bob may read the source's configuration");
        StrategyFixtures.grant("bob", "team", Arrays.asList(GrantAction.CREATE));

        org.htmlunit.Page answer = postCreateItemPage("bob", "job/team/", "name=copy&mode=copy&from=src", null);
        // D-48 ruling: the copy is made, but its stripped authorization property is reported to
        // bob with a 403 and the D-48 message (note 153)
        assertEquals(403, answer.getWebResponse().getStatusCode(), "a copy whose authorization property the guard"
                + " stripped must answer 403 (D-48), got HTTP " + answer.getWebResponse().getStatusCode());
        GrantSelfGrantFeedbackTest.assertGuardFeedback("copy", UsabilityFixtures.text(answer), "team/copy", "team » copy");
        assertPayloadPropertyRemoved("team/copy");
        assertTrue(has(src, "carol", Item.CONFIGURE), "guard: the source keeps its own property");
    }
}
