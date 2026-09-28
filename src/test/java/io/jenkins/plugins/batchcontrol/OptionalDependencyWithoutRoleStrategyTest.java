package io.jenkins.plugins.batchcontrol;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.policy.GrantRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import java.util.Arrays;
import jenkins.model.Jenkins;
import org.htmlunit.Page;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
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
 * security-05 S-03 (ARCHITECTURE section 4: "a missing plugin never breaks class loading"),
 * matrix row T-02-42: a real Jenkins started WITHOUT role-strategy. Jenkins boots, the Batch
 * Control matrix strategy works (a grant confers), the {@code /manage} page with the
 * batch-control-strategy monitor renders, and a legacy wrapper config.xml loads, both through
 * {@code Jenkins.reload()} and through a real restart.
 *
 * <p>This class deliberately references no role-strategy type (not even through
 * {@link StrategyFixtures}): its code is loaded into the JVM that lacks the plugin.
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a/D-35d and docs/reports/security-05.md
 * only (no src/main knowledge).
 */
public class OptionalDependencyWithoutRoleStrategyTest {


    @RegisterExtension
    final RealJenkinsExtension rr = new RealJenkinsExtension().omitPlugins("role-strategy");

    /** T-02-42: without role-strategy the matrix path, the monitor page and the legacy upgrade all work. */
    @Test
    public void t_02_42_withoutRoleStrategyMatrixPathWorks() throws Throwable {
        rr.then(OptionalDependencyWithoutRoleStrategyTest::bootAndUpgrade);
        // The fixture pins the restart to the port the first boot was given, which was released
        // when that JVM stopped; under parallel surefire forks another process can take it in
        // between and the restart fails with "Failed to start Jetty". Ask for a fresh ephemeral
        // port instead: the restart still reuses the same JENKINS_HOME, which is what it measures.
        rr.withPort(0);
        rr.then(OptionalDependencyWithoutRoleStrategyTest::legacyWrapperSurvivesRestart);
    }

    private static void bootAndUpgrade(JenkinsRule r) throws Throwable {
        assertTrue(Jenkins.get().getPlugin("role-strategy") == null, "premise: role-strategy must not be installed");
        r.jenkins.setSecurityRealm(privateRealm()); // the real Jenkins JVM has no JenkinsRule$DummySecurityRealm

        ProjectMatrixAuthorizationStrategy plain = new ProjectMatrixAuthorizationStrategy();
        plain.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        for (String u : new String[] {"bob", "a1"}) {
            plain.add(Jenkins.READ, PermissionEntry.user(u));
            plain.add(Item.READ, PermissionEntry.user(u));
        }
        plain.add(BatchControlPermissions.REQUEST_GRANT, PermissionEntry.user("bob"));
        plain.add(BatchControlPermissions.APPROVE, PermissionEntry.user("a1"));
        r.jenkins.setAuthorizationStrategy(plain);
        r.jenkins.save();
        LegacyWrapperXml.write(r.jenkins);
        r.jenkins.reload();

        assertSame(BatchControlMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                "without role-strategy the legacy matrix wrapper must still load as the Batch Control matrix strategy");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.setApprovers(Arrays.asList("a1"));
        cfg.save();

        FreeStyleProject p = r.createFreeStyleProject("job");
        assertFalse(has(p, "bob", Item.CONFIGURE), "premise: bob has no Configure before the grant");
        GrantRequest request;
        try (ACLContext ignored = ACL.as2(User.getById("bob", true).impersonate2())) {
            request = GrantRequestService.get().create(new GrantScope(GrantScope.Type.JOB, "job"),
                    Arrays.asList(GrantAction.CONFIGURE), 30, "maintenance", "a1");
        }
        Grant grant;
        try (ACLContext ignored = ACL.as2(User.getById("a1", true).impersonate2())) {
            grant = GrantRequestService.get().approve(request.getId(), "ok");
        }
        assertNotNull(grant);
        assertTrue(has(p, "bob", Item.CONFIGURE), "the Batch Control matrix strategy must confer the grant without role-strategy");

        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false).login("admin", "admin");
        Page manage = wc.goTo("manage/");
        assertEquals(200, manage.getWebResponse().getStatusCode(), "/manage must render without role-strategy");
        assertFalse(manage.getWebResponse().getContentAsString().contains("NoClassDefFoundError"),
                "/manage must not show a NoClassDefFoundError");

        // leave the legacy wrapper on disk for the restart step
        LegacyWrapperXml.write(r.jenkins);
    }

    private static void legacyWrapperSurvivesRestart(JenkinsRule r) throws Throwable {
        assertSame(BatchControlMatrixAuthorizationStrategy.class, r.jenkins.getAuthorizationStrategy().getClass(),
                "Jenkins must boot from a legacy wrapper config.xml without role-strategy");
        assertTrue(((BatchControlMatrixAuthorizationStrategy) r.jenkins.getAuthorizationStrategy())
                .getGrantedPermissionEntries().get(Jenkins.ADMINISTER).contains(PermissionEntry.user("admin")),
                "the global entries must be kept through the boot");
    }

    /** A persistable realm with the fixture's users (password = user id). */
    private static hudson.security.HudsonPrivateSecurityRealm privateRealm() throws Exception {
        hudson.security.HudsonPrivateSecurityRealm realm = new hudson.security.HudsonPrivateSecurityRealm(false, false, null);
        for (String u : new String[] {"admin", "bob", "a1"}) {
            realm.createAccount(u, u);
        }
        return realm;
    }

    private static boolean has(hudson.security.AccessControlled o, String user, hudson.security.Permission p) {
        return o.getACL().hasPermission2(User.getById(user, true).impersonate2(), p);
    }
}
