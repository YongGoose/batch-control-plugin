package io.jenkins.plugins.batchcontrol;

import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy;
import hudson.model.AdministrativeMonitor;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.ops.ConfigureWithoutGrantMonitor;
import io.jenkins.plugins.batchcontrol.security.BatchControlMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.security.BatchControlRoleBasedAuthorizationStrategy;
import java.util.Collections;
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
 * Item/Configure while change control is on) and T-08-08 (the batch-control-strategy monitor
 * for a plain role strategy, SPEC item 8 / D-35a).
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
        BatchControlMatrixAuthorizationStrategy adminOnly = new BatchControlMatrixAuthorizationStrategy();
        adminOnly.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        j.jenkins.setAuthorizationStrategy(adminOnly);
        assertFalse(monitor.isActivated(), "only the admin holds Configure: the monitor must stay quiet");

        // a non-admin with direct Item/Configure appears -> warning
        BatchControlMatrixAuthorizationStrategy withDirectConfigure = new BatchControlMatrixAuthorizationStrategy();
        withDirectConfigure.add(Jenkins.ADMINISTER, PermissionEntry.user("admin"));
        withDirectConfigure.add(Jenkins.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.READ, PermissionEntry.user("u3"));
        withDirectConfigure.add(Item.CONFIGURE, PermissionEntry.user("u3"));
        j.jenkins.setAuthorizationStrategy(withDirectConfigure);
        assertTrue(monitor.isActivated(), "a non-admin holding direct Item/Configure while change control is on "
                + "must activate the warning monitor");

        // change control off -> the warning is not relevant and must disappear
        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "with change control off the monitor must stay quiet");
    }

    /**
     * T-08-08 (rewritten for SPEC item 8 / D-35a, which replaced the Role Strategy "JIT
     * unsupported" notice): with change control on, a plain RoleBasedAuthorizationStrategy
     * activates the {@code batch-control-strategy} monitor; the Batch Control role strategy does
     * not; with change control off the plain one does not either.
     */
    @Test
    public void t_08_08_plainRoleStrategyActivatesStrategyMonitor() throws Exception {
        AdministrativeMonitor monitor = j.jenkins.getAdministrativeMonitor("batch-control-strategy");
        assertNotNull(monitor, "the batch-control-strategy monitor must be registered");
        assertTrue(monitor.isEnabled());

        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        cfg.setChangeControlEnabled(true);
        cfg.save();

        j.jenkins.setAuthorizationStrategy(new BatchControlRoleBasedAuthorizationStrategy(
                StrategyFixtures.roles(), Collections.emptySet()));
        assertFalse(monitor.isActivated(), "the Batch Control role strategy must not activate the monitor");

        j.jenkins.setAuthorizationStrategy(new RoleBasedAuthorizationStrategy(StrategyFixtures.roles(), Collections.emptySet()));
        assertTrue(monitor.isActivated(), "a plain role strategy with change control on must activate the monitor");

        cfg.setChangeControlEnabled(false);
        cfg.save();
        assertFalse(monitor.isActivated(), "with change control off the monitor must stay quiet");
    }
}
