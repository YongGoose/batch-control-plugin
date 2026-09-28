package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.security.AuthorizationStrategy;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Copies between a plain supported strategy and its Batch Control subclass (D-35a, D-35d): the
 * migration action, its reverse, and the load-time conversion of the withdrawn wrapper.
 *
 * <p>matrix-auth and role-strategy are optional, and either or both may be missing (S-03). This
 * class refers to core types only. Strategies are recognised by class name, the plugin is checked
 * to be installed, and only then is the per-plugin holder ({@link MatrixStrategies} or
 * {@link RoleStrategies}) called. Those holders return core types, so verifying this class never
 * loads a class of either plugin.
 */
@Restricted(NoExternalUse.class)
public final class StrategyMigration {

    static final String PROJECT_MATRIX = "hudson.security.ProjectMatrixAuthorizationStrategy";
    static final String GLOBAL_MATRIX = "hudson.security.GlobalMatrixAuthorizationStrategy";
    static final String ROLE_BASED =
            "com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy";

    static final String MATRIX_AUTH_PLUGIN = "matrix-auth";
    static final String ROLE_STRATEGY_PLUGIN = "role-strategy";

    private StrategyMigration() {
    }

    /** Whether the plugin {@code shortName} is installed and active. */
    static boolean pluginActive(String shortName) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null && jenkins.getPlugin(shortName) != null;
    }

    /**
     * Whether {@code strategy} is exactly one of the plain strategies the migration action copies:
     * matrix-auth's project matrix or global matrix, or role-strategy's class. Subclasses by other
     * plugins are not copied: their extra state would be lost.
     */
    public static boolean isMigratable(@CheckForNull AuthorizationStrategy strategy) {
        if (strategy == null) {
            return false;
        }
        String name = strategy.getClass().getName();
        return PROJECT_MATRIX.equals(name) || GLOBAL_MATRIX.equals(name) || ROLE_BASED.equals(name);
    }

    /**
     * Whether migrating {@code strategy} makes per-item authorization properties effective (S-04,
     * D-35d): matrix-auth's global matrix ignores job, folder and agent properties, and the Batch
     * Control matrix is project-based, so stale properties would start to count and native
     * Item/Configure holders could edit item ACLs.
     */
    public static boolean isPerItemWidening(@CheckForNull AuthorizationStrategy strategy) {
        return strategy != null && GLOBAL_MATRIX.equals(strategy.getClass().getName());
    }

    /**
     * The Batch Control subclass with every entry of {@code strategy}, or {@code null} when
     * {@link #isMigratable} is false or the plugin is not installed. The argument is not modified.
     * An explicit administrator action (the monitor's migration button); for the global matrix
     * see {@link #isPerItemWidening}.
     */
    @CheckForNull
    public static AuthorizationStrategy toBatchControl(@CheckForNull AuthorizationStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        String name = strategy.getClass().getName();
        if ((PROJECT_MATRIX.equals(name) || GLOBAL_MATRIX.equals(name)) && pluginActive(MATRIX_AUTH_PLUGIN)) {
            return MatrixStrategies.copyOf(strategy);
        }
        if (ROLE_BASED.equals(name) && pluginActive(ROLE_STRATEGY_PLUGIN)) {
            return RoleStrategies.copyOf(strategy);
        }
        return null;
    }

    /**
     * The load conversion of the withdrawn wrapper's delegate (D-35a as amended by D-35d, SPEC
     * item 8): a project matrix or role-strategy delegate becomes the matching subclass.
     * {@code null} for any other strategy, including the global matrix, which is installed
     * unwrapped because converting it would make per-item properties effective without an
     * administrator's action.
     */
    @CheckForNull
    static AuthorizationStrategy fromLegacyDelegate(AuthorizationStrategy delegate) {
        if (isPerItemWidening(delegate)) {
            return null;
        }
        return toBatchControl(delegate);
    }

    /** An empty Batch Control matrix (denies everyone but SYSTEM), or {@code null} without matrix-auth. */
    @CheckForNull
    static AuthorizationStrategy emptyMatrix() {
        return pluginActive(MATRIX_AUTH_PLUGIN) ? MatrixStrategies.empty() : null;
    }

    /**
     * The plain parent strategy with every entry of a Batch Control {@code strategy}, or
     * {@code null} if it is not one.
     */
    @CheckForNull
    public static AuthorizationStrategy toPlain(@CheckForNull AuthorizationStrategy strategy) {
        return strategy instanceof GrantLayeredStrategy
                ? ((GrantLayeredStrategy) strategy).toPlainStrategy()
                : null;
    }
}
