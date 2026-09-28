package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The withdrawn delegating wrapper (D-35a), kept only so that a {@code config.xml} saved by an
 * earlier version still loads. It has no descriptor, so it can no longer be selected.
 *
 * <p>{@link #readResolve()} converts it on load:
 * <ul>
 *   <li>a matrix-auth project matrix or role-strategy delegate becomes the matching Batch Control
 *       subclass with every entry kept;</li>
 *   <li>any other delegate is installed unwrapped. Grants then stop conferring, and
 *       {@code ops.BatchControlStrategyMonitor} says that grants need a supported strategy;</li>
 *   <li>a missing delegate (the old deny-all state) stays deny-all: an empty Batch Control matrix
 *       when matrix-auth is installed, otherwise this object, whose ACLs deny everyone but
 *       SYSTEM.</li>
 * </ul>
 */
@Restricted(NoExternalUse.class)
public class BatchControlAuthorizationStrategy extends AuthorizationStrategy {

    private static final Logger LOGGER = Logger.getLogger(BatchControlAuthorizationStrategy.class.getName());

    /** The wrapped strategy as saved by the earlier version. */
    @CheckForNull
    private final AuthorizationStrategy delegate;

    /**
     * Only for building a saved configuration of an earlier version (tests of the load
     * conversion). Installing the result directly bypasses {@link #readResolve()} and denies
     * everything except SYSTEM.
     */
    public BatchControlAuthorizationStrategy(@CheckForNull AuthorizationStrategy delegate) {
        this.delegate = delegate;
    }

    /** Replaces the loaded wrapper with the strategy that takes its place (see the class comment). */
    protected Object readResolve() {
        AuthorizationStrategy saved = delegate;
        if (saved == null) {
            if (Jenkins.getInstanceOrNull() != null && Jenkins.get().getPlugin("matrix-auth") != null) {
                LOGGER.warning("The saved Batch Control wrapper had no delegate strategy; loading an "
                        + "empty Batch Control matrix, which denies everyone except SYSTEM.");
                return StrategyMigration.emptyMatrix();
            }
            LOGGER.warning("The saved Batch Control wrapper had no delegate strategy; every "
                    + "permission is denied except to SYSTEM.");
            return this;
        }
        String savedClass = saved.getClass().getName();
        AuthorizationStrategy converted = StrategyMigration.toBatchControl(saved);
        if (converted != null) {
            LOGGER.info(() -> "Converted the withdrawn Batch Control wrapper around "
                    + savedClass + " into " + converted.getClass().getName()
                    + ", keeping every entry (D-35a).");
            return converted;
        }
        if (saved instanceof GrantLayeredStrategy) {
            return saved;
        }
        LOGGER.warning(() -> "The withdrawn Batch Control wrapper was around "
                + savedClass + ", which has no Batch Control variant; it is "
                + "installed unwrapped and grants no longer confer anything (D-35a).");
        return saved;
    }

    @NonNull
    @Override
    public ACL getRootACL() {
        return GrantAwareACL.denyAll();
    }

    @NonNull
    @Override
    public Collection<String> getGroups() {
        return Collections.emptySet();
    }
}
