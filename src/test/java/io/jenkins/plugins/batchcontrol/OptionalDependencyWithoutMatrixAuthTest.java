package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.PermissionEntry;
import com.michelin.cio.hudson.plugins.rolestrategy.Role;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import com.michelin.cio.hudson.plugins.rolestrategy.RoleMap;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * security-05 S-03, matrix row T-02-43: a real Jenkins started WITHOUT matrix-auth. Jenkins
 * boots, the Batch Control role strategy works (item role and a grant), the {@code /manage}
 * page with the batch-control-strategy monitor renders, and the Batch Control role strategy is
 * loaded from config.xml, both through {@code Jenkins.reload()} and through a real restart. (The
 * legacy wrapper config.xml this row used to load was removed with its conversion by D-35e.)
 *
 * <p>This class deliberately references no matrix-auth type (not even through
 * {@link StrategyFixtures}): its code is loaded into the JVM that lacks the plugin.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a/D-35d/D-35e and docs/reports/security-05.md
 * only (no src/main knowledge).
 */
public class OptionalDependencyWithoutMatrixAuthTest {

    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("matrix-auth");

    /** T-02-43: without matrix-auth the role path, the monitor page and a reload and a restart all work. */
    @Test
    public void t_02_43_withoutMatrixAuthRolePathWorks() throws Throwable {
        rr.then(OptionalDependencyWithoutMatrixAuthTest::bootAndReload);
        // The fixture pins the restart to the port the first boot was given, which was released
        // when that JVM stopped; under parallel surefire forks another process can take it in
        // between and the restart fails with "Failed to start Jetty". Ask for a fresh ephemeral
        // port instead: the restart still reuses the same JENKINS_HOME, which is what it measures.
        rr.withPort(0);
        rr.then(OptionalDependencyWithoutMatrixAuthTest::strategySurvivesRestart);
    }

    private static Map<String, RoleMap> roles() {
        TreeMap<Role, Set<PermissionEntry>> global = new TreeMap<>();
        global.put(new Role("admin", Pattern.compile(".*"), Set.of(Jenkins.ADMINISTER), ""), new HashSet<>(Set.of(PermissionEntry.user("admin"))));
        global.put(new Role("reader", Pattern.compile(".*"), Set.of(Jenkins.READ, Item.READ), ""),
                new HashSet<>(Set.of(PermissionEntry.user("bob"), PermissionEntry.user("a1"))));
        global.put(new Role("requester", Pattern.compile(".*"), Set.of(BatchControlPermissions.REQUEST_GRANT), ""),
                new HashSet<>(Set.of(PermissionEntry.user("bob"))));
        global.put(new Role("approver", Pattern.compile(".*"), Set.of(BatchControlPermissions.APPROVE), ""),
                new HashSet<>(Set.of(PermissionEntry.user("a1"))));
        TreeMap<Role, Set<PermissionEntry>> items = new TreeMap<>();
        items.put(new Role("team", Pattern.compile("team-.*"), Set.of(Item.CONFIGURE), ""), new HashSet<>(Set.of(PermissionEntry.user("bob"))));
        Map<String, RoleMap> m = new HashMap<>();
        m.put(RoleBasedAuthorizationStrategy.GLOBAL, new RoleMap(global));
        m.put(RoleBasedAuthorizationStrategy.PROJECT, new RoleMap(items));
        m.put(RoleBasedAuthorizationStrategy.SLAVE, new RoleMap(new TreeMap<>()));
        return m;
    }

    private static void bootAndReload(JenkinsRule r) throws Throwable {
        assertTrue(Jenkins.get().getPlugin("matrix-auth") == null, "premise: matrix-auth must not be installed");
        r.jenkins.setSecurityRealm(privateRealm()); // the real Jenkins JVM has no JenkinsRule$DummySecurityRealm
        r.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(roles(), Collections.emptySet()));
        r.jenkins.save();
        r.jenkins.reload();

        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                "without matrix-auth the Batch Control role strategy must load from config.xml");
        assertTrue(has(r.createFreeStyleProject("team-a"), "bob", Item.CONFIGURE), "item roles must work without matrix-auth");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject p = r.createFreeStyleProject("other");
        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob has no Configure on 'other' before the grant");
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "other"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant);
        assertTrue(has(p, "bob", Item.CONFIGURE), "the Batch Control role strategy must confer the grant without matrix-auth");

        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
        Page manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "/manage must render without matrix-auth");
        assertFalse(manage.getWebResponse().getContentAsString().contains("NoClassDefFoundError"),
                "/manage must not show a NoClassDefFoundError");
    }

    private static void strategySurvivesRestart(JenkinsRule r) throws Throwable {
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                "Jenkins must boot with the Batch Control role strategy without matrix-auth");
        assertTrue(has(r.jenkins.getItemByFullName("team-a"), "bob", Item.CONFIGURE), "roles must be kept through the boot");
    }

    /** A persistable realm with the fixture's users (password = user id). */
    private static hudson.security.HudsonPrivateSecurityRealm privateRealm() throws Exception {
        hudson.security.HudsonPrivateSecurityRealm realm = new hudson.security.HudsonPrivateSecurityRealm(false, false, null);
        for (String u : new String[] {"admin", "bob", "a1"}) {
            realm.createAccount(u, u);
        }
        return realm;
    }

    private static boolean has(hudson.security.AccessControlled o, String user, Permission p) {
        return o.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }
}
