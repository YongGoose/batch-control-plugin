package io.jenkins.plugins.batchcontrol.model;

import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Target scope of a grant request / grant: a single job or a folder subtree.
 *
 * <p>Folder matching is done on path-segment boundaries: scope {@code team/batch} includes
 * {@code team/batch} itself and {@code team/batch/job1}, but never {@code team/batch-other}.
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
     * </ul>
     */
    public boolean includes(String itemFullName) {
        if (itemFullName == null) {
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
