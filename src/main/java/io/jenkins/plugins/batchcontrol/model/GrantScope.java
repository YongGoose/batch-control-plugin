package io.jenkins.plugins.batchcontrol.model;

import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Target scope of a grant request / grant: a single job, a folder subtree, or a folder and its
 * direct items (D-65).
 *
 * <p>Folder matching is done on path-segment boundaries: scope {@code team/batch} includes
 * {@code team/batch} itself and {@code team/batch/job1}, but never {@code team/batch-other}.
 *
 * <p>An empty full name (the Jenkins root) is not a valid scope: it is rejected when a grant
 * request is created and again when it is approved, and {@link #includes(String)} matches
 * nothing for it. There is no root-scope / instance-wide grant.
 */
@Restricted(NoExternalUse.class)
public final class GrantScope {

    /**
     * Scope kind. Stored by name in the existing {@code type} field, so files written before
     * {@link #FOLDER_ONLY} existed load unchanged (D-65).
     */
    public enum Type {
        /** The job with exactly this full name. */
        JOB,
        /** The folder and everything below it, nested folders included. */
        FOLDER,
        /** The folder itself and the items whose parent is that folder, not nested folders' contents (D-65). */
        FOLDER_ONLY
    }

    private final Type type;
    private final String fullName;

    public GrantScope(Type type, String fullName) {
        this.type = Objects.requireNonNull(type, "type");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
    }

    public Type getType() {
        return type;
    }

    public String getFullName() {
        return fullName;
    }

    /**
     * Whether the given item full name falls inside this scope.
     *
     * <ul>
     *   <li>{@code JOB}: exact match only.</li>
     *   <li>{@code FOLDER}: the folder itself (CREATE is checked on the folder ACL)
     *       and any descendant, with a {@code /} segment-boundary check.</li>
     *   <li>{@code FOLDER_ONLY} (D-65): the folder itself and its direct items ({@code f/x}, never
     *       {@code f/sub/x}).</li>
     *   <li>An empty scope name matches nothing at all, for any type — see below.</li>
     * </ul>
     */
    public boolean includes(String itemFullName) {
        if (itemFullName == null) {
            return false;
        }
        // S-13: an empty scope name (the Jenkins root item-group) matches NOTHING. Root-scope
        // grants are not a supported capability: GrantRequestService rejects an empty scope both
        // at creation and at approval, so this state cannot be produced through the plugin. The
        // guard stays because XStream rebuilds persisted Grant/GrantRequest objects without
        // running the constructor, so a hand-edited or pre-S-03 store file could still carry
        // GrantScope("") — and the safe answer for such a scope is "includes nothing", never the
        // instance-wide "includes everything" this branch used to return.
        if (fullName.isEmpty()) {
            return false;
        }
        if (type == Type.JOB) {
            return fullName.equals(itemFullName);
        }
        if (itemFullName.equals(fullName)) {
            return true;
        }
        String prefix = fullName + "/";
        if (!itemFullName.startsWith(prefix)) {
            return false;
        }
        return type == Type.FOLDER || itemFullName.indexOf('/', prefix.length()) < 0;
    }

    /**
     * Whether a DELETE action of this scope confers Item/Delete on {@code itemFullName}. For
     * {@code FOLDER_ONLY} only a direct item that is not itself an item group: deleting the folder
     * or a nested folder (any {@code ItemGroup}, a multibranch project included) would delete
     * items outside the scope (D-65 owner ruling). For the other types it is
     * {@link #includes(String)}.
     *
     * @param itemIsGroup whether the item is an item group; callers that cannot tell pass
     *                    {@code true} (fail-safe)
     */
    public boolean includesDeleteOf(String itemFullName, boolean itemIsGroup) {
        if (type == Type.FOLDER_ONLY) {
            return !itemIsGroup && itemFullName != null && !itemFullName.equals(fullName) && includes(itemFullName);
        }
        return includes(itemFullName);
    }

    /**
     * Whether a CREATE action of this scope confers Item/Create in the item group
     * {@code groupFullName}, i.e. whether an item created directly in that group falls inside
     * this scope. For {@code FOLDER_ONLY} that is the folder itself only: a nested folder is a
     * direct item, but what is created in it lies outside the scope (D-65). For the other types it
     * is {@link #includes(String)} of the group, as before.
     */
    public boolean includesCreateIn(String groupFullName) {
        if (type == Type.FOLDER_ONLY) {
            return groupFullName != null && !fullName.isEmpty() && fullName.equals(groupFullName);
        }
        return includes(groupFullName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GrantScope)) {
            return false;
        }
        GrantScope other = (GrantScope) o;
        return type == other.type && fullName.equals(other.fullName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, fullName);
    }

    @Override
    public String toString() {
        return type + ":" + fullName;
    }
}
