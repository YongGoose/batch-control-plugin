package io.jenkins.plugins.batchcontrol.listener;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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
     * The id of the current user's active grant matching the item and action, or {@code null}
     * (SPEC item 9: link the grant when one covers the change, else {@code grantId=null}).
     * A {@code null} action matches any granted action.
     */
    @CheckForNull
    static String activeGrantIdFor(String user, String itemFullName, @CheckForNull GrantAction action) {
        Grant grant = GrantService.get().findActiveGrant(user, itemFullName, action);
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
