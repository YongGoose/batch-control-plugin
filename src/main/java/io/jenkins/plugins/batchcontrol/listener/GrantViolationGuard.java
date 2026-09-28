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
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.store.FileStore;
import com.thoughtworks.xstream.io.xml.DomReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.util.xml.XMLUtils;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritParentStrategy;
import org.jenkinsci.plugins.matrixauth.inheritance.InheritanceStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * D-35b: a user whose Item/Configure comes only from a grant cannot turn it into a permanent
 * authorization entry. matrix-auth lets anyone with Item/Configure edit the item's
 * {@code AuthorizationMatrixProperty} (job) or the folder's; when a save by such a user changed
 * that property, the previous property is put back and a {@code GRANT_VIOLATION} change record
 * names the user, the item and the grant.
 *
 * <p>"Only from a grant" is decided by permission after the previous property is back in place
 * (D-35d (1)): the user holds Item/Configure on the item through the installed strategy with the
 * grant layer, but not without it (inherited ACLs included). Otherwise the change was not a
 * grant's doing and it is re-applied.
 *
 * <p>The previous state is the item's last recorded configuration snapshot (D-35d (4),
 * {@code snapshots/} of the store), so a reload from disk that fires no listener cannot leave a
 * stale in-memory copy behind. An in-memory baseline of every job's and folder's property (its
 * XML form, or empty for none) is only the fallback for an item without a snapshot. An item
 * known to neither is left alone, so an unknown state is never overwritten.
 * A restore that fails is recorded as a violation whose restore failed (S-06). The baseline is kept whatever the switches say, because the switch may be
 * turned on later; the guard itself acts only while change control is on and a Batch Control
 * matrix strategy is installed (grants confer nothing otherwise).
 *
 * <p>The ordinal puts the guard ahead of {@link ConfigSnapshotListener}, which then reads the
 * restored file, so the CONFIGURE record shows what actually stays. The restoring save itself is
 * suppressed from recording.
 *
 * <p>matrix-auth is optional. This class does not link without it, so its optional extension is
 * skipped. The nested {@link Baseline} listener does load (its own code uses core types only), so
 * its callbacks that reach this class first check {@link Baseline#matrixAuthActive()} (S-03).
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
            String remembered = BASELINE.put(fullName, now);
            if (!guardApplies()) {
                return;
            }
            // S-05, D-35d (4): the baseline is the last recorded configuration snapshot (this guard
            // runs ahead of ConfigSnapshotListener, so the snapshot is still the previous save's).
            // The in-memory copy is only the fallback for an item that has no snapshot yet.
            String snapshot = snapshotPropertyXml(item);
            String before = snapshot != null ? snapshot : remembered;
            if (before == null || before.equals(now)) {
                return;
            }
            Authentication auth = Jenkins.getAuthentication2();
            if (ACL.SYSTEM2.equals(auth) || ACL.isAnonymous2(auth)) {
                // D-35d (2): SYSTEM saves (builds without a build authenticator, Job DSL, JCasC)
                // are not blocked; the batch-control-strategy monitor warns about that setup.
                return;
            }
            String user = auth.getName();
            if (!holdsActiveGrant(user)) {
                return; // without any active grant the save cannot have been made possible by one
            }
            // D-35d (4): only what this save added is taken back. Entries missing from the saved
            // property are never re-added, so a stale baseline cannot bring an entry back.
            String reverted;
            try {
                reverted = withoutAdditions(item, before, now);
            } catch (RuntimeException e) {
                // S-06: never guess (restoring the baseline could bring removed entries back).
                LOGGER.log(Level.SEVERE, "Cannot compare the authorization property of '" + fullName
                        + "' with its baseline (D-35b)", e);
                Grant grant = namedGrant(user, item);
                appendViolation(fullName, user, grant,
                        "The authorization property was changed by a user holding " + grantText(grant)
                                + ", and comparing it with the previous property FAILED ("
                                + e.getClass().getSimpleName() + "), so an administrator must check the item.");
                return;
            }
            if (reverted.equals(now)) {
                return; // nothing was added: entries were only removed, or the save changed nothing else
            }
            try {
                // S-01, D-35d (1): "Configure only from a grant" is decided by permission, not by a
                // grant lookup: with the additions taken back, the strategy's ACL without the
                // grant layer (inherited ACLs included) denies Configure, and with it allows
                // Configure. Any other outcome means the change was not a grant's doing.
                apply(item, reverted);
                boolean onlyFromGrant = !GrantLayer.hasPermissionWithoutGrants(item, auth, Item.CONFIGURE)
                        && item.hasPermission2(auth, Item.CONFIGURE);
                if (!onlyFromGrant) {
                    apply(item, now); // the change stands
                    return;
                }
            } catch (IOException | RuntimeException e) {
                // S-06: fail loudly. The change may still be in place, so it is recorded as a
                // violation whose restore failed.
                LOGGER.log(Level.SEVERE, "Could not revert the authorization property of '" + fullName
                        + "' after a change by '" + user + "' (D-35b)", e);
                Grant grant = namedGrant(user, item);
                appendViolation(fullName, user, grant,
                        "The authorization property was changed by a user whose Item/Configure may come only "
                                + "from " + grantText(grant) + "; removing the added entries FAILED ("
                                + e.getClass().getSimpleName() + "), so an administrator must check the item.");
                return;
            }
            BASELINE.put(fullName, reverted);
            Grant grant = namedGrant(user, item);
            appendViolation(fullName, user, grant,
                    "The authorization property was changed by a user whose Item/Configure comes only "
                            + "from " + grantText(grant) + "; the entries the change added were removed.");
            LOGGER.warning(() -> "Reverted additions to the authorization property of '" + fullName
                    + "' by '" + user + "', whose Item/Configure comes only from " + grantText(grant)
                    + " (D-35b)");
        }
    }

    /**
     * The property {@code now} without what it added relative to {@code before} (both in the form
     * {@link #propertyXml} returns): every entry of {@code now} that {@code before} lacks is dropped,
     * and the inheritance strategy is the baseline's (inheriting from the parent when the baseline
     * had no property, which is how an item without a property behaves). Entries of {@code before}
     * missing from {@code now} stay missing. {@code ""} when nothing is left and the baseline had no
     * property.
     */
    static String withoutAdditions(AbstractItem item, String before, String now) {
        Object nowProperty = parse(now);
        if (nowProperty == null) {
            return now; // the property was removed: nothing was added
        }
        Object beforeProperty = parse(before);
        Map<Permission, Set<PermissionEntry>> beforeEntries = beforeProperty == null
                ? Collections.emptyMap() : entries(beforeProperty);
        Map<Permission, Set<PermissionEntry>> kept = new HashMap<>();
        boolean added = false;
        for (Map.Entry<Permission, Set<PermissionEntry>> e : entries(nowProperty).entrySet()) {
            Set<PermissionEntry> previous = beforeEntries.getOrDefault(e.getKey(), Collections.emptySet());
            for (PermissionEntry entry : e.getValue()) {
                if (previous.contains(entry)) {
                    kept.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(entry);
                } else {
                    added = true;
                }
            }
        }
        InheritanceStrategy inheritance = beforeProperty == null
                ? new InheritParentStrategy() : inheritance(beforeProperty);
        InheritanceStrategy nowInheritance = inheritance(nowProperty);
        boolean sameInheritance = inheritance != null && nowInheritance != null
                && inheritance.getClass() == nowInheritance.getClass();
        if (!added && sameInheritance) {
            return now;
        }
        if (beforeProperty == null && kept.isEmpty()) {
            return "";
        }
        Object result;
        if (item instanceof Job) {
            AuthorizationMatrixProperty job = new AuthorizationMatrixProperty(new HashMap<>(), inheritance);
            kept.forEach((permission, set) -> set.forEach(entry -> job.add(permission, entry)));
            result = job;
        } else {
            com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty folder =
                    new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<>());
            folder.setInheritanceStrategy(inheritance);
            kept.forEach((permission, set) -> set.forEach(entry -> folder.add(permission, entry)));
            result = folder;
        }
        return Items.XSTREAM2.toXML(result);
    }

    @CheckForNull
    private static Object parse(String xml) {
        if (xml.isEmpty()) {
            return null;
        }
        Object property = Items.XSTREAM2.fromXML(xml);
        return property instanceof AuthorizationMatrixProperty
                || property instanceof com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty
                ? property : null;
    }

    private static Map<Permission, Set<PermissionEntry>> entries(Object property) {
        return property instanceof AuthorizationMatrixProperty
                ? ((AuthorizationMatrixProperty) property).getGrantedPermissionEntries()
                : ((com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty) property)
                        .getGrantedPermissionEntries();
    }

    @CheckForNull
    private static InheritanceStrategy inheritance(Object property) {
        return property instanceof AuthorizationMatrixProperty
                ? ((AuthorizationMatrixProperty) property).getInheritanceStrategy()
                : ((com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty) property)
                        .getInheritanceStrategy();
    }

    private static boolean holdsActiveGrant(String user) {
        for (Grant grant : GrantService.get().listActive()) {
            if (user.equals(grant.getUser())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The grant to name in the record: the one conferring Configure on the item (a CONFIGURE
     * grant covering it, or the Create grant it was created through), else any active grant of
     * the user covering the item.
     */
    @CheckForNull
    private static Grant namedGrant(String user, AbstractItem item) {
        String fullName = item.getFullName();
        Grant grant = GrantService.get().findConfigureGrant(user, fullName, item.getRootDir());
        return grant != null ? grant : GrantService.get().findActiveGrant(user, fullName, null);
    }

    private static String grantText(@CheckForNull Grant grant) {
        return grant == null ? "a grant" : "grant " + grant.getId();
    }

    private static void appendViolation(String fullName, String user, @CheckForNull Grant grant, String detail) {
        ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user, detail);
        record.setGrantId(grant == null ? null : grant.getId());
        FileStore.get().appendChangeRecord(record);
    }

    /**
     * The authorization property of the item's last recorded configuration snapshot, in the form
     * {@link #propertyXml} returns ({@code ""} for none), or {@code null} when there is no snapshot
     * or it cannot be read.
     */
    @CheckForNull
    static String snapshotPropertyXml(AbstractItem item) {
        String className = propertyClassName(item);
        if (className == null) {
            return null;
        }
        String snapshot;
        try {
            snapshot = FileStore.get().loadConfigSnapshot(item.getFullName());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot read the configuration snapshot of '" + item.getFullName() + "'", e);
            return null;
        }
        if (snapshot == null) {
            return null;
        }
        try {
            Document doc = XMLUtils.parse(new StringReader(snapshot));
            Element properties = child(doc.getDocumentElement(), "properties");
            Element element = properties == null ? null : child(properties, className);
            if (element == null) {
                return "";
            }
            Object property = Items.XSTREAM2.unmarshal(new DomReader(element));
            return property == null ? "" : Items.XSTREAM2.toXML(property);
        } catch (SAXException | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot read the authorization property from the configuration snapshot of '"
                    + item.getFullName() + "'", e);
            return null;
        }
    }

    @CheckForNull
    private static Element child(@CheckForNull Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(((Element) n).getTagName())) {
                return (Element) n;
            }
        }
        return null;
    }

    @CheckForNull
    private static String propertyClassName(AbstractItem item) {
        if (item instanceof Job) {
            return AuthorizationMatrixProperty.class.getName();
        }
        if (item instanceof AbstractFolder) {
            return com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class.getName();
        }
        return null;
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
     * Keeps the baseline in step with item lifecycle events, and applies the payload half of
     * D-35c. The ordinal puts it after {@link CreatedItemGrantListener} (which records a
     * grant-only creation) and ahead of matrix-auth's creator listeners, so a property that
     * listener adds is compared against the creation state.
     */
    @Extension(optional = true, ordinal = 999)
    @Restricted(NoExternalUse.class)
    public static class Baseline extends ItemListener {

        /**
         * S-03: whether matrix-auth is installed. The outer guard does not even link without it
         * (its optional extension is skipped), but this listener's own code refers to core types
         * only, so it loads; every callback that reaches the outer class, which refers to
         * matrix-auth types, checks this first. Kept in this class so the check itself does not
         * link the outer class.
         */
        static boolean matrixAuthActive() {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            return jenkins != null && jenkins.getPlugin("matrix-auth") != null;
        }

        /**
         * Fills the baseline once all items are loaded (startup and reload). This runs as SYSTEM
         * during startup, so {@code getAllItems} sees every item; on a reload the requesting
         * administrator sees every item as well.
         */
        @Override
        public void onLoaded() {
            if (!matrixAuthActive()) {
                return;
            }
            synchronized (LOCK) {
                BASELINE.clear();
                for (AbstractItem item : Jenkins.get().getAllItems(AbstractItem.class)) {
                    if (item instanceof Job || item instanceof AbstractFolder) {
                        BASELINE.put(item.getFullName(), propertyXml(item));
                    }
                }
            }
        }

        /**
         * D-35c, creation payload: an item created by a user whose Create comes only from a grant
         * (a {@code createItem} with a config.xml payload, or a copy) keeps no authorization
         * property it arrived with. The property is removed from the item and, for a folder, from
         * every item inside it, each with a {@code GRANT_VIOLATION} record. Core's default
         * {@code onCopied} calls this method, so copies are covered.
         */
        @Override
        public void onCreated(Item item) {
            if (!(item instanceof Job || item instanceof AbstractFolder) || !matrixAuthActive()) {
                return;
            }
            AbstractItem created = (AbstractItem) item;
            synchronized (LOCK) {
                Grant grant = creationGrant(created);
                if (grant != null) {
                    stripPayload(created, grant);
                    if (created instanceof AbstractFolder) {
                        for (AbstractItem inner : ((AbstractFolder<?>) created).getAllItems(AbstractItem.class)) {
                            if (inner instanceof Job || inner instanceof AbstractFolder) {
                                stripPayload(inner, grant);
                            }
                        }
                    }
                }
                BASELINE.putIfAbsent(created.getFullName(), propertyXml(created));
            }
        }

        /** The grant through whose Create alone the current user created {@code item}, or {@code null}. */
        @CheckForNull
        private static Grant creationGrant(AbstractItem item) {
            if (!guardApplies()) {
                return null;
            }
            Authentication auth = Jenkins.getAuthentication2();
            if (ACL.SYSTEM2.equals(auth) || ACL.isAnonymous2(auth)) {
                return null;
            }
            return GrantService.get().findCreatingGrant(auth.getName(), item.getFullName(), item.getRootDir());
        }

        private static void stripPayload(AbstractItem item, Grant grant) {
            String fullName = item.getFullName();
            if (property(item) == null) {
                BASELINE.put(fullName, "");
                return;
            }
            String user = Jenkins.getAuthentication2().getName();
            try {
                apply(item, "");
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Could not remove the authorization property of '" + fullName
                        + "' created by '" + user + "' under grant " + grant.getId() + " (D-35c)", e);
                return;
            }
            BASELINE.put(fullName, "");
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user,
                    "The item was created by a user whose Item/Create comes only from grant "
                            + grant.getId() + " and carried an authorization property; the property was removed.");
            record.setGrantId(grant.getId());
            FileStore.get().appendChangeRecord(record);
            LOGGER.warning(() -> "Removed the authorization property of '" + fullName + "', created by '"
                    + user + "' through grant " + grant.getId() + " (D-35c)");
        }

        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            if (!matrixAuthActive()) {
                return;
            }
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
            if (!matrixAuthActive()) {
                return;
            }
            String fullName = item.getFullName();
            synchronized (LOCK) {
                BASELINE.keySet().removeIf(name -> name.equals(fullName) || name.startsWith(fullName + "/"));
            }
        }
    }
}
