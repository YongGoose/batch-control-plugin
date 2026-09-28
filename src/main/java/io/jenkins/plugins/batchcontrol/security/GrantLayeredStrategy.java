package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.security.AuthorizationStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Implemented by the Batch Control authorization strategies, the subclasses of the supported
 * strategies that layer active grants over the parent's ACLs (D-35a, ARCHITECTURE section 4).
 *
 * <p>This interface has no dependency on matrix-auth or role-strategy, so code that runs whether or
 * not those plugins are installed (monitors, listeners) can tell a Batch Control strategy apart
 * with {@code instanceof} without loading either plugin's classes.
 */
@Restricted(NoExternalUse.class)
public interface GrantLayeredStrategy {

    /**
     * A plain instance of the parent strategy with every entry of this one: the uninstall path of
     * the migration action (D-35a). The returned strategy confers nothing through grants.
     */
    @NonNull
    AuthorizationStrategy toPlainStrategy();
}
