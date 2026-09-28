package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.AuthorizationStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The role-strategy half of {@link StrategyMigration} (S-03). Refers to role-strategy types, so
 * it is linked only after {@link StrategyMigration} checked that role-strategy is installed. Every
 * method returns a core type, and this class never refers to matrix-auth.
 */
@Restricted(NoExternalUse.class)
final class RoleStrategies {

    private RoleStrategies() {
    }

    /** The Batch Control role strategy with every role, assignment and template of {@code existing}. */
    @NonNull
    static AuthorizationStrategy copyOf(@NonNull AuthorizationStrategy existing) {
        return BatchControlRoleBasedAuthorizationStrategy.copyOf(existing);
    }
}
