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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@WithJenkins
public class GrantMonitorsTest {

    private JenkinsRule j;

    @BeforeEach
    public void setUp(JenkinsRule rule) {
        this.j = rule;
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
        assertNotNull(monitor, "the configure-without-grant monitor must be registered");
        assertTrue(monitor.isEnabled(), "the monitor must be enabled by default");

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        // change control on, but only the admin exists -> quiet (admin bypass is out of scope)
        GlobalMatrixAuthorizationStrategy adminOnly = new GlobalMatrixAuthorizationStrategy();
        adminOnly.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(adminOnly));
        assertFalse(monitor.isActivated(), "only the admin holds Configure: the monitor must stay quiet");

        // a non-admin with direct Item/Configure appears -> warning
        GlobalMatrixAuthorizationStrategy withDirectConfigure = new GlobalMatrixAuthorizationStrategy();
        withDirectConfigure.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        withDirectConfigure.add(Jenkins.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.CONFIGURE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(new BatchControlAuthorizationStrategy(withDirectConfigure));
        assertTrue(monitor.isActivated(), "a non-admin holding direct Item/Configure while change control is on "
                + "must activate the warning monitor");

        // change control off -> the warning is not relevant and must disappear
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "with change control off the monitor must stay quiet");
    }

    /** T-08-08: Role Strategy as the global authorization strategy activates the unsupported-JIT notice. */
    @Test
    public void t_08_08_roleStrategyNoticeMonitorActivation() throws Exception {
        AdministrativeMonitor monitor = AdministrativeMonitor.all().get(RoleStrategyNoticeMonitor.class);
        assertNotNull(monitor, "the Role Strategy notice monitor must be registered");
        assertTrue(monitor.isEnabled());

        // matrix strategy selected -> quiet
        GlobalMatrixAuthorizationStrategy matrix = new GlobalMatrixAuthorizationStrategy();
        matrix.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(matrix);
        assertFalse(monitor.isActivated(), "a matrix strategy must not trigger the Role Strategy notice");

        // Role Strategy selected -> the JIT-unsupported notice must show
        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy());
        assertTrue(monitor.isActivated(), "selecting Role Strategy as the global strategy must activate the notice "
                + "(JIT change control is unsupported there, ARCHITECTURE section 7)");
    }
}
