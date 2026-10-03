package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.AuthorizationStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The matrix-auth half of {@link StrategyMigration} (S-03). Refers to matrix-auth types, so it is
 * linked only after {@link StrategyMigration} checked that matrix-auth is installed. Every method
 * returns a core type, so no caller has to load a matrix-auth class to verify the call, and this
 * class never refers to role-strategy.
 */
@Restricted(NoExternalUse.class)
final class MatrixStrategies {

    private MatrixStrategies() {
    }

    /** The Batch Control matrix with every entry of a matrix-auth matrix (project or global). */
    @NonNull
    static AuthorizationStrategy copyOf(@NonNull AuthorizationStrategy existing) {
        return BatchControlMatrixAuthorizationStrategy.copyOf(existing);
    }
}
