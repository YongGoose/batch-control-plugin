package io.jenkins.plugins.batchcontrol.listener;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Shared state and helpers of the change-recording listeners (SPEC item 9).
 *
 * <p>Recording is active while ANY global switch is on (SPEC item 9 last criterion, D-13) and
 * fully off when both are off, in which case the plugin writes nothing at all.
 *
 * <p>The suppression flag guards internal plugin-initiated saves (the D-31/D-34
 * activation-lock property written on every job creation) against being recorded as user CONFIGURE changes — and against listener recursion.
 */
@Restricted(NoExternalUse.class)
final class ChangeRecording {

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ChangeRecording() {
    }

    /** Whether change recording is active: any switch on (D-13). */
    static boolean isActive() {
        BatchControlGlobalConfiguration cfg = BatchControlGlobalConfiguration.get();
        return cfg.isRunControlEnabled() || cfg.isChangeControlEnabled();
    }

    /** The acting user id from the current authentication. */
    static String currentUser() {
        return Jenkins.getAuthentication2().getName();
    }

    /**
     * The id of the current user's active grant naming {@code itemFullName}, bound to {@code item}
     * (D-71a), and matching the action, or {@code null} (SPEC item 9: link the grant when one
     * covers the change, else {@code grantId=null}). {@code itemFullName} may be the item's name
     * before a rename or move. A {@code null} action matches any granted action.
     */
    @CheckForNull
    static String activeGrantIdFor(String user, String itemFullName, Item item, @CheckForNull GrantAction action) {
        Grant grant = GrantService.get().findActiveGrant(user, itemFullName, item, action);
        return grant == null ? null : grant.getId();
    }

    /**
     * The id of the active CREATE window through which {@code user} created {@code item}, or
     * {@code null} (SPEC item 9). A CREATE window names the folder the item is created in (D-71),
     * so the lookup takes the item's parent: preferably the window whose name restriction admits
     * the item's name, else any CREATE window of the user on that folder.
     */
    @CheckForNull
    static String createGrantIdFor(String user, Item item) {
        if (!(item.getParent() instanceof Item)) {
            return null; // no root-scope grant exists (S-13)
        }
        // D-71a: the window must name the parent folder and be bound to it.
        Item group = (Item) item.getParent();
        Grant grant = GrantService.get().findActiveCreateGrant(user, item.getParent(), item.getName());
        if (grant == null) {
            java.util.List<Grant> windows = GrantService.get().findActiveGrants(user, group, GrantAction.CREATE);
            grant = windows.isEmpty() ? null : windows.get(0);
        }
        return grant == null ? null : grant.getId();
    }

    /**
     * The id of the active grant through which {@code user} holds Item/Configure on {@code item},
     * or {@code null}: a grant with the CONFIGURE action covering the item, or the Create grant
     * through which the user created it, which confers Configure on that item while it is active
     * (D-35c; e2e-03 DEF-05). The CONFIGURE record links it like any other grant (SPEC item 9).
     */
    @CheckForNull
    static String configureGrantIdFor(String user, Item item) {
        Grant grant = GrantService.get().findConfigureGrant(user, item);
        return grant == null ? null : grant.getId();
    }

    /**
     * Suppresses recording on this thread (plugin-internal saves) and returns the state to
     * restore. Always pair with {@link #endSuppression(boolean)} in a {@code finally} block,
     * passing the value this method returned:
     *
     * <pre>boolean previous = beginSuppression();
     *try { … } finally { endSuppression(previous); }</pre>
     *
     * <p>The returned token is what makes the pair nestable (S-19). An inner
     * {@code begin}/{@code end} pair that unconditionally cleared the flag would also end an
     * outer suppression that is still meant to be in effect, and the outer caller's remaining
     * internal saves would then be recorded as user CONFIGURE changes.
     */
    static boolean beginSuppression() {
        boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        return previous;
    }

    /**
     * Restores the suppression state captured by {@link #beginSuppression()}.
     *
     * <p>Restoring "not suppressed" removes the entry rather than storing {@code FALSE}: the
     * suppressed window is short-lived while the threads that enter it (HTTP request handlers,
     * queue threads) are pooled and long-lived, so leaving a mapping behind on every one of them
     * is a leak with no purpose — {@code withInitial} already answers {@code FALSE} for a thread
     * with no entry.
     */
    static void endSuppression(boolean previous) {
        if (previous) {
            SUPPRESSED.set(Boolean.TRUE);
        } else {
            SUPPRESSED.remove();
        }
    }

    static boolean isSuppressed() {
        return SUPPRESSED.get();
    }
}
