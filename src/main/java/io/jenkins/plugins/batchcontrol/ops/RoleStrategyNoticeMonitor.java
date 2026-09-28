package io.jenkins.plugins.batchcontrol.ops;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Informs administrators that JIT change control does not apply under the plain Role Strategy
 * (SPEC item 8): run control and recording still work, but grants cannot confer anything until
 * the Batch Control role-based variant is installed (D-35a). Activates when exactly
 * {@code RoleBasedAuthorizationStrategy} is the global authorization strategy; the Batch Control
 * subclass has a different class name and does not activate it. {@link BatchControlStrategyMonitor}
 * offers the migration.
 *
 * <p>role-strategy is an optional plugin, so it is detected by class name only — never
 * imported.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RoleStrategyNoticeMonitor extends AdministrativeMonitor {

    /** Detected by name: role-strategy must never be a compile-time dependency. */
    private static final String ROLE_STRATEGY_CLASS =
            "com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy";

    @Override
    public String getDisplayName() {
        return Messages.RoleStrategyNoticeMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        AuthorizationStrategy strategy = Jenkins.get().getAuthorizationStrategy();
        return strategy != null && ROLE_STRATEGY_CLASS.equals(strategy.getClass().getName());
    }
}
