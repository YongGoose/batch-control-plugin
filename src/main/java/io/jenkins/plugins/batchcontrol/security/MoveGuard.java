package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.store.BlockedAttemptAudit;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.DirectlyModifiableTopLevelItemGroup;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.springframework.security.core.Authentication;

/**
 * D-59 (SPEC item 8): while change control is on, moving an item is a change that change control
 * governs. A move by a user without Overall/Administer is allowed only if the user holds
 * Item/Delete on the item and Item/Create on the destination, each native or from an active grant
 * (the "delete here, create there" reading core applies to renames). A CREATE grant's name
 * restriction (D-40) is matched against the moved item's name ({@link NewItemName} reads it from
 * the {@code move/move} request). A refused move changes nothing, is answered with
 * {@link MoveRefusal} and recorded once as {@code GRANT_VIOLATION} naming the user and the item.
 *
 * <p>The decision is taken at two points, both before anything moves:
 * <ul>
 *   <li>{@link ChangeControlledRelocationHandler}, first in the folders plugin's handler chain: the
 *       last step before {@code Items.move}, whatever the installed authorization strategy;</li>
 *   <li>the grant layer's Item/Move check on the item ({@link GrantAwareACL}), which the folders
 *       plugin makes first in {@code move/move}: there the refusal is explained even when the
 *       plugin would otherwise drop the request silently because the destination is not offered
 *       (for example a CREATE grant whose name restriction does not admit the moved item).</li>
 * </ul>
 *
 * <p>Every check here runs as the current user; nothing switches to {@code ACL.SYSTEM2}. With
 * change control off this class decides nothing.
 */
@Restricted(NoExternalUse.class)
final class MoveGuard {

    private static final Logger LOGGER = Logger.getLogger(MoveGuard.class.getName());

    private MoveGuard() {
    }

    /**
     * Whether {@code permission} is the folders plugin's Item/Move ({@code RelocationAction.RELOCATE},
     * not API: the class is restricted to that plugin). Cheap: no lookup.
     */
    static boolean isMovePermission(Permission permission) {
        return permission.group == Item.PERMISSIONS && "Move".equals(permission.name);
    }

    /**
     * The refusal of moving {@code item} into {@code destination} by the current user, recorded,
     * or {@code null} when the move is allowed (or change control is off).
     */
    @CheckForNull
    static MoveRefusal check(Item item, ItemGroup<?> destination) {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return null;
        }
        Authentication a = Jenkins.getAuthentication2();
        if (ACL.SYSTEM2.equals(a) || Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return null;
        }
        boolean delete = item.hasPermission(Item.DELETE);
        boolean create = destination instanceof AccessControlled
                && ((AccessControlled) destination).hasPermission(Item.CREATE);
        if (delete && create) {
            return null;
        }
        String user = a.getName();
        String destName = destination.getFullName();
        Grant restricting = create ? null : restrictingGrant(user, destName, item.getName());
        List<String> missing = new ArrayList<>();
        if (!delete) {
            missing.add("Item/Delete on '" + item.getFullName() + "'");
        }
        if (!create && restricting == null) {
            missing.add("Item/Create on " + describe(destName));
        }
        String reason = missing.isEmpty() ? "" : "missing " + String.join(" and ", missing);
        if (restricting != null) {
            reason = (reason.isEmpty() ? "" : reason + "; ")
                    + "the name '" + item.getName() + "' is outside the name restriction '"
                    + restricting.getCreateNamePattern() + "' of grant " + restricting.getId();
        }
        record(user, item, destName, reason, restricting);
        StringBuilder message = new StringBuilder()
                .append("Moving '").append(item.getFullName()).append("' to ").append(describe(destName))
                .append(" was refused, and nothing was moved. While change control is on, a move needs")
                .append(" Item/Delete on the item and Item/Create on the destination, from your own")
                .append(" permissions or an active permission window.");
        if (!missing.isEmpty()) {
            message.append(" You are missing ").append(String.join(" and ", missing)).append('.');
        }
        if (restricting != null) {
            message.append(" Your permission window for ").append(describe(destName))
                    .append(" (grant ").append(restricting.getId()).append(") only allows ")
                    .append(CreateNamePattern.describe(restricting.getCreateNamePattern()))
                    .append(", which does not include '").append(item.getName()).append("'.");
        }
        message.append(" Request a permission window that covers both, or ask an administrator.");
        return new MoveRefusal(message.toString());
    }

    /**
     * The check of the current request when it is a POST to {@code <item>/move/move} for the item
     * {@code itemFullName}: the refusal, or {@code null} when the request is not such a move, the
     * destination is not one the folders plugin could move to, or the move is allowed. Called by
     * the grant layer once the installed strategy has granted Item/Move on the item.
     */
    @CheckForNull
    static MoveRefusal checkCurrentRequest(String itemFullName) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        Item item = NewItemName.movedItem(req);
        if (req == null || item == null || !itemFullName.equals(item.getFullName())) {
            return null;
        }
        ItemGroup<?> destination = destination(req.getParameter("destination"));
        if (destination == null || destination == item.getParent() || !canHold(destination, item)) {
            // Left to the folders plugin, which refuses these without moving anything.
            return null;
        }
        return check(item, destination);
    }

    /** The group named by the {@code destination} parameter ({@code /} or {@code /a/b}), if visible. */
    @CheckForNull
    private static ItemGroup<?> destination(@CheckForNull String destination) {
        if (destination == null || !destination.startsWith("/")) {
            return null;
        }
        Jenkins jenkins = Jenkins.get();
        if (destination.equals("/")) {
            return jenkins;
        }
        Item group = jenkins.getItemByFullName(destination.substring(1)); // as the user: Read applies
        return group instanceof ItemGroup ? (ItemGroup<?>) group : null;
    }

    /**
     * Whether {@code destination} is a group the folders plugin's standard handler could move
     * {@code item} into: directly modifiable, not the item or one of its descendants, and without
     * another item of the same name.
     */
    private static boolean canHold(ItemGroup<?> destination, Item item) {
        if (!(destination instanceof DirectlyModifiableTopLevelItemGroup)) {
            return false;
        }
        for (ItemGroup<?> g = destination; g instanceof Item; g = ((Item) g).getParent()) {
            if (g == item) {
                return false;
            }
        }
        Item existing = destination.getItem(item.getName());
        return existing == null;
    }

    /**
     * The active CREATE grant of {@code user} on {@code groupFullName} whose name restriction alone
     * refuses {@code name}, or {@code null} when no such grant exists (no grant at all, or one
     * that admits the name).
     */
    @CheckForNull
    private static Grant restrictingGrant(String user, String groupFullName, String name) {
        if (groupFullName.isEmpty()) {
            return null; // no root-scope grant exists (S-13)
        }
        List<Grant> grants = GrantService.get().findActiveGrants(user, groupFullName, GrantAction.CREATE);
        Grant first = null;
        for (Grant grant : grants) {
            if (grant.getCreateNamePattern() == null || grant.allowsCreateName(name)) {
                return null;
            }
            if (first == null) {
                first = grant;
            }
        }
        return first;
    }

    private static String describe(String groupFullName) {
        return groupFullName.isEmpty() ? "the Jenkins root" : "'" + groupFullName + "'";
    }

    private static void record(String user, Item item, String destName, String reason,
                               @CheckForNull Grant restricting) {
        String target = item.getFullName();
        try {
            BlockedAttemptAudit.get().record(ChangeType.GRANT_VIOLATION,
                    NewItemName.MOVE_OPERATION + " " + target + " " + destName, target, user,
                    "Refused to move '" + target + "' to " + describe(destName) + " for '" + user + "': " + reason,
                    restricting == null ? null : restricting.getId());
        } catch (RuntimeException e) {
            // The refusal stands whatever happens to the record.
            LOGGER.log(Level.WARNING, "Could not record the refused move of '" + target + "'", e);
        }
        LOGGER.info(() -> "Refused to move '" + target + "' to " + describe(destName) + " for '" + user
                + "': " + reason);
    }
}
