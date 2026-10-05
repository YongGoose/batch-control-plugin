package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import java.util.HashMap;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-71a, D-71b: whether an open permission window still applies to its item, for display only.
 *
 * <p>A window confers something only on the item it was approved for. Once that item is renamed,
 * moved or deleted the grant layer unbinds the window for good (its recorded identity is cleared),
 * but the window stays listed as active until it ends or is revoked. The Grants list and the
 * request's detail page must not make such a window look active, so they ask this class and show
 * {@link #UNBOUND_LABEL} instead of the remaining time.
 *
 * <p>Read-only: nothing here changes a grant. The item is resolved as the viewer sees it
 * ({@link Visibility#findVisibleItem}), never as SYSTEM. When the viewer can read it, the grant
 * layer's own rule decides ({@link GrantService#isBoundTo}: recorded identity and kind match the
 * item now at that name). When the viewer cannot read it (or it is gone), the recorded identity
 * decides: the grant layer clears it when the item is deleted, renamed or moved (D-71b), so a
 * window still carrying one has not been unbound. A viewer therefore learns nothing about an item
 * they may not read beyond what the grant record itself says.
 */
@Restricted(NoExternalUse.class)
public final class WindowBinding {

    /** What an open window that no longer applies shows instead of its remaining time. */
    public static final String UNBOUND_LABEL = "No longer applies (the item was renamed, moved or deleted)";

    /** The marker value of a window that still applies ({@code data-batch-control-window-state}). */
    public static final String BOUND = "bound";

    /** The marker value of a window that no longer applies ({@code data-batch-control-window-state}). */
    public static final String UNBOUND = "unbound";

    private WindowBinding() {
    }

    /**
     * The grant layer's own copies of the windows active now, by id. Its copy is the one the
     * permission checks use: an unbinding takes effect there even when writing the grant's file
     * failed, so the screens prefer it over the stored file they list.
     */
    public static Map<String, Grant> effectiveActive() {
        Map<String, Grant> byId = new HashMap<>();
        for (Grant grant : GrantService.get().listActive()) {
            byId.put(grant.getId(), grant);
        }
        return byId;
    }

    /**
     * Whether the open window {@code stored} (as read from the store) still applies to its item.
     *
     * @param stored the window as listed
     * @param effective the grant layer's copy of the same window ({@link #effectiveActive()}), or
     *        {@code null} when it has none
     */
    public static boolean isBound(Grant stored, @CheckForNull Grant effective) {
        if (stored.getItemIdentity() == null || (effective != null && effective.getItemIdentity() == null)) {
            return false;
        }
        Grant grant = effective != null ? effective : stored;
        GrantScope scope = grant.getScope();
        if (scope == null || scope.getType() != GrantScope.Type.ITEM
                || scope.getFullName() == null || scope.getFullName().isEmpty()) {
            return false;
        }
        Item item = Visibility.findVisibleItem(scope.getFullName());
        if (item == null) {
            // Gone, or not readable by the viewer: the recorded identity decides (see the class
            // comment); a deleted item's windows were unbound when it was deleted.
            return true;
        }
        return scope.getFullName().equals(item.getFullName()) && GrantService.isBoundTo(grant, item);
    }
}
