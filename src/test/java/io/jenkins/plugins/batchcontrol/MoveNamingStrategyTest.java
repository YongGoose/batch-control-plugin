package io.jenkins.plugins.batchcontrol;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.rolestrategy.RoleBasedProjectNamingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertClientError;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.assertSuccess;
import static io.jenkins.plugins.batchcontrol.ApproverFormFixtures.records;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-59b (SPEC item 8 D-59 line, jenkinsci/role-strategy-plugin#751): while change control is on, a
 * move also needs the installed project naming strategy to accept the moved name in the
 * destination. With role-strategy's role-based naming strategy, Item/Create is granted on a
 * container so that pattern-restricted creation works, so a user whose item role allows creating
 * only {@code team/team-.*} still holds Create on {@code team}; moving {@code prod/x} there must be
 * refused (nothing moved, 4xx, one GRANT_VIOLATION naming the naming rule), while
 * {@code prod/team-ok} moves. Guards: with the default naming strategy D-59 is unchanged; with
 * change control off moves behave as in Jenkins. Matrix rows T-SEC-73..75 (notes 218-219).
 *
 * <p>Actor {@code mover}: Overall/Read and Item/Read globally; an item role {@code prod/.*} with
 * native Item/Move and Item/Delete; an item role {@code team(/team-.*)?} with Item/Create.
 *
 * <p>Written from docs/SPEC.md item 8, docs/DECISIONS.md D-59/D-59b and docs/TEST-MATRIX.md only
 * (no src/main knowledge).
 */
@WithJenkins
public class MoveNamingStrategyTest {

    private JenkinsRule j;
    private Folder prod;
    private Folder team;

    @BeforeEach
    public void setUp(JenkinsRule rule) throws Exception {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        prod = j.jenkins.createProject(Folder.class, "prod");
        team = j.jenkins.createProject(Folder.class, "team");
        prod.createProject(FreeStyleProject.class, "x");
        prod.createProject(FreeStyleProject.class, "team-ok");

        for (String name : new String[] {"x", "team-ok"}) {
            Item item = prod.getItem(name);
            assertTrue(has(item, Item.DELETE) && has(item, RelocationAction.RELOCATE),
                    "premise: mover holds native Delete and Move on prod/" + name);
        }
        assertTrue(has(team, Item.CREATE), "premise: mover holds Item/Create on the container team");
    }

    /**
     * T-SEC-73 (D-59b): role-based naming strategy on; mover moves {@code prod/x} into
     * {@code team}: refused 4xx, nothing moved, one GRANT_VIOLATION naming mover, {@code prod/x}
     * and the naming rule. Guard: {@code prod/team-ok} moves into {@code team} with no new record.
     */
    @Test
    public void t_sec_73_moveUnderANameTheNamingStrategyRefusesIsRefused() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(true));

        WebResponse refused = move(prod.getItem("x"), team);
        assertClientError(refused, "mover moving prod/x into team under the role naming strategy");
        assertNotNull(prod.getItem("x"), "the refused move must leave prod/x in place");
        assertNull(team.getItem("x"), "nothing may arrive in team");
        List<ChangeRecord> violations = records(ChangeType.GRANT_VIOLATION);
        assertEquals(1, violations.size(), "the refused move is recorded once as GRANT_VIOLATION, got " + violations);
        ChangeRecord rec = violations.get(0);
        String text = rec.getTarget() + " " + rec.getDetail();
        assertEquals("mover", rec.getUser(), "the GRANT_VIOLATION names the mover");
        assertTrue(text.contains("prod/x"), "the GRANT_VIOLATION names prod/x: " + text);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("naming"), "the GRANT_VIOLATION names the naming rule: " + text);

        assertSuccess(move(prod.getItem("team-ok"), team), "guard: a name the naming strategy accepts moves");
        assertNull(prod.getItem("team-ok"), "guard: team-ok left prod");
        assertNotNull(team.getItem("team-ok"), "guard: team-ok arrived in team");
        assertEquals(1, records(ChangeType.GRANT_VIOLATION).size(), "guard: the permitted move adds no violation");
    }

    /** T-SEC-74 (D-59b guard): with the default naming strategy the same move of {@code prod/x} goes through (D-59 unchanged). */
    @Test
    public void t_sec_74_defaultNamingStrategyLeavesD59Unchanged() throws Exception {
        assertEquals(jenkins.model.ProjectNamingStrategy.DefaultProjectNamingStrategy.class,
                j.jenkins.getProjectNamingStrategy().getClass(), "premise: the default naming strategy is installed");
        assertSuccess(move(prod.getItem("x"), team), "Delete at the source and Create at the destination admit the move");
        assertNull(prod.getItem("x"), "prod/x left prod");
        assertNotNull(team.getItem("x"), "x arrived in team");
        assertTrue(records(ChangeType.GRANT_VIOLATION).isEmpty(), "no violation is recorded");
    }

    /**
     * T-SEC-75 (D-59b guard): role-based naming strategy on but change control off: the move of
     * {@code prod/x} into {@code team} behaves as in Jenkins (the folders plugin moves it) and records
     * nothing. Falsifiability twin first: with change control on it is refused.
     */
    @Test
    public void t_sec_75_changeControlOffLeavesMovesAsInJenkins() throws Exception {
        j.jenkins.setProjectNamingStrategy(new RoleBasedProjectNamingStrategy(true));
        assertClientError(move(prod.getItem("x"), team), "twin: with change control on the move is refused");
        assertNotNull(prod.getItem("x"), "twin: nothing moved");
        int before = records(ChangeType.GRANT_VIOLATION).size();

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertSuccess(move(prod.getItem("x"), team), "with change control off the move behaves as in Jenkins");
        assertNull(prod.getItem("x"), "prod/x left prod");
        assertNotNull(team.getItem("x"), "x arrived in team");
        assertEquals(before, records(ChangeType.GRANT_VIOLATION).size(), "change control off records no violation");
    }

    // ---------------------------------------------------------------- helpers

    private WebResponse move(Item item, Folder destination) throws Exception {
        assertNotNull(item, "fixture: the item to move must exist");
        return ApproverFormFixtures.post(j, "mover", item.getUrl() + "move/move",
                Collections.singletonList(new NameValuePair("destination", "/" + destination.getFullName())));
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), set(PermissionEntry.user("admin")));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""), set(PermissionEntry.user("mover")));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("prod-movers", Pattern.compile("prod/.*"),
                Set.of(Item.READ, Item.DELETE, RelocationAction.RELOCATE), ""), set(PermissionEntry.user("mover")));
        items.put(new Role("team-creators", Pattern.compile("team(/team-.*)?"),
                Set.of(Item.READ, Item.CREATE), ""), set(PermissionEntry.user("mover")));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    @SafeVarargs
    private static <E> Set<E> set(E... e) {
        return new HashSet<>(Arrays.asList(e));
    }

    private static boolean has(hudson.security.AccessControlled o, Permission p) {
        return o.getACL().hasPermission2(hudson.model.User.getById("mover", true).impersonate2(), p);
    }
}
