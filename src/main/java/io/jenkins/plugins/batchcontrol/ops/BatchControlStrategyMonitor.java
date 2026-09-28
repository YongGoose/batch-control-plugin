package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.StrategyMigration;
import java.io.IOException;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * "Change control is on, but the installed authorization strategy is not a Batch Control
 * strategy" (D-35a, ARCHITECTURE section 4). Grants confer nothing then. This covers an instance
 * that never migrated, a withdrawn wrapper whose delegate had no Batch Control variant, and
 * role-strategy's Manage Roles save, which reinstalls the plain class.
 *
 * <p>{@link #doMigrate()} copies an installed plain matrix-auth project matrix or role-strategy
 * configuration into the matching Batch Control subclass, keeping every entry; {@link #doRevert()}
 * is the reverse, the uninstall path. Neither touches per-item properties, which live on the
 * items. Neither switches to {@code ACL.SYSTEM2}: an administrator runs them and
 * {@link Jenkins#save()} needs no further permission.
 *
 * <p>matrix-auth and role-strategy are optional: this class refers to neither, see
 * {@link StrategyMigration}.
 */
@Extension
@Restricted(NoExternalUse.class)
public class BatchControlStrategyMonitor extends AdministrativeMonitor {

    private static final Logger LOGGER = Logger.getLogger(BatchControlStrategyMonitor.class.getName());

    /** The monitor id, also its URL segment under {@code /administrativeMonitor/}. */
    public static final String ID = "batch-control-strategy";

    public BatchControlStrategyMonitor() {
        super(ID);
    }

    @Override
    public String getDisplayName() {
        return Messages.BatchControlStrategyMonitor_DisplayName();
    }

    @Override
    public boolean isActivated() {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                && !GrantLayer.isGrantLayered(Jenkins.get().getAuthorizationStrategy());
    }

    /** Whether the installed strategy can be copied into a Batch Control subclass (for the view). */
    public boolean isMigratable() {
        return StrategyMigration.isMigratable(Jenkins.get().getAuthorizationStrategy());
    }

    /** Whether a Batch Control strategy is installed, so that {@link #doRevert()} applies. */
    public boolean isRevertable() {
        return GrantLayer.isGrantLayered(Jenkins.get().getAuthorizationStrategy());
    }

    /** The display name of the installed strategy (for the view). */
    @CheckForNull
    public String getInstalledStrategyName() {
        AuthorizationStrategy strategy = Jenkins.get().getAuthorizationStrategy();
        return strategy == null ? null : strategy.getDescriptor().getDisplayName();
    }

    /**
     * Installs the Batch Control subclass of the current plain matrix-auth or role-strategy
     * strategy with every entry kept, and saves. Other strategies are refused.
     */
    @RequirePOST
    public HttpResponse doMigrate() throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        Jenkins jenkins = Jenkins.get();
        AuthorizationStrategy current = jenkins.getAuthorizationStrategy();
        AuthorizationStrategy migrated = StrategyMigration.toBatchControl(current);
        if (migrated == null) {
            return HttpResponses.error(400, "The installed authorization strategy ("
                    + (current == null ? "none" : current.getClass().getName())
                    + ") has no Batch Control variant. Supported: matrix-auth's project-based "
                    + "matrix and role-strategy's role-based strategy.");
        }
        jenkins.setAuthorizationStrategy(migrated);
        jenkins.save();
        LOGGER.info(() -> "Authorization strategy " + current.getClass().getName() + " migrated to "
                + migrated.getClass().getName() + " by " + Jenkins.getAuthentication2().getName());
        return HttpResponses.redirectViaContextPath("/manage");
    }

    /**
     * Installs the plain parent strategy of the current Batch Control strategy with every entry
     * kept, and saves (the uninstall path). Grants stop conferring immediately.
     */
    @RequirePOST
    public HttpResponse doRevert() throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        Jenkins jenkins = Jenkins.get();
        AuthorizationStrategy current = jenkins.getAuthorizationStrategy();
        AuthorizationStrategy plain = StrategyMigration.toPlain(current);
        if (plain == null) {
            return HttpResponses.error(400, "The installed authorization strategy is not a Batch "
                    + "Control strategy; there is nothing to revert.");
        }
        jenkins.setAuthorizationStrategy(plain);
        jenkins.save();
        LOGGER.info(() -> "Authorization strategy " + current.getClass().getName() + " reverted to "
                + plain.getClass().getName() + " by " + Jenkins.getAuthentication2().getName());
        return HttpResponses.redirectViaContextPath("/manage");
    }
}
