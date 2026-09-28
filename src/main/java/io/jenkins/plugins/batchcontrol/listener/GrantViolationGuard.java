package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.BulkChange;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import hudson.security.ACL;
import hudson.security.AuthorizationMatrixProperty;
import hudson.security.AuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-35b: a user whose Item/Configure comes only from a grant cannot turn it into a permanent
 * authorization entry. matrix-auth lets anyone with Item/Configure edit the item's
 * {@code AuthorizationMatrixProperty} (job) or the folder's; when a save by such a user changed
 * that property, the previous property is put back and a {@code GRANT_VIOLATION} change record
 * names the user, the item and the grant.
 *
 * <p>"Only from a grant" is decided after the previous property is back in place: if the user
 * still has Item/Configure with every grant layer switched off, the change was the user's to make
 * and it is re-applied. Otherwise it stays reverted.
 *
 * <p>The previous state is an in-memory baseline of every job's and folder's property (its XML
 * form, or empty for none), filled when items load or are created and updated after every save.
 * An item not yet in the baseline is only recorded, never reverted, so an unknown state is
 * never overwritten. The baseline is kept whatever the switches say, because the switch may be
 * turned on later; the guard itself acts only while change control is on and a Batch Control
 * matrix strategy is installed (grants confer nothing otherwise).
 *
 * <p>The ordinal puts the guard ahead of {@link ConfigSnapshotListener}, which then reads the
 * restored file, so the CONFIGURE record shows what actually stays. The restoring save itself is
 * suppressed from recording.
 *
 * <p>matrix-auth is optional: this class and its nested listener are optional extensions and
 * are simply not loaded without it.
 */
@Extension(optional = true, ordinal = 1000)
@Restricted(NoExternalUse.class)
public class GrantViolationGuard extends SaveableListener {

    private static final Logger LOGGER = Logger.getLogger(GrantViolationGuard.class.getName());

    /** Item full name to the XML of its authorization property ({@code ""} for none). */
    private static final Map<String, String> BASELINE = new ConcurrentHashMap<>();

    /** Serializes compare, restore and baseline update. */
    private static final Object LOCK = new Object();

    /** Set while the guard itself saves an item, so that save is not examined again. */
    private static final ThreadLocal<Boolean> RESTORING = new ThreadLocal<>();

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (!(o instanceof Job || o instanceof AbstractFolder) || RESTORING.get() != null) {
            return;
        }
        AbstractItem item = (AbstractItem) o;
        String fullName = item.getFullName();
        synchronized (LOCK) {
            String now = propertyXml(item);
            String before = BASELINE.put(fullName, now);
            if (before == null || before.equals(now) || !guardApplies()) {
                return;
            }
            Authentication auth = Jenkins.getAuthentication2();
            if (ACL.SYSTEM2.equals(auth) || ACL.isAnonymous2(auth)) {
                return;
            }
            String user = auth.getName();
            Grant grant = GrantService.get().findConfigureGrant(user, fullName);
            if (grant == null) {
                return; // not a save a grant made possible
            }
            try {
                apply(item, before);
                if (GrantLayer.hasPermissionWithoutGrants(item, auth, Item.CONFIGURE)) {
                    apply(item, now); // natively allowed: the change stands
                    return;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Could not restore the authorization property of '" + fullName
                        + "' after a change by '" + user + "' under grant " + grant.getId()
                        + " (D-35b)", e);
                return;
            }
            BASELINE.put(fullName, before);
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user,
                    "The authorization property was changed by a user whose Item/Configure comes only "
                            + "from grant " + grant.getId() + "; the previous property was restored.");
            record.setGrantId(grant.getId());
            FileStore.get().appendChangeRecord(record);
            LOGGER.warning(() -> "Reverted a change of the authorization property of '" + fullName
                    + "' by '" + user + "', whose Item/Configure comes only from grant "
                    + grant.getId() + " (D-35b)");
        }
    }

    private static boolean guardApplies() {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return false;
        }
        AuthorizationStrategy strategy = Jenkins.get().getAuthorizationStrategy();
        return GrantLayer.isGrantLayered(strategy) && strategy instanceof ProjectMatrixAuthorizationStrategy;
    }

    /** The XML form of the item's matrix-auth property, or {@code ""} when it has none. */
    static String propertyXml(AbstractItem item) {
        Object property = property(item);
        return property == null ? "" : Items.XSTREAM2.toXML(property);
    }

    @CheckForNull
    private static Object property(AbstractItem item) {
        if (item instanceof Job) {
            return ((Job<?, ?>) item).getProperty(AuthorizationMatrixProperty.class);
        }
        if (item instanceof AbstractFolder) {
            return ((AbstractFolder<?>) item).getProperties()
                    .get(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
        }
        return null;
    }

    /** Replaces the item's property with the one {@code xml} describes ({@code ""}: none), one save. */
    private static void apply(AbstractItem item, String xml) throws IOException {
        Object property = xml.isEmpty() ? null : Items.XSTREAM2.fromXML(xml);
        RESTORING.set(Boolean.TRUE);
        boolean previouslySuppressed = ChangeRecording.beginSuppression();
        try (BulkChange bc = new BulkChange(item)) {
            if (item instanceof Job) {
                Job<?, ?> job = (Job<?, ?>) item;
                job.removeProperty(AuthorizationMatrixProperty.class);
                if (property != null) {
                    job.addProperty((JobProperty) property);
                }
            } else {
                AbstractFolder<?> folder = (AbstractFolder<?>) item;
                folder.getProperties()
                        .remove(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
                if (property != null) {
                    folder.addProperty(
                            (com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty) property);
                }
            }
            bc.commit();
        } finally {
            ChangeRecording.endSuppression(previouslySuppressed);
            RESTORING.remove();
        }
    }

    /**
     * Keeps the baseline in step with item lifecycle events. Ahead of matrix-auth's creator
     * listeners, so a property that listener adds is compared against the creation state.
     */
    @Extension(optional = true, ordinal = 1000)
    @Restricted(NoExternalUse.class)
    public static class Baseline extends ItemListener {

        /**
         * Fills the baseline once all items are loaded (startup and reload). This runs as SYSTEM
         * during startup, so {@code getAllItems} sees every item; on a reload the requesting
         * administrator sees every item as well.
         */
        @Override
        public void onLoaded() {
            synchronized (LOCK) {
                BASELINE.clear();
                for (AbstractItem item : Jenkins.get().getAllItems(AbstractItem.class)) {
                    if (item instanceof Job || item instanceof AbstractFolder) {
                        BASELINE.put(item.getFullName(), propertyXml(item));
                    }
                }
            }
        }

        @Override
        public void onCreated(Item item) {
            if (item instanceof Job || item instanceof AbstractFolder) {
                synchronized (LOCK) {
                    BASELINE.putIfAbsent(item.getFullName(), propertyXml((AbstractItem) item));
                }
            }
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            synchronized (LOCK) {
                for (String name : BASELINE.keySet().toArray(new String[0])) {
                    if (name.equals(oldFullName) || name.startsWith(oldFullName + "/")) {
                        String value = BASELINE.remove(name);
                        if (value != null) {
                            BASELINE.put(newFullName + name.substring(oldFullName.length()), value);
                        }
                    }
                }
            }
        }

        @Override
        public void onDeleted(Item item) {
            String fullName = item.getFullName();
            synchronized (LOCK) {
                BASELINE.keySet().removeIf(name -> name.equals(fullName) || name.startsWith(fullName + "/"));
            }
        }
    }
}
