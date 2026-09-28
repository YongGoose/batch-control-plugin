package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.AdministrativeMonitor;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.net.URL;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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

import static io.jenkins.plugins.batchcontrol.StrategyFixtures.MONITOR_ID;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.T0;
import static io.jenkins.plugins.batchcontrol.StrategyFixtures.has;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-35a migration and monitor: POST {@code /manage/administrativeMonitor/batch-control-strategy/migrate}
 * copies an installed plain matrix-auth or role-strategy configuration into the matching Batch
 * Control subclass keeping every entry; POST {@code .../revert} copies it back (the uninstall
 * path). Both require Overall/Administer and a CSRF crumb. The monitor shows while change
 * control is on and the installed strategy is not a Batch Control strategy.
 * Matrix rows T-02-30 (migrate), T-02-31 (revert), T-02-32 (refusals: 403 without Administer,
 * 403 without crumb, 405 on GET) and T-02-33 (monitor activation).
 *
 * <p>Written from docs/SPEC.md, docs/DECISIONS.md D-35a, docs/ARCHITECTURE.md section 4 and the
 * frozen endpoint names of slice #30 only (no src/main knowledge).
 */
@WithJenkins
public class StrategyMigrationTest {

    private static final String BASE = "manage/administrativeMonitor/" + MONITOR_ID + "/";

    private JenkinsRule j;
    private BatchControlGlobalConfiguration cfg;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        BatchClock.setForTest(Clock.fixed(T0, ZoneOffset.UTC));
        cfg = StrategyFixtures.changeControlOn();
    }

    @AfterEach
    public void resetClock() {
        BatchClock.reset();
    }

    private int post(String user, String action, boolean withCrumb) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        URL url = withCrumb ? wc.createCrumbedUrl(BASE + action) : new URL(j.getURL(), BASE + action);
        return wc.getPage(new WebRequest(url, HttpMethod.POST)).getWebResponse().getStatusCode();
    }

    private int get(String user, String action) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().withThrowExceptionOnFailingStatusCode(false).login(user);
        return wc.getPage(new WebRequest(new URL(j.getURL(), BASE + action), HttpMethod.GET)).getWebResponse().getStatusCode();
    }

    private FreeStyleProject jobWithAliceProperty() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        AuthorizationMatrixProperty amp = new AuthorizationMatrixProperty(new HashMap<>(), new InheritParentStrategy());
        amp.add(Item.CONFIGURE, PermissionEntry.user("alice"));
        p.addProperty(amp);
        return p;
    }

    /**
     * T-02-30: with a plain matrix-auth strategy installed (grants do not confer, the monitor
     * shows), the administrator's migrate installs the Batch Control matrix strategy with every
     * entry kept, the per-item property untouched, the open grant conferring and the monitor
     * quiet. The same for a plain role-strategy.
     */
    @Test
    public void t_02_30_migrateCopiesPlainStrategyIntoSubclass() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(plain);
        Set<String> before = StrategyFixtures.describeMatrix(plain.getGrantedPermissionEntries());
        FreeStyleProject p = jobWithAliceProperty();
        FreeStyleProject granted = j.createFreeStyleProject("granted");
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, "granted", Arrays.asList(GrantAction.CONFIGURE));
        AdministrativeMonitor monitor = StrategyFixtures.strategyMonitor();
        assertFalse(has(granted, "bob", Item.CONFIGURE), "premise: a grant does not confer under the plain parent");
        assertTrue(monitor.isActivated(), "premise: the monitor shows for a plain matrix strategy with change control on");

        int status = post("admin", "migrate", true);
        assertTrue(status < 400, "the administrator's migrate must succeed, got HTTP " + status);

        assertSame(BatchControlMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "migrate must install the Batch Control matrix strategy");
        assertEquals(before, StrategyFixtures.describeMatrix(
                ((BatchControlMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).getGrantedPermissionEntries()),
                "migrate must keep every global entry");
        assertTrue(has(p, "alice", Item.CONFIGURE), "per-item properties must be untouched by the migration");
        assertTrue(has(granted, "bob", Item.CONFIGURE), "the open grant must confer after the migration");
        assertFalse(has(granted, "carol", Item.CONFIGURE), "guard: carol holds no grant");
        assertFalse(monitor.isActivated(), "the monitor must be quiet after the migration");

        // role-strategy
        RoleBasedAuthorizationStrategy plainRoles = new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet());
        j.jenkins.setAuthorizationStrategy(plainRoles);
        Set<String> rolesBefore = StrategyFixtures.describeRoles(plainRoles);
        assertTrue(monitor.isActivated(), "premise: the monitor shows for a plain role strategy with change control on");

        status = post("admin", "migrate", true);
        assertTrue(status < 400, "the administrator's migrate must succeed for role-strategy, got HTTP " + status);
        assertSame(BatchControlRoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "migrate must install the Batch Control role strategy");
        assertEquals(rolesBefore, StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()),
                "migrate must keep every role and assignment");
        assertFalse(monitor.isActivated(), "the monitor must be quiet after the role migration");
    }

    /**
     * T-02-31: revert copies the Batch Control subclass back into the exact plain parent class
     * with every entry kept (the uninstall path); grants stop conferring and per-item properties
     * are untouched. The same for role-strategy.
     */
    @Test
    public void t_02_31_revertCopiesSubclassBackIntoPlainParent() throws Exception {
        BatchControlMatrixAuthorizationStrategy sub = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(sub);
        Set<String> before = StrategyFixtures.describeMatrix(sub.getGrantedPermissionEntries());
        FreeStyleProject p = jobWithAliceProperty();
        FreeStyleProject granted = j.createFreeStyleProject("granted");
        StrategyFixtures.grant("bob", GrantScope.Type.JOB, "granted", Arrays.asList(GrantAction.CONFIGURE));
        assertTrue(has(granted, "bob", Item.CONFIGURE), "premise: the grant confers under the subclass");

        int status = post("admin", "revert", true);
        assertTrue(status < 400, "the administrator's revert must succeed, got HTTP " + status);

        assertSame(ProjectMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "revert must install exactly the plain ProjectMatrixAuthorizationStrategy");
        assertEquals(before, StrategyFixtures.describeMatrix(
                ((ProjectMatrixAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()).getGrantedPermissionEntries()),
                "revert must keep every global entry");
        assertTrue(has(p, "alice", Item.CONFIGURE), "per-item properties must be untouched by the revert");
        assertFalse(has(granted, "bob", Item.CONFIGURE), "after the revert a grant must no longer confer");

        BatchControlRoleBasedAuthorizationStrategy subRoles =
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet());
        j.jenkins.setAuthorizationStrategy(subRoles);
        Set<String> rolesBefore = StrategyFixtures.describeRoles(subRoles);
        status = post("admin", "revert", true);
        assertTrue(status < 400, "the administrator's revert must succeed for role-strategy, got HTTP " + status);
        assertSame(RoleBasedAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass(),
                "revert must install exactly the plain RoleBasedAuthorizationStrategy");
        assertEquals(rolesBefore, StrategyFixtures.describeRoles((RoleBasedAuthorizationStrategy) j.jenkins.getAuthorizationStrategy()),
                "revert must keep every role and assignment");
    }

    /**
     * T-02-32 (P0 permission row): migrate and revert refuse m1 (BatchControl/Manage but not
     * Overall/Administer) with 403, refuse the administrator without a crumb with 403, and
     * answer a GET with 405. None of the refused requests changes the installed strategy.
     * Guard: the administrator's crumbed POST does change it (T-02-30/31), so the refusals are
     * not a broken endpoint.
     */
    @Test
    public void t_02_32_migrateAndRevertRefuseNonAdminGetAndMissingCrumb() throws Exception {
        ProjectMatrixAuthorizationStrategy plain = StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(plain);

        assertEquals(403, post("m1", "migrate", true), "migrate without Overall/Administer must be 403");
        assertSame(plain, j.jenkins.getAuthorizationStrategy(), "a refused migrate must not change the strategy");
        assertEquals(403, post("admin", "migrate", false), "migrate without a crumb must be 403");
        assertSame(plain, j.jenkins.getAuthorizationStrategy(), "a crumb-less migrate must not change the strategy");
        assertEquals(405, get("admin", "migrate"), "migrate by GET must be refused with 405");
        assertSame(plain, j.jenkins.getAuthorizationStrategy(), "a GET must not change the strategy");

        BatchControlMatrixAuthorizationStrategy sub = StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy());
        j.jenkins.setAuthorizationStrategy(sub);
        assertEquals(403, post("m1", "revert", true), "revert without Overall/Administer must be 403");
        assertSame(sub, j.jenkins.getAuthorizationStrategy(), "a refused revert must not change the strategy");
        assertEquals(403, post("admin", "revert", false), "revert without a crumb must be 403");
        assertSame(sub, j.jenkins.getAuthorizationStrategy(), "a crumb-less revert must not change the strategy");
        assertEquals(405, get("admin", "revert"), "revert by GET must be refused with 405");
        assertSame(sub, j.jenkins.getAuthorizationStrategy(), "a GET must not change the strategy");

        // guard: the endpoint itself works for the administrator
        assertTrue(post("admin", "revert", true) < 400, "guard: the administrator's crumbed revert must succeed");
        assertSame(ProjectMatrixAuthorizationStrategy.class, j.jenkins.getAuthorizationStrategy().getClass());
    }

    /**
     * T-02-33: the batch-control-strategy monitor shows exactly when change control is on and the
     * installed strategy is not a Batch Control strategy: plain project matrix, plain global
     * matrix and plain role strategy activate it; either subclass keeps it quiet; with change
     * control off it is quiet whatever is installed.
     */
    @Test
    public void t_02_33_monitorShowsOnlyForUnsupportedStrategyWithChangeControlOn() throws Exception {
        AdministrativeMonitor monitor = StrategyFixtures.strategyMonitor();
        assertTrue(monitor.isEnabled(), "the monitor must be enabled by default");

        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
        assertTrue(monitor.isActivated(), "plain project matrix + change control on must activate the monitor");
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new GlobalMatrixAuthorizationStrategy()));
        assertTrue(monitor.isActivated(), "plain global matrix + change control on must activate the monitor");
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertTrue(monitor.isActivated(), "plain role strategy + change control on must activate the monitor");

        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new BatchControlMatrixAuthorizationStrategy()));
        assertFalse(monitor.isActivated(), "the Batch Control matrix strategy must keep the monitor quiet");
        j.jenkins.setAuthorizationStrategy(
                new BatchControlRoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertFalse(monitor.isActivated(), "the Batch Control role strategy must keep the monitor quiet");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        j.jenkins.setAuthorizationStrategy(StrategyFixtures.matrix(new ProjectMatrixAuthorizationStrategy()));
        assertFalse(monitor.isActivated(), "with change control off the monitor must stay quiet (SPEC 1: no new behaviour while off)");
    }
}
