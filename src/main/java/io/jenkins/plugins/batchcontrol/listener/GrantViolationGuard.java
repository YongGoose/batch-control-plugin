package io.jenkins.plugins.batchcontrol.listener;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.thoughtworks.xstream.io.xml.DomReader;
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
import io.jenkins.plugins.batchcontrol.store.Store;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
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
        // S-28-04: the item's own monitor, taken first, is the lock: a save already holds it (core
        // Job/AbstractFolder save() is synchronized), and apply() needs it, so the order is always
        // item monitor only and no two saves can wait on each other in opposite orders.
        synchronized (item) {
            String now = propertyXml(item);
            int count = propertyCount(item);
            String remembered = BASELINE.put(fullName, now);
            if (!guardApplies()) {
                return;
            }
            // S-05, D-35d (4): the baseline is the last recorded configuration snapshot (this guard
            // runs ahead of ConfigSnapshotListener, so the snapshot is still the previous save's).
            // The in-memory copy is only the fallback for an item that has no snapshot yet, and the
            // baseline of an item whose fail-closed change could not be saved (UnsavedItemWrites):
            // its snapshot was taken from the stale file, for example with the payload property a
            // failed removal left on disk, which must not be restored as "the previous property".
            String snapshot = UnsavedItemWrites.isPending(item) ? null : snapshotPropertyXml(item);
            String before = snapshot != null ? snapshot : remembered;
            Authentication auth = Jenkins.getAuthentication2();
            boolean person = !ACL.SYSTEM2.equals(auth) && !ACL.isAnonymous2(auth);
            boolean http = Stapler.getCurrentRequest2() != null;
            // D-58a (3): the only exception is an administrator's own save through an HTTP request
            // (native Overall/Administer, asked with every grant layer off).
            boolean adminHttp = person && http
                    && GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER);
            // Judged before this save updates the state, so a save cannot un-guard itself.
            boolean guarded = GrantService.get().isGuardedItem(fullName);
            if (before != null && (!before.equals(now) || count > 1)) {
                boolean handled = person && holdsActiveGrant(auth.getName())
                        && revertGrantOnlySave(item, fullName, before, now, auth);
                if (!handled && guarded && !adminHttp) {
                    revertWidening(item, fullName, before, now, count, auth);
                }
            }
            // D-58b (3): a save never counts as the review (GrantService#markReviewed does).
            updateChangedState(item, fullName, auth, person);
        }
    }

    /**
     * D-58a (1): a save by a user whose Item/Configure comes only from a grant puts the item into
     * the "changed under a grant" state; a save through the web by a native Item/Configure or
     * Overall/Administer holder is the review that takes it out. Judged on the saved state (after
     * any revert above). Never throws.
     */
    private static void updateChangedState(AbstractItem item, String fullName, Authentication auth, boolean person) {
        if (!person) {
            return;
        }
        try {
            boolean nativeConfigure = GrantLayer.hasPermissionWithoutGrants(item, auth, Item.CONFIGURE);
            if (!nativeConfigure && item.hasPermission2(auth, Item.CONFIGURE)) {
                Grant grant = namedGrant(auth.getName(), item);
                if (grant != null) {
                    GrantService.get().markChanged(grant.getId(), fullName);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not update the changed-under-grant state of '" + fullName + "'", e);
        }
    }

    /**
     * D-35b: a save by a user whose Item/Configure comes only from a grant loses what it added.
     *
     * @return {@code true} when the save was reverted or the attempt was recorded as failed;
     *         {@code false} when the change stands (the D-58a check still follows)
     */
    private static boolean revertGrantOnlySave(AbstractItem item, String fullName, String before, String now,
                                               Authentication auth) {
        String user = auth.getName();
        // D-35d (4): only what this save added is taken back. Entries missing from the saved
        // property are never re-added, so a stale baseline cannot bring an entry back.
        String reverted;
        try {
            reverted = withoutAdditions(item, before, now);
        } catch (RuntimeException e) {
            // S-06: never guess (restoring the baseline could bring removed entries back).
            LOGGER.log(Level.SEVERE, "Cannot compare the authorization property of '" + fullName
                    + "' with its baseline", e);
            Grant grant = namedGrant(user, item);
            appendViolation(fullName, user, grant,
                    "The authorization property was changed by a user holding " + grantText(grant)
                            + ", and comparing it with the previous property FAILED ("
                            + e.getClass().getSimpleName() + "), so an administrator must check the item.");
            return true;
        }
        if (reverted.equals(now)) {
            return false; // nothing was added: entries were only removed, or the save changed nothing else
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
                return false;
            }
        } catch (IOException | RuntimeException e) {
            // S-06: fail loudly. The change may still be in place, so it is recorded as a
            // violation whose restore failed.
            LOGGER.log(Level.SEVERE, "Could not revert the authorization property of '" + fullName
                    + "' after a change by '" + user + "'", e);
            Grant grant = namedGrant(user, item);
            appendViolation(fullName, user, grant,
                    "The authorization property was changed by a user whose Item/Configure may come only "
                            + "from " + grantText(grant) + "; removing the added entries FAILED ("
                            + e.getClass().getSimpleName() + "), so an administrator must check the item.");
            return true;
        }
        BASELINE.put(fullName, reverted);
        Grant grant = namedGrant(user, item);
        appendViolation(fullName, user, grant,
                "The authorization property was changed by a user whose Item/Configure comes only "
                        + "from " + grantText(grant) + "; the entries the change added were removed.");
        // D-48: the saving user is told as well, if the save came through an HTTP request.
        SelfGrantRevertFilter.flag(item);
        LOGGER.warning(() -> "Reverted additions to the authorization property of '" + fullName
                + "' by '" + user + "', whose Item/Configure comes only from " + grantText(grant));
        return true;
    }

    /**
     * D-58a (2): on a guarded item, whoever saved (a build of any identity, SYSTEM, a script, the
     * CLI, another user), every change that widens access relative to the baseline is taken back:
     * an added or widened entry for any sid, a widening inheritance change, removing the property,
     * or a second authorization property. Only the widening is undone; nothing the baseline lacked
     * is re-added. Exception-safe: a failure is logged and recorded, never thrown into the save.
     */
    private static boolean revertWidening(AbstractItem item, String fullName, String before, String now, int count,
                                          Authentication auth) {
        String saver = auth == null ? "unknown" : auth.getName();
        String grantId = GrantService.get().guardingGrantId(fullName);
        List<String> undone = new ArrayList<>();
        String narrowed;
        try {
            narrowed = narrowToBaseline(item, before, now, undone);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Cannot compare the authorization property of '" + fullName
                    + "' with its baseline", e);
            appendViolation(fullName, saver, grantId, "The authorization property of this guarded item was saved"
                    + " by '" + saver + "', and comparing it with the previous property FAILED ("
                    + e.getClass().getSimpleName() + "), so an administrator must check the item.");
            return true;
        }
        if (count > 1) {
            undone.add(0, count + " authorization properties");
        }
        if (undone.isEmpty()) {
            return false;
        }
        String what = describe(undone);
        try {
            apply(item, narrowed);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Could not undo the widening of '" + fullName + "': " + what, e);
            appendViolation(fullName, saver, grantId, "The authorization property of this guarded item was widened"
                    + " by '" + saver + "' (" + what + "); undoing it FAILED (" + e.getClass().getSimpleName()
                    + "), so an administrator must check the item.");
            return true;
        }
        BASELINE.put(fullName, narrowed);
        appendViolation(fullName, saver, grantId, "The authorization property of this item, which is guarded because"
                + " of a permission window, was widened by '" + saver + "' (" + what + "); the widening was undone.");
        SelfGrantRevertFilter.flag(item); // D-48 for a save through an HTTP request
        if (Stapler.getCurrentRequest2() == null && item instanceof Job) {
            BuildLogNotice.print((Job<?, ?>) item, "[Batch Control] This build widened the authorization of '"
                    + fullName + "' (" + what + "), which is guarded because of a permission window; the change was"
                    + " undone. Ask an administrator.");
        }
        LOGGER.warning(() -> "Undid the widening of the authorization property of '" + fullName + "' saved by '"
                + saver + "': " + what);
        return true;
    }

    /**
     * D-58a: {@code now} with every widening relative to {@code before} undone, each undone change
     * described in {@code undone}. Entries of {@code now} that {@code before} lacks are dropped (any
     * sid); an inheritance change is undone unless it only narrows (to not inheriting); a removed
     * property comes back as it was. Nothing else changes.
     */
    static String narrowToBaseline(AbstractItem item, String before, String now, List<String> undone) {
        Object nowProperty = parse(now);
        Object beforeProperty = parse(before);
        if (nowProperty == null) {
            if (beforeProperty == null) {
                return now;
            }
            undone.add("the authorization property was removed");
            return before;
        }
        Map<Permission, Set<PermissionEntry>> beforeEntries = beforeProperty == null
                ? Collections.emptyMap() : entries(beforeProperty);
        Map<Permission, Set<PermissionEntry>> kept = new HashMap<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<Permission, Set<PermissionEntry>> e : entries(nowProperty).entrySet()) {
            Set<PermissionEntry> previous = beforeEntries.getOrDefault(e.getKey(), Collections.emptySet());
            for (PermissionEntry entry : e.getValue()) {
                if (previous.contains(entry)) {
                    kept.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(entry);
                } else {
                    added.add(safe(entry.getType() + ":" + entry.getSid()) + " " + e.getKey().getId());
                }
            }
        }
        Collections.sort(added);
        undone.addAll(added);
        InheritanceStrategy beforeInheritance = beforeProperty == null
                ? new InheritParentStrategy() : inheritance(beforeProperty);
        InheritanceStrategy nowInheritance = inheritance(nowProperty);
        InheritanceStrategy inheritance = nowInheritance == null ? new InheritParentStrategy() : nowInheritance;
        if (beforeInheritance != null && nowInheritance != null
                && beforeInheritance.getClass() != nowInheritance.getClass()
                && !(nowInheritance instanceof NonInheritingStrategy)) {
            undone.add("inheritance changed to " + nowInheritance.getClass().getSimpleName());
            inheritance = beforeInheritance;
        }
        if (beforeProperty == null && kept.isEmpty() && inheritance instanceof InheritParentStrategy) {
            return "";
        }
        return Items.XSTREAM2.toXML(build(item, kept, inheritance));
    }

    /** A property of the item's kind with {@code entries} and {@code inheritance}. */
    private static Object build(AbstractItem item, Map<Permission, Set<PermissionEntry>> entries,
                                InheritanceStrategy inheritance) {
        if (item instanceof Job) {
            AuthorizationMatrixProperty job = new AuthorizationMatrixProperty(new HashMap<>(), inheritance);
            entries.forEach((permission, set) -> set.forEach(entry -> job.add(permission, entry)));
            return job;
        }
        com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty folder =
                new com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty(new HashMap<>());
        folder.setInheritanceStrategy(inheritance);
        entries.forEach((permission, set) -> set.forEach(entry -> folder.add(permission, entry)));
        return folder;
    }

    /**
     * S-27-11: text taken from a saved property, safe for the build log, the server log and a
     * record: control characters replaced, length capped.
     */
    static String safe(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 120));
        for (int i = 0; i < text.length() && out.length() < 120; i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return out.length() < text.length() ? out + "..." : out.toString();
    }

    /** The undone changes joined, at most 20 listed. */
    private static String describe(List<String> undone) {
        if (undone.size() <= 20) {
            return String.join(", ", undone);
        }
        return String.join(", ", undone.subList(0, 20)) + " and " + (undone.size() - 20) + " more";
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
            if (io.jenkins.plugins.batchcontrol.model.Approvers.sameUser(user, grant.getUser())) { // S-28-15
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
        Grant grant = GrantService.get().findConfigureGrant(user, item);
        return grant != null ? grant : GrantService.get().findActiveGrant(user, item, null);
    }

    private static String grantText(@CheckForNull Grant grant) {
        return grant == null ? "a grant" : "grant " + grant.getId();
    }

    private static void appendViolation(String fullName, String user, @CheckForNull Grant grant, String detail) {
        appendViolation(fullName, user, grant == null ? null : grant.getId(), detail);
    }

    private static void appendViolation(String fullName, String user, @CheckForNull String grantId, String detail) {
        try {
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user, detail);
            record.setGrantId(grantId);
            Store.get().appendChangeRecord(record);
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Could not record a GRANT_VIOLATION on '" + fullName + "': " + detail, e);
        }
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
            snapshot = Store.get().loadConfigSnapshot(item.getFullName());
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
            // S-27-02: every authorization property counts, not only the first.
            List<Object> found = new ArrayList<>();
            if (properties != null) {
                for (Node n = properties.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element && className.equals(((Element) n).getTagName())) {
                        Object property = Items.XSTREAM2.unmarshal(new DomReader((Element) n));
                        if (property != null) {
                            found.add(property);
                        }
                    }
                }
            }
            return xmlOf(item, found);
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

    /**
     * The XML form of the item's matrix-auth property, or {@code ""} when it has none. S-27-02:
     * with more than one authorization property on the item, the union of their entries (with the
     * first one's inheritance), so what any of them grants is compared.
     */
    static String propertyXml(AbstractItem item) {
        return xmlOf(item, properties(item));
    }

    /** S-27-02: how many authorization properties the item carries. */
    static int propertyCount(AbstractItem item) {
        return properties(item).size();
    }

    private static String xmlOf(AbstractItem item, List<Object> properties) {
        if (properties.isEmpty()) {
            return "";
        }
        if (properties.size() == 1) {
            return Items.XSTREAM2.toXML(properties.get(0));
        }
        Map<Permission, Set<PermissionEntry>> union = new HashMap<>();
        for (Object property : properties) {
            entries(property).forEach((permission, set) ->
                    union.computeIfAbsent(permission, k -> new HashSet<>()).addAll(set));
        }
        InheritanceStrategy inheritance = inheritance(properties.get(0));
        return Items.XSTREAM2.toXML(build(item, union, inheritance == null ? new InheritParentStrategy() : inheritance));
    }

    /** Every matrix-auth authorization property of the item, in order (S-27-02). */
    private static List<Object> properties(AbstractItem item) {
        List<Object> found = new ArrayList<>();
        if (item instanceof Job) {
            for (Object property : ((Job<?, ?>) item).getAllProperties()) {
                if (property instanceof AuthorizationMatrixProperty) {
                    found.add(property);
                }
            }
        } else if (item instanceof AbstractFolder) {
            for (Object property : ((AbstractFolder<?>) item).getProperties()) {
                if (property instanceof com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty) {
                    found.add(property);
                }
            }
        }
        return found;
    }

    /** Replaces the item's property with the one {@code xml} describes ({@code ""}: none), one save. */
    private static void apply(AbstractItem item, String xml) throws IOException {
        Object property = xml.isEmpty() ? null : Items.XSTREAM2.fromXML(xml);
        RESTORING.set(Boolean.TRUE);
        boolean previouslySuppressed = ChangeRecording.beginSuppression();
        try (BulkChange bc = new BulkChange(item)) {
            if (item instanceof Job) {
                Job<?, ?> job = (Job<?, ?>) item;
                // S-27-02: every authorization property goes, not only the first.
                // S-28-01: every one of them, with no cap, before the single merged one is written.
                @SuppressWarnings({"rawtypes", "unchecked"})
                Job rawJob = job;
                for (Object existing : new ArrayList<>(job.getAllProperties())) {
                    if (existing instanceof AuthorizationMatrixProperty) {
                        rawJob.removeProperty((JobProperty) existing);
                    }
                }
                if (property != null) {
                    job.addProperty((JobProperty) property);
                }
            } else {
                AbstractFolder<?> folder = (AbstractFolder<?>) item;
                folder.getProperties()
                        .removeAll(com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty.class);
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
            // S-28-04: no global lock here; each item is changed under its own monitor only.
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
            guardCreationInGuardedFolder(created);
            BASELINE.putIfAbsent(created.getFullName(), propertyXml(created));
        }

        /**
         * D-58a (2), S-27-05: an item created inside a guarded folder (or under a guarded name) keeps
         * no authorization entries, unless an administrator created it through an HTTP request.
         * {@code createProjectFromXML} fires no save event, so this runs on creation (and on copy,
         * which core reports here). The new item and what it contains are stripped, and each is put
         * into the "changed under a grant" state of the guarding grant, so a later save of it by
         * the same script is guarded too. Never throws.
         */
        private static void guardCreationInGuardedFolder(AbstractItem created) {
            try {
                if (!guardApplies()) {
                    return;
                }
                Authentication auth = Jenkins.getAuthentication2();
                boolean person = !ACL.SYSTEM2.equals(auth) && !ACL.isAnonymous2(auth);
                if (person && Stapler.getCurrentRequest2() != null
                        && GrantLayer.hasPermissionWithoutGrants(Jenkins.get(), auth, Jenkins.ADMINISTER)) {
                    return;
                }
                String grantId = guardingGrantOf(created);
                if (grantId == null) {
                    return;
                }
                List<AbstractItem> items = new ArrayList<>();
                items.add(created);
                if (created instanceof AbstractFolder) {
                    for (AbstractItem inner : ((AbstractFolder<?>) created).getAllItems(AbstractItem.class)) {
                        if (inner instanceof Job || inner instanceof AbstractFolder) {
                            items.add(inner);
                        }
                    }
                }
                String user = auth.getName();
                for (AbstractItem item : items) {
                    String fullName = item.getFullName();
                    synchronized (item) { // S-28-04
                    if (propertyCount(item) > 0) {
                        try {
                            apply(item, "");
                            BASELINE.put(fullName, "");
                            appendViolation(fullName, user, grantId, "The item was created by '" + user + "' inside"
                                    + " an item that is guarded because of a permission window, and carried"
                                    + " authorization entries; they were removed.");
                            SelfGrantRevertFilter.flag(item); // D-48
                        } catch (IOException | RuntimeException e) {
                            LOGGER.log(Level.SEVERE, "Could not remove the authorization entries of '" + fullName
                                    + "', created inside a guarded item", e);
                            appendViolation(fullName, user, grantId, "The item was created by '" + user + "' inside"
                                    + " a guarded item with authorization entries; removing them FAILED ("
                                    + e.getClass().getSimpleName() + "), so an administrator must check the item."
                                    + failedRemovalOutcome(item));
                        }
                    }
                    }
                    // S-29-05: no redundant mark when a folder above is already marked (it covers this item).
                    Object parent = item.getParent();
                    if (!(parent instanceof Item && GrantService.get().isChangedUnderGrant((Item) parent))) {
                        GrantService.get().markChanged(grantId, fullName);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not check the creation of '" + created.getFullName() + "'", e);
            }
        }

        /** The grant that guards the item's own name or one of its ancestors, or {@code null}. */
        @CheckForNull
        private static String guardingGrantOf(AbstractItem item) {
            String own = GrantService.get().guardingGrantId(item.getFullName());
            if (own != null) {
                return own;
            }
            Object parent = item.getParent();
            while (parent instanceof Item) {
                String id = GrantService.get().guardingGrantId(((Item) parent).getFullName());
                if (id != null) {
                    return id;
                }
                parent = ((Item) parent).getParent();
            }
            return null;
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
            return GrantService.get().findCreatingGrant(auth.getName(), item);
        }

        private static void stripPayload(AbstractItem item, Grant grant) {
            synchronized (item) {
                stripPayloadLocked(item, grant);
            }
        }

        private static void stripPayloadLocked(AbstractItem item, Grant grant) {
            String fullName = item.getFullName();
            if (propertyCount(item) == 0) {
                BASELINE.put(fullName, "");
                return;
            }
            String user = Jenkins.getAuthentication2().getName();
            try {
                apply(item, "");
            } catch (IOException | RuntimeException e) {
                // S-06, SPEC item 2: recorded as GRANT_VIOLATION like every other failed restore here.
                LOGGER.log(Level.SEVERE, "Could not remove the authorization property of '" + fullName
                        + "' created by '" + user + "' under grant " + grant.getId(), e);
                appendViolation(fullName, user, grant, "The item was created by a user whose Item/Create comes only"
                        + " from grant " + grant.getId() + " and carried an authorization property; removing it FAILED ("
                        + e.getClass().getSimpleName() + "), so an administrator must check the item."
                        + failedRemovalOutcome(item));
                return;
            }
            BASELINE.put(fullName, "");
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user,
                    "The item was created by a user whose Item/Create comes only from grant "
                            + grant.getId() + " and carried an authorization property; the property was removed.");
            record.setGrantId(grant.getId());
            Store.get().appendChangeRecord(record);
            SelfGrantRevertFilter.flag(item); // D-48
            LOGGER.warning(() -> "Removed the authorization property of '" + fullName + "', created by '"
                    + user + "' through grant " + grant.getId());
        }

        /**
         * S-06, fail closed, after removing a created item's authorization property failed: the
         * removal is in memory (the property no longer applies), but the item's file and the
         * configuration snapshot taken from it still carry the property. The in-memory state becomes
         * the guard's baseline, so a later save cannot restore the property from the snapshot as
         * "the previous property", and the save is retried by the periodic work
         * ({@link UnsavedItemWrites}). Called under the item's monitor.
         *
         * @return the end of the GRANT_VIOLATION record's detail, saying which of the two happened
         */
        private static String failedRemovalOutcome(AbstractItem item) {
            if (propertyCount(item) > 0) {
                return " The property is still in effect.";
            }
            BASELINE.put(item.getFullName(), "");
            UnsavedItemWrites.add(item, "the removal of the authorization property");
            return " The property no longer applies, and its removal is saved again every minute; until that"
                    + " succeeds, a restart would load the item with the property.";
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
