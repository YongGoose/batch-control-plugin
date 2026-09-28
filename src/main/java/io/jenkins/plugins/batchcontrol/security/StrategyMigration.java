package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.security.AuthorizationStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Copies between a plain supported strategy and its Batch Control subclass (D-35a): the migration
 * action, its reverse, and the load-time conversion of the withdrawn wrapper.
 *
 * <p>matrix-auth and role-strategy are optional. Strategies are recognised by class name, and the
 * subclass holding the copy logic is only reached once the name matched, so the class of the
 * missing plugin is never loaded. Method signatures use core types only for the same reason.
 */
@Restricted(NoExternalUse.class)
public final class StrategyMigration {

    static final String PROJECT_MATRIX = "hudson.security.ProjectMatrixAuthorizationStrategy";
    static final String ROLE_BASED =
            "com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy";

    private StrategyMigration() {
    }

    /**
     * Whether {@code strategy} is exactly the plain matrix-auth project matrix or the plain
     * role-strategy class, the two strategies that have a Batch Control subclass. Subclasses by
     * other plugins are not copied: their extra state would be lost.
     */
    public static boolean isMigratable(@CheckForNull AuthorizationStrategy strategy) {
        if (strategy == null) {
            return false;
        }
        String name = strategy.getClass().getName();
        return PROJECT_MATRIX.equals(name) || ROLE_BASED.equals(name);
    }

    /**
     * The Batch Control subclass with every entry of {@code strategy}, or {@code null} when
     * {@link #isMigratable} is false. The argument is not modified.
     */
    @CheckForNull
    public static AuthorizationStrategy toBatchControl(@CheckForNull AuthorizationStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        String name = strategy.getClass().getName();
        if (PROJECT_MATRIX.equals(name)) {
            return BatchControlMatrixAuthorizationStrategy.copyOf(strategy);
        }
        if (ROLE_BASED.equals(name)) {
            return BatchControlRoleBasedAuthorizationStrategy.copyOf(strategy);
        }
        return null;
    }

    /** An empty Batch Control matrix (denies everyone but SYSTEM). Requires matrix-auth. */
    static AuthorizationStrategy emptyMatrix() {
        return new BatchControlMatrixAuthorizationStrategy();
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
