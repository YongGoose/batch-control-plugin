package io.jenkins.plugins.batchcontrol.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The kind of the item a permission window names (D-71): the item's {@link TopLevelItemDescriptor}
 * id, display name and icon, recorded when the request is created and copied to the grant at
 * approval. Approval compares the recorded descriptor id with the item's current one, so a window
 * is never approved on an item that was replaced by one of another kind under the same name.
 *
 * <p>Immutable; persisted by XStream as three strings inside the grant request and grant files.
 */
@Restricted(NoExternalUse.class)
public final class ItemKind {

    private static final Logger LOGGER = Logger.getLogger(ItemKind.class.getName());

    private final String descriptorId;
    private final String displayName;
    @CheckForNull
    private final String iconClassName;

    public ItemKind(String descriptorId, String displayName, @CheckForNull String iconClassName) {
        this.descriptorId = Objects.requireNonNull(descriptorId, "descriptorId");
        this.displayName = displayName == null || displayName.isBlank() ? descriptorId : displayName;
        this.iconClassName = iconClassName == null || iconClassName.isBlank() ? null : iconClassName;
    }

    /**
     * The kind of {@code item}, read from its descriptor, or {@code null} when the item is not a
     * top-level item (a sub-item of a job, such as a matrix configuration or a Maven module, which
     * is part of that job and cannot be named by a window).
     */
    @CheckForNull
    public static ItemKind of(@CheckForNull Item item) {
        if (!(item instanceof TopLevelItem)) {
            return null;
        }
        TopLevelItemDescriptor descriptor = ((TopLevelItem) item).getDescriptor();
        if (descriptor == null) {
            return null;
        }
        String icon;
        try {
            icon = descriptor.getIconClassName();
        } catch (RuntimeException e) {
            // A descriptor's icon is decoration; the kind is still recorded without it.
            LOGGER.log(Level.FINE, "No icon for " + descriptor.getId(), e);
            icon = null;
        }
        return new ItemKind(descriptor.getId(), descriptor.getDisplayName(), icon);
    }

    /**
     * The descriptor id, for example {@code org.jenkinsci.plugins.workflow.job.WorkflowJob},
     * {@code hudson.model.FreeStyleProject} or {@code com.cloudbees.hudson.plugins.folder.Folder}.
     */
    public String getDescriptorId() {
        return descriptorId;
    }

    /** The descriptor display name when recorded, for example "Pipeline", "Freestyle project" or "Folder". */
    public String getDisplayName() {
        return displayName == null ? descriptorId : displayName;
    }

    /** The descriptor's icon class name, or {@code null} when it has none. */
    @CheckForNull
    public String getIconClassName() {
        return iconClassName;
    }

    /**
     * Whether {@code item} currently has this kind (the same descriptor id). Cheap: only the
     * descriptor id is read (D-71a calls this on the permission-check path).
     */
    public boolean matches(@CheckForNull Item item) {
        if (!(item instanceof TopLevelItem)) {
            return false;
        }
        TopLevelItemDescriptor descriptor = ((TopLevelItem) item).getDescriptor();
        return descriptor != null && descriptorId.equals(descriptor.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemKind)) {
            return false;
        }
        ItemKind other = (ItemKind) o;
        return descriptorId.equals(other.descriptorId)
                && Objects.equals(getDisplayName(), other.getDisplayName())
                && Objects.equals(iconClassName, other.iconClassName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(descriptorId, getDisplayName(), iconClassName);
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
