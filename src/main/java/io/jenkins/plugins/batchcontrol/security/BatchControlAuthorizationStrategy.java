package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The withdrawn delegating wrapper (D-35a), kept only so that a {@code config.xml} saved by an
 * earlier version still loads. It has no descriptor, so it can no longer be selected.
 *
 * <p>{@link #readResolve()} converts it on load:
 * <ul>
 *   <li>a matrix-auth project matrix or role-strategy delegate becomes the matching Batch
 *       Control subclass with every entry kept;</li>
 *   <li>a matrix-auth global matrix is installed unwrapped (D-35d, S-04): converting it would
 *       make per-item properties effective, so the monitor offers it as an explicit action;</li>
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

    /**
     * Replaces the loaded wrapper with the strategy that takes its place (see the class comment).
     * Never throws: {@code Jenkins.authorizationStrategy} is a critical field, so an exception here
     * would stop Jenkins from starting (S-03). A conversion that cannot be linked because an
     * optional plugin is missing falls back to the unwrapped delegate.
     */
    protected Object readResolve() {
        AuthorizationStrategy saved = delegate;
        if (saved == null) {
            AuthorizationStrategy empty = emptyMatrixOrNull();
            if (empty != null) {
                LOGGER.warning("The saved Batch Control wrapper had no delegate strategy; loading an "
                        + "empty Batch Control matrix, which denies everyone except SYSTEM.");
                return empty;
            }
            LOGGER.warning("The saved Batch Control wrapper had no delegate strategy; every "
                    + "permission is denied except to SYSTEM.");
            return this;
        }
        String savedClass = saved.getClass().getName();
        if (saved instanceof GrantLayeredStrategy) {
            return saved;
        }
        if (StrategyMigration.isPerItemWidening(saved)) {
            LOGGER.warning(() -> "The withdrawn Batch Control wrapper was around " + savedClass
                    + "; it is installed unwrapped, so grants no longer confer anything. Converting "
                    + "it would make per-item authorization properties effective; the "
                    + "batch-control-strategy monitor offers that as an explicit action (D-35d).");
            return saved;
        }
        AuthorizationStrategy converted;
        try {
            converted = StrategyMigration.fromLegacyDelegate(saved);
        } catch (LinkageError | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not convert the withdrawn Batch Control wrapper around "
                    + savedClass + "; it is installed unwrapped and grants no longer confer anything.", e);
            return saved;
        }
        if (converted != null) {
            LOGGER.info(() -> "Converted the withdrawn Batch Control wrapper around "
                    + savedClass + " into " + converted.getClass().getName()
                    + ", keeping every entry (D-35a).");
            return converted;
        }
        LOGGER.warning(() -> "The withdrawn Batch Control wrapper was around "
                + savedClass + ", which has no Batch Control variant; it is "
                + "installed unwrapped and grants no longer confer anything (D-35a).");
        return saved;
    }

    @CheckForNull
    private static AuthorizationStrategy emptyMatrixOrNull() {
        try {
            return StrategyMigration.emptyMatrix();
        } catch (LinkageError | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build an empty Batch Control matrix", e);
            return null;
        }
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
