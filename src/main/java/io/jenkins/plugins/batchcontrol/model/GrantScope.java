package io.jenkins.plugins.batchcontrol.model;

import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Target scope of a grant request / grant: a single job or a folder subtree.
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

    /** Scope kind. */
    public enum Type {
        JOB,
        FOLDER
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
     *   <li>An empty scope name matches nothing at all, for either type — see below.</li>
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
        return itemFullName.equals(fullName) || itemFullName.startsWith(fullName + "/");
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
