package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.AdministrativeMonitor;
import hudson.model.Item;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.ops.RoleStrategyNoticeMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SPEC item 8 administrative monitors. Matrix rows T-08-06 (warning when a user holds direct
 * Item/Configure while change control is on) and T-08-08 (notice when Role Strategy is the
 * global authorization strategy).
 *
 * Per the slice instruction these AdministrativeMonitor rows are covered through integration
 * checks of monitor activation (isActivated()), not through screen-scraping /manage.
 *
 * Written from docs/SPEC.md, docs/ARCHITECTURE.md and docs/TEST-MATRIX.md only (no src/main knowledge).
 */
public class GrantMonitorsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    }

    /**
     * T-08-06: with change control on, a non-admin user holding direct Item/Configure from the
     * delegate activates the warning monitor; without such a user (or with change control off)
     * the monitor stays quiet.
     */
    @Test
    public void t_08_06_configureWithoutGrantMonitorActivation() throws Exception {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(ConfigureWithoutGrantMonitor.class);
        assertNotNull("the configure-without-grant monitor must be registered", monitor);
        assertTrue("the monitor must be enabled by default", monitor.isEnabled());

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        // change control on, but only the admin exists -> quiet (admin bypass is out of scope)
        GlobalMatrixAuthorizationStrategy adminOnly = new GlobalMatrixAuthorizationStrategy();
        adminOnly.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(adminOnly));
        assertFalse("only the admin holds Configure: the monitor must stay quiet",
                monitor.isActivated());

        // a non-admin with direct Item/Configure appears -> warning
        GlobalMatrixAuthorizationStrategy withDirectConfigure = new GlobalMatrixAuthorizationStrategy();
        withDirectConfigure.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        withDirectConfigure.add(Jenkins.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.CONFIGURE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(withDirectConfigure));
        assertTrue("a non-admin holding direct Item/Configure while change control is on "
                + "must activate the warning monitor", monitor.isActivated());

        // change control off -> the warning is not relevant and must disappear
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse("with change control off the monitor must stay quiet", monitor.isActivated());
    }

    /** T-08-08: Role Strategy as the global authorization strategy activates the unsupported-JIT notice. */
    @Test
    public void t_08_08_roleStrategyNoticeMonitorActivation() throws Exception {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(RoleStrategyNoticeMonitor.class);
        assertNotNull("the Role Strategy notice monitor must be registered", monitor);
        assertTrue(monitor.isEnabled());

        // matrix strategy selected -> quiet
        GlobalMatrixAuthorizationStrategy matrix = new GlobalMatrixAuthorizationStrategy();
        matrix.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(matrix);
        assertFalse("a matrix strategy must not trigger the Role Strategy notice",
                monitor.isActivated());

        // Role Strategy selected -> the JIT-unsupported notice must show
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy());
        assertTrue("selecting Role Strategy as the global strategy must activate the notice "
                + "(JIT change control is unsupported there, ARCHITECTURE section 7)",
                monitor.isActivated());
    }
}
