package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-75 (1), security-39 S-39-04 (SPEC item 8): the name a screen shows for the item of a grant
 * request or permission window ({@code tags/scopeItem.jelly}).
 *
 * <p>A window follows its item when an administrator (or a user with their own permissions)
 * renames or moves it (D-74 (3)), so its current full name can differ from the name the request
 * was approved for. The followed name is shown only to a viewer who may read the item now:
 * {@link Visibility#findVisibleItem} finds it as the viewer and the viewer holds Item/Read on it,
 * or the viewer is an administrator (who sees the current name even after the item was deleted).
 * Anyone else sees the approved name with a fixed note that the item was moved and its new
 * location is not visible to them; nothing else about the new location is passed to the view
 * (no link, no title, no kind resolved from the moved item). When the name did not change,
 * nothing changes: the approved name is shown to every viewer of the request as before.
 *
 * <p>A window whose request is no longer stored has no approved name. Its name is then shown only
 * under the same rule, and otherwise not at all ({@link #isNameHidden()}).
 *
 * <p>Read-only and decided per rendering, as the viewer; no state, no permission switch.
 */
@Restricted(NoExternalUse.class)
public final class ScopeDisplay {

    @CheckForNull
    private final String fullName;

    private final boolean moved;

    private ScopeDisplay(@CheckForNull String fullName, boolean moved) {
        this.fullName = fullName;
        this.moved = moved;
    }

    /**
     * The name to show for a request approved for {@code approved} whose window now names
     * {@code current}.
     *
     * @param approved the item the request was made for, or {@code null} when the request is not
     *                 stored any more
     * @param current  the window's item ({@code Grant#getScope()}), or {@code null} before there is
     *                 a window
     */
    public static ScopeDisplay of(@CheckForNull GrantScope approved, @CheckForNull GrantScope current) {
        return of(approved == null ? null : approved.getFullName(), current == null ? null : current.getFullName());
    }

    /** {@link #of(GrantScope, GrantScope)} by full names. */
    public static ScopeDisplay of(@CheckForNull String approvedName, @CheckForNull String currentName) {
        if (currentName == null || currentName.equals(approvedName)) {
            return new ScopeDisplay(currentName == null ? approvedName : currentName, false);
        }
        if (mayRead(currentName)) {
            return new ScopeDisplay(currentName, false);
        }
        return new ScopeDisplay(approvedName, true);
    }

    /**
     * Whether the viewer may read the item at {@code fullName} now: an administrator, or a viewer
     * who finds it ({@link Visibility#findVisibleItem}: Item/Read on it and on every folder above
     * it; Item/Discover alone is not enough) and holds Item/Read on it.
     */
    static boolean mayRead(String fullName) {
        if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return true;
        }
        Item item = Visibility.findVisibleItem(fullName);
        return item != null && item.hasPermission(Item.READ);
    }

    /**
     * The full name to show: the window's current one when it did not change or the viewer may
     * read the item, otherwise the approved one. {@code null} only for a window whose request is
     * no longer stored and whose item the viewer may not read.
     */
    @CheckForNull
    public String getFullName() {
        return fullName;
    }

    /**
     * Whether the item was renamed or moved where the viewer may not read it, so the screen shows
     * {@link #getFullName()} (the approved name) with the fixed note instead of the current name.
     */
    public boolean isMoved() {
        return moved;
    }

    /** Whether no name can be shown at all (a moved window whose request is no longer stored). */
    public boolean isNameHidden() {
        return moved && fullName == null;
    }
}
