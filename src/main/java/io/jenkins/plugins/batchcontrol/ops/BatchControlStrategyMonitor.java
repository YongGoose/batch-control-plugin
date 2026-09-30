package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.security.AuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.Messages;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.StrategyMigration;
import io.jenkins.plugins.batchcontrol.security.SystemBuildCheck;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * "Change control is on, but the installed authorization strategy is not a Batch Control
 * strategy" (D-35a, ARCHITECTURE section 4). Grants confer nothing then. This covers an instance
 * that never migrated, a withdrawn wrapper whose delegate had no Batch Control variant, and
 * role-strategy's Manage Roles save, which reinstalls the plain class.
 *
 * <p>It also warns while change control is on and no build authenticator is configured, so builds
 * run as SYSTEM (D-35d (2), {@link #isBuildAuthenticatorMissing()}).
 *
 * <p>{@link #doMigrate()} copies an installed plain matrix-auth matrix (project-based, or global:
 * then per-item properties become effective, {@link #isPerItemWidening()}) or role-strategy
 * configuration into the matching Batch Control subclass, keeping every entry; {@link #doRevert()}
 * is the reverse, the uninstall path. Neither touches per-item properties, which live on the
 * items. Neither switches to {@code ACL.SYSTEM2}: an administrator runs them and
 * {@link Jenkins#save()} needs no further permission.
 *
 * <p>Both actions work whether or not the monitor is activated: its URL
 * ({@code /manage/administrativeMonitor/batch-control-strategy/}) only requires Overall/Administer,
 * and the revert button lives on the Batch Control global configuration page, where a Batch
 * Control strategy is installed and the monitor is therefore quiet. After success both redirect
 * back to the referring page of this Jenkins, or to the global security page.
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

    /**
     * Shows while change control is on and either the installed strategy is not a Batch Control
     * strategy ({@link #isStrategyUnsupported()}) or builds run as SYSTEM
     * ({@link #isBuildAuthenticatorMissing()}).
     */
    @Override
    public boolean isActivated() {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                && (strategyUnsupported() || buildAuthenticatorMissing()
                        || SystemBuildCheck.buildsMayRunAsSystem()
                        // D-58a (4): answered from the in-memory grant cache, no disk scan per page.
                        || !io.jenkins.plugins.batchcontrol.security.GrantService.get()
                                .itemsChangedUnderGrant(1).isEmpty());
    }

    /**
     * Condition 3 (D-50, D-50a): with change control on, a build of a job without its own build
     * authorization and without a user cause would run as SYSTEM under the configured build
     * authenticators (for example Authorize Project per-project, or a strategy that follows the
     * triggering user). Instance-wide and cached ({@link SystemBuildCheck}), so rendering a page
     * never probes on every call.
     */
    public boolean isBuildsMayRunAsSystem() {
        return SystemBuildCheck.buildsMayRunAsSystem();
    }

    /**
     * Condition 1 (D-35a): change control is on, but the installed strategy is not a Batch Control
     * strategy, so active grants confer nothing (for the view).
     */
    public boolean isStrategyUnsupported() {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled() && strategyUnsupported();
    }

    /**
     * Condition 2 (D-35d (2), S-02): change control is on and no {@link QueueItemAuthenticator} is
     * configured, so builds run as SYSTEM. A build running as SYSTEM (a Pipeline
     * {@code properties([authorizationMatrix(...)])} step, a Job DSL seed job) can then write an
     * authorization property after a Configure grant holder edited the script, and the D-35b guard
     * does not apply to SYSTEM saves. Configuring a build authenticator (Authorize Project) makes
     * those saves the user's, so the guard applies. Only a warning: SYSTEM saves are not blocked.
     */
    public boolean isBuildAuthenticatorMissing() {
        return BatchControlGlobalConfiguration.get().isChangeControlEnabled() && buildAuthenticatorMissing();
    }

    private static boolean strategyUnsupported() {
        return !GrantLayer.isGrantLayered(Jenkins.get().getAuthorizationStrategy());
    }

    /**
     * Whether no build authenticator is configured on the global security page
     * ({@link QueueItemAuthenticatorConfiguration}, where Authorize Project registers its
     * strategies). Other {@link jenkins.security.QueueItemAuthenticatorProvider}s are not counted:
     * Pipeline's own provider only hands a {@code node} block the authentication its build already
     * runs as, so with it alone builds still run as SYSTEM.
     */
    private static boolean buildAuthenticatorMissing() {
        return QueueItemAuthenticatorConfiguration.get().getAuthenticators().isEmpty();
    }

    /** Whether the installed strategy can be copied into a Batch Control subclass (for the view). */
    public boolean isMigratable() {
        return StrategyMigration.isMigratable(Jenkins.get().getAuthorizationStrategy());
    }

    /**
     * Whether the migration would make per-item authorization properties effective (D-35d (3),
     * S-04): the installed strategy is matrix-auth's global matrix, which ignores job, folder and
     * agent properties, while the Batch Control matrix is project-based (for the view's warning).
     */
    public boolean isPerItemWidening() {
        return StrategyMigration.isPerItemWidening(Jenkins.get().getAuthorizationStrategy());
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
     * Installs the Batch Control subclass of the current plain matrix-auth (project or global) or
     * role-strategy strategy with every entry kept, and saves. Other strategies are refused.
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
                    + ") has no Batch Control variant, or its plugin is not installed. Supported: "
                    + "matrix-auth's project-based and global matrix, and role-strategy's role-based "
                    + "strategy.");
        }
        jenkins.setAuthorizationStrategy(migrated);
        jenkins.save();
        LOGGER.info(() -> "Authorization strategy " + current.getClass().getName() + " migrated to "
                + migrated.getClass().getName() + " by " + Jenkins.getAuthentication2().getName());
        recordStrategyChange("installed", current, migrated);
        return backToReferrer();
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
        recordStrategyChange("reverted", current, plain);
        return backToReferrer();
    }

    /**
     * D-58a: the items in the "changed under a grant" state (saved or created under a grant and not
     * reviewed since by an administrator or a native Configure holder through the web), sorted, at
     * most 50. They stay guarded until reviewed. Administrator-only page.
     */
    public java.util.List<String> getItemsChangedUnderGrant() {
        return io.jenkins.plugins.batchcontrol.security.GrantService.get().itemsChangedUnderGrant(50);
    }

    /**
     * D-58b (3): "Mark as reviewed" for one listed item. Administrators only; the item and
     * everything below it leave the "changed under a grant" state and a GUARD_REVIEWED record is
     * written. Answers 404 for an item that does not exist.
     */
    @RequirePOST
    public HttpResponse doMarkReviewed(@org.kohsuke.stapler.QueryParameter String item) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        hudson.model.Item target = item == null ? null : Jenkins.get().getItemByFullName(item);
        if (target == null) {
            return HttpResponses.error(404, "No item named '" + item + "'.");
        }
        io.jenkins.plugins.batchcontrol.security.GrantService.get().markReviewed(target);
        return backToReferrer();
    }

    /** The {@code target} of a {@link ChangeType#STRATEGY_CHANGE} record (D-52). */
    public static final String STRATEGY_CHANGE_TARGET = "authorization-strategy";

    /**
     * D-52: one {@link ChangeType#STRATEGY_CHANGE} record for an install or revert, written after
     * the strategy is saved. A store failure is logged and does not undo the change.
     */
    private static void recordStrategyChange(String what, AuthorizationStrategy before, AuthorizationStrategy after) {
        String user = Jenkins.getAuthentication2().getName();
        try {
            Store.get().appendChangeRecord(ChangeRecord.create(ChangeType.STRATEGY_CHANGE, STRATEGY_CHANGE_TARGET,
                    user, "Batch Control authorization strategy " + what + ": "
                            // S-25-07: display names only; the record is shown to every ViewHistory holder.
                            + (before == null ? "none" : before.getDescriptor().getDisplayName())
                            + " -> " + after.getDescriptor().getDisplayName()));
        } catch (RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not record the authorization strategy change", e);
        }
    }

    /** Fallback target when there is no usable referrer. */
    static final String FALLBACK = "/manage/configureSecurity";

    /**
     * Redirects to the path of the referring page when it belongs to this Jenkins, else to the
     * global security page. Only a path below the context path is followed, never a host, so the
     * redirect cannot leave this Jenkins (no open redirect through a forged {@code Referer}).
     */
    private static HttpResponse backToReferrer() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String target = req == null ? null : sameOriginPath(req.getHeader("Referer"), req.getContextPath());
        return target == null ? HttpResponses.redirectViaContextPath(FALLBACK) : HttpResponses.redirectTo(target);
    }

    /**
     * The path (and query) of {@code referer} if it is a path of this Jenkins below
     * {@code contextPath}; {@code null} otherwise.
     */
    static String sameOriginPath(String referer, String contextPath) {
        if (referer == null || referer.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(referer);
        } catch (URISyntaxException e) {
            return null;
        }
        String path = uri.getRawPath();
        String context = contextPath == null ? "" : contextPath;
        if (path == null || !path.startsWith(context + "/") || path.startsWith("//")
                || path.indexOf('\\') >= 0) {
            return null;
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }
}
