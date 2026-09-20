package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.security.BatchControlAuthorizationStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Informs administrators that JIT change control is not supported under Role Strategy
 * (SPEC item 8, ARCHITECTURE section 7 / C-2): run control and recording still work, but
 * grants cannot apply. Activates when Role Strategy is the global authorization strategy —
 * plain or as the delegate inside the plugin's wrapper (wrapping it does not make it
 * supported).
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
        if (isRoleStrategy(strategy)) {
            return true;
        }
        if (strategy instanceof BatchControlAuthorizationStrategy) {
            return isRoleStrategy(((BatchControlAuthorizationStrategy) strategy).getDelegate());
        }
        return false;
    }

    private static boolean isRoleStrategy(@CheckForNull AuthorizationStrategy strategy) {
        return strategy != null && ROLE_STRATEGY_CLASS.equals(strategy.getClass().getName());
    }
}
