package io.jenkins.plugins.batchcontrol.model;

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ModifiableItemGroup;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Target of a grant request / grant: exactly one item, a job or a folder of any kind (D-71,
 * replaces the JOB, FOLDER and FOLDER_ONLY scopes of D-65). A window confers nothing on any other
 * item, including the items inside a folder.
 *
 * <ul>
 *   <li>CONFIGURE (and EXTENDED_READ through the {@code impliedBy} walk, P-11) is answered on the
 *       item's own ACL: {@link #includes(String)}.</li>
 *   <li>CREATE is checked by core on the ACL of the group the new item is created in, so a CREATE
 *       window, which can only exist on a regular folder ({@link #createAppliesTo(Item)}), admits
 *       creation directly inside that folder only: {@link #includes(String)} of the group.</li>
 *   <li>DELETE can only exist on a job ({@link #deleteAppliesTo(Item)}): deleting an item group
 *       that is not a job deletes its children as SYSTEM without checking them.</li>
 *   <li>D-35c: the items its holder created through a CREATE window are matched by parent,
 *       {@link #isParentOf(String)}, not by name prefix.</li>
 * </ul>
 *
 * <p>An empty full name (the Jenkins root) is not a valid scope: it is rejected when a grant
 * request is created and again when it is approved, and {@link #includes(String)} matches
 * nothing for it. There is no root-scope / instance-wide grant.
 */
@Restricted(NoExternalUse.class)
public final class GrantScope {

    /**
     * Scope kind. Stored by name in the {@code type} field. Files written with the earlier values
     * ({@code JOB}, {@code FOLDER}, {@code FOLDER_ONLY}) are not converted (D-69, D-71).
     */
    public enum Type {
        /** The one item with exactly this full name (D-71). */
        ITEM
    }

    private final Type type;
    private final String fullName;

    public GrantScope(Type type, String fullName) {
        this.type = Objects.requireNonNull(type, "type");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
    }

    /** The scope naming exactly the item {@code fullName} (D-71). */
    public static GrantScope item(String fullName) {
        return new GrantScope(Type.ITEM, fullName);
    }

    public Type getType() {
        return type;
    }

    public String getFullName() {
        return fullName;
    }

    /**
     * Whether the given item full name is the scope item: an exact full-name match (D-71). An
     * empty scope name matches nothing at all — see below.
     */
    public boolean includes(String itemFullName) {
        if (itemFullName == null) {
            return false;
        }
        // S-13: an empty scope name (the Jenkins root item-group) matches NOTHING. Root-scope
        // grants are not a supported capability: GrantRequestService rejects an empty scope both
        // at creation and at approval, so this state cannot be produced through the plugin. The
        // guard stays because XStream rebuilds persisted Grant/GrantRequest objects without
        // running the constructor, so a hand-edited store file could still carry GrantScope("")
        // — and the safe answer for such a scope is "includes nothing".
        if (fullName == null || fullName.isEmpty() || type != Type.ITEM) {
            return false;
        }
        return fullName.equals(itemFullName);
    }

    /**
     * D-35c (as amended by D-71): whether the scope item is the parent of {@code itemFullName},
     * i.e. the item lies directly inside the scope folder ({@code f/x}, never {@code f/sub/x} and
     * never {@code f} itself).
     */
    public boolean isParentOf(@CheckForNull String itemFullName) {
        if (itemFullName == null || !includes(parentOf(itemFullName))) {
            return false;
        }
        return itemFullName.length() > fullName.length() + 1;
    }

    /** The full name of the group {@code itemFullName} lies in ({@code ""} for a root item). */
    public static String parentOf(String itemFullName) {
        int slash = itemFullName.lastIndexOf('/');
        return slash < 0 ? "" : itemFullName.substring(0, slash);
    }

    /**
     * D-71: whether a window's CREATE can apply to {@code item}: a modifiable item group that is
     * neither a job nor a computed folder (a regular folder; not a multibranch project or an
     * organization folder, whose children are created by indexing).
     */
    public static boolean createAppliesTo(@CheckForNull Item item) {
        return item instanceof ModifiableItemGroup && !(item instanceof Job) && !(item instanceof ComputedFolder);
    }

    /**
     * D-71: whether a window's DELETE can apply to {@code item}: a {@link Job} only, including
     * multi-configuration and Maven projects, whose sub-items are part of the job. Never an item
     * group that is not a job (a folder, a multibranch project, an organization folder): core's
     * {@code AbstractItem.delete()} deletes such a group's children as SYSTEM without checking them.
     */
    public static boolean deleteAppliesTo(@CheckForNull Item item) {
        return item instanceof Job;
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
