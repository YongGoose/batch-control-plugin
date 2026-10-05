package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.AbstractItem;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.client;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.decideGrant;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.submitGrantOk;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.WINDOW_MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC item 8 (D-71: "CREATE applies only to a modifiable item group ... and allows creating items
 * directly inside it only ...; the D-40 name restriction applies unchanged, and the D-35c
 * Configure covers the items the holder created through the window"; "DELETE applies only to a
 * job"; the D-40 line: "an attempt with another name is refused with HTTP 4xx, leaves no item, and
 * is recorded as GRANT_VIOLATION"; expiry "from the first permission check, no timer dependency")
 * and SPEC item 2 (D-35a: with the Batch Control role-strategy strategy, "pattern-based Create and
 * the role naming strategy work"), under the Batch Control role strategy. The matrix-strategy
 * twins are T-08-102/103/109/112. Coverage inventory G-H5; matrix rows T-08-165 .. T-08-168
 * (note 269).
 *
 * <p>Roles: global {@code admin} (Administer: admin), {@code reader} (Overall/Read, Item/Read: u1,
 * a1), {@code requester} (RequestGrant: u1), {@code approver} (Approve: a1); no item role, so u1
 * holds no Create, Configure or Delete of their own. Items: folder {@code team} with the jobs
 * {@code team/old} and {@code team/keep} and the nested folder {@code team/sub}; the top-level job
 * {@code outside}. Windows are requested through the form contract and approved by a1. The
 * plugin clock is fixed at T0 so expiry is reached by moving the clock (no sleep).
 *
 * <p>Written from docs/SPEC.md items 2 and 8, docs/DECISIONS.md D-35a/c, D-40, D-59b and D-71 and
 * docs/TEST-MATRIX.md only (no src/main knowledge).
 */
@WithJenkins
public class RoleStrategyWindowActionsTest {

    private static final String RESTRICTION = "/app-[0-9]+/";

    private JenkinsRule j;
    private Folder team;
    private FreeStyleProject old;
    private FreeStyleProject keep;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "fixture: the Batch Control role strategy must be installed");
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        team = j.jenkins.createProject(Folder.class, "team");
        team.setDescription("base");
        old = team.createProject(FreeStyleProject.class, "old");
        keep = team.createProject(FreeStyleProject.class, "keep");
        keep.setDescription("base");
        team.createProject(Folder.class, "sub");
        j.createFreeStyleProject("outside");
        for (Item item : new Item[] {team, old, keep}) {
            assertFalse(can("u1", item, Item.CONFIGURE), "fixture: u1 holds no Configure of their own on " + item.getFullName());
            assertFalse(can("u1", item, Item.DELETE), "fixture: u1 holds no Delete of their own on " + item.getFullName());
        }
        assertFalse(can("u1", team, Item.CREATE), "fixture: u1 holds no Create of their own in team");
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    /**
     * T-08-165 (G-H5): with the default naming strategy, u1's CREATE window on {@code team}
     * restricted to {@code /app-[0-9]+/} creates {@code team/app-1}, and u1 then saves its
     * configuration (D-35c) but cannot delete it; {@code team/other} is refused with 4xx, leaves
     * no item and is recorded once as GRANT_VIOLATION naming u1 and the name; a matching name in
     * the nested folder {@code team/sub} and at the root is refused; nothing outside changes
     * (u1 saves neither {@code team} nor {@code team/keep}).
     */
    @Test
    public void t_08_165_restrictedCreateWindowUnderTheRoleStrategy() throws Exception {
        openWindow("team", List.of("CREATE"), RESTRICTION);

        int created = createItem("u1", team.getUrl(), "app-1");
        assertTrue(created < 400, "a matching name directly in team must be created, got HTTP " + created);
        FreeStyleProject app1 = j.jenkins.getItemByFullName("team/app-1", FreeStyleProject.class);
        assertNotNull(app1, "team/app-1 must exist");
        assertEquals(200, postConfig("u1", app1, "changed-by-u1"), "D-35c: u1 must save the configuration of the job they created");
        assertEquals("changed-by-u1", reload(app1).getDescription());
        assertFalse(can("u1", app1, Item.DELETE), "D-35c confers no Delete on the created job");

        int violationsBefore = violations("other").size();
        int other = createItem("u1", team.getUrl(), "other");
        assertTrue(other >= 400 && other < 500, "a name outside the restriction must be refused with 4xx, got HTTP " + other);
        assertNull(j.jenkins.getItemByFullName("team/other"), "the refused name must leave no item");
        assertEquals(violationsBefore + 1, violations("other").size(), "the refused creation must be recorded once as GRANT_VIOLATION"
                + " naming u1 and 'other': " + describe(violations("other")));

        int nested = createItem("u1", "job/team/job/sub/", "app-2");
        assertTrue(nested >= 400 && nested < 500, "creating inside team/sub must be refused, got HTTP " + nested);
        assertNull(j.jenkins.getItemByFullName("team/sub/app-2"));
        int root = createItem("u1", "", "app-3");
        assertTrue(root >= 400 && root < 500, "creating at the root must be refused, got HTTP " + root);
        assertNull(j.jenkins.getItemByFullName("app-3"));

        assertEquals(403, postConfig("u1", keep, "changed-keep"), "the window confers nothing on team/keep");
        assertEquals("base", reload(keep).getDescription());
        assertEquals(403, postConfig("u1", team, "changed-team"), "a CREATE window confers no Configure on its folder");
        assertEquals("base", reload(team).getDescription());
    }

    /**
     * T-08-166 (G-H5): u1's DELETE window on the job {@code team/old} deletes it; the sibling
     * {@code team/keep}, the folder {@code team} and the job {@code outside} are refused with 4xx
     * and still exist.
     */
    @Test
    public void t_08_166_deleteWindowUnderTheRoleStrategy() throws Exception {
        openWindow("team/old", List.of("DELETE"), null);

        for (Item other : new Item[] {keep, team, j.jenkins.getItemByFullName("outside")}) {
            int code = postDelete("u1", other);
            assertTrue(code >= 400 && code < 500, "deleting " + other.getFullName() + " must be refused, got HTTP " + code);
            assertNotNull(j.jenkins.getItemByFullName(other.getFullName()), other.getFullName() + " must still exist");
        }
        int deleted = postDelete("u1", old);
        assertTrue(deleted < 400, "the DELETE window must delete team/old, got HTTP " + deleted);
        assertNull(j.jenkins.getItemByFullName("team/old"), "team/old must be deleted");
    }

    /**
     * T-08-167 (G-H5, expiry): u1 holds the restricted CREATE window on {@code team} and a DELETE
     * window on {@code team/old} and creates {@code team/app-1}. Once the plugin clock is past the
     * windows' expiry: creating {@code team/app-2} is refused (no item), saving
     * {@code team/app-1} answers 403 (the D-35c Configure ended with the window) and deleting
     * {@code team/old} is refused (it still exists).
     */
    @Test
    public void t_08_167_roleStrategyWindowsStopConferringAtExpiry() throws Exception {
        openWindow("team", List.of("CREATE"), RESTRICTION);
        openWindow("team/old", List.of("DELETE"), null);
        assertTrue(createItem("u1", team.getUrl(), "app-1") < 400, "premise: u1 creates team/app-1 during the window");
        FreeStyleProject app1 = j.jenkins.getItemByFullName("team/app-1", FreeStyleProject.class);
        assertNotNull(app1);
        assertEquals(200, postConfig("u1", app1, "during-window"), "premise: u1 configures team/app-1 during the window");

        BatchClock.setForTest(Clock.fixed(T0.plus(Duration.ofMinutes(WINDOW_MINUTES + 1)), ZoneOffset.UTC));

        int create = createItem("u1", team.getUrl(), "app-2");
        assertTrue(create >= 400 && create < 500, "after the expiry creating team/app-2 must be refused, got HTTP " + create);
        assertNull(j.jenkins.getItemByFullName("team/app-2"));
        assertEquals(403, postConfig("u1", app1, "after-expiry"), "after the expiry the D-35c Configure must have ended");
        assertEquals("during-window", reload(app1).getDescription());
        int delete = postDelete("u1", old);
        assertTrue(delete >= 400 && delete < 500, "after the expiry deleting team/old must be refused, got HTTP " + delete);
        assertNotNull(j.jenkins.getItemByFullName("team/old"), "team/old must still exist");
    }

    /**
     * T-08-168 (G-H5, role naming strategy): with role-strategy's {@code RoleBasedProjectNamingStrategy}
     * installed, u1's restricted CREATE window still refuses {@code team/other} (4xx, no item, one
     * GRANT_VIOLATION naming u1 and the name), and the window does not get past the naming
     * strategy either: u1 holds no role whose pattern allows {@code team/app-2}, so that creation is
     * refused (4xx, no item); a window adds a permission, it does not switch off the installed
     * naming strategy (SPEC item 2; D-59b applies the same rule to moves). The DELETE window on
     * {@code team/old} still deletes it. Guard: the administrator creates {@code team/app-9} under
     * the same naming strategy.
     */
    @Test
    public void t_08_168_windowsUnderTheRoleNamingStrategy() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(false));
        assertTrue(j.jenkins.getProjectNamingStrategy() instanceof RoleBasedProjectNamingStrategy, "fixture: the role naming strategy is installed");
        openWindow("team", List.of("CREATE"), RESTRICTION);
        openWindow("team/old", List.of("DELETE"), null);

        int violationsBefore = violations("other").size();
        int other = createItem("u1", team.getUrl(), "other");
        assertTrue(other >= 400 && other < 500, "a name outside the restriction must be refused with 4xx, got HTTP " + other);
        assertNull(j.jenkins.getItemByFullName("team/other"));
        assertEquals(violationsBefore + 1, violations("other").size(), "the restriction is enforced and recorded whatever the naming"
                + " strategy: " + describe(violations("other")));

        int matching = createItem("u1", team.getUrl(), "app-2");
        assertTrue(matching >= 400 && matching < 500, "the role naming strategy must still refuse a name no role of u1 allows,"
                + " window or not, got HTTP " + matching);
        assertNull(j.jenkins.getItemByFullName("team/app-2"), "the refused creation must leave no item");

        int deleted = postDelete("u1", old);
        assertTrue(deleted < 400, "the DELETE window must still delete team/old, got HTTP " + deleted);
        assertNull(j.jenkins.getItemByFullName("team/old"));

        int byAdmin = createItem("admin", team.getUrl(), "app-9");
        assertTrue(byAdmin < 400, "guard: the administrator creates under the same naming strategy, got HTTP " + byAdmin);
        assertNotNull(j.jenkins.getItemByFullName("team/app-9"));
    }

    // ---------------------------------------------------------------- helpers

    private void openWindow(String fullName, List<String> actions, String pattern) throws Exception {
        long before = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        String id = submitGrantOk(j, "u1", fullName, actions, WINDOW_MINUTES, "maintenance of " + fullName, pattern, "a1");
        assertSuccess(decideGrant(j, "a1", id, "approve", "ok"), "fixture: approval by a1");
        long after = GrantService.get().listActive().stream().filter(g -> "u1".equals(g.getUser())).count();
        assertEquals(before + 1, after, "fixture: u1 must hold one more active window");
    }

    private static boolean can(String user, Item item, hudson.security.Permission p) {
        return item.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }

    private int createItem(String userId, String containerUrl, String name) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        URL url = new URL(wc.createCrumbedUrl(containerUrl + "createItem").toExternalForm()
                + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(url, HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(ItemScopeTest.MINIMAL_JOB_XML);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private int postDelete(String userId, Item item) throws Exception {
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(item.getUrl() + "doDelete"), HttpMethod.POST);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    /** POSTs the item's config.xml with its description replaced by {@code description}; returns the status. */
    private int postConfig(String userId, AbstractItem target, String description) throws Exception {
        String xml = target.getConfigFile().asString();
        String changed = xml.contains("<description>")
                ? xml.replaceFirst("(?s)<description>.*?</description>", "<description>" + description + "</description>")
                : xml.contains("<description/>")
                        ? xml.replace("<description/>", "<description>" + description + "</description>")
                        : xml.replaceFirst("(<project[^>]*>|<com\\.cloudbees\\.hudson\\.plugins\\.folder\\.Folder[^>]*>)",
                                "$1<description>" + description + "</description>");
        assertTrue(changed.contains(description), "fixture: the description of " + target.getFullName() + " must be replaceable");
        JenkinsRule.WebClient wc = client(j, userId);
        WebRequest request = new WebRequest(wc.createCrumbedUrl(target.getUrl() + "config.xml"), HttpMethod.POST);
        request.setAdditionalHeader("Content-Type", "application/xml; charset=UTF-8");
        request.setRequestBody(changed);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private AbstractItem reload(AbstractItem item) {
        return j.jenkins.getItemByFullName(item.getFullName(), AbstractItem.class);
    }

    private static List<ChangeRecord> violations(String name) {
        return ApproverFormFixtures.records(ChangeType.GRANT_VIOLATION).stream()
                .filter(r -> "u1".equals(r.getUser()))
                .filter(r -> String.valueOf(r.getTarget()).contains(name) || String.valueOf(r.getDetail()).contains(name))
                .toList();
    }

    private static String describe(List<ChangeRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (ChangeRecord r : records) {
            sb.append("[user=").append(r.getUser()).append(" target=").append(r.getTarget()).append(" detail=").append(r.getDetail()).append(']');
        }
        return sb.toString();
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""),
                set(PermissionEntry.user("u1"), PermissionEntry.user("a1")));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(BatchControlPermissions.REQUEST_GRANT), ""),
                set(PermissionEntry.user("u1")));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(BatchControlPermissions.APPROVE), ""), set(PermissionEntry.user("a1")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(new TreeMap<>()));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }
}
