package io.jenkins.plugins.batchcontrol.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.listener.ItemChangeListener;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.policy.ActivationService;
import io.jenkins.plugins.batchcontrol.model.CreateNamePattern;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
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
import org.springframework.security.access.AccessDeniedException;
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
        String destName = destination.getFullName();
        // D-59b (role-strategy#751): a move creates the name in the destination, so the installed
        // naming strategy must accept it there, as it would for a creation. Asked as the mover
        // (this runs in the mover's own context), which is what role-strategy's strategy evaluates.
        String namingRefusal = namingRefusal(destName, item.getName());
        if (delete && create && namingRefusal == null) {
            return null;
        }
        String user = a.getName();
        Grant restricting = create ? null : restrictingGrant(user, destName, item.getName());
        List<String> missing = new ArrayList<>();
        if (!delete) {
            missing.add("Item/Delete on '" + item.getFullName() + "'");
        }
        if (!create && restricting == null) {
            missing.add("Item/Create on " + describe(destName));
        }
        String reason = missing.isEmpty() ? "" : "missing " + String.join(" and ", missing);
        if (namingRefusal != null) {
            reason = (reason.isEmpty() ? "" : reason + "; ") + "the project naming strategy does not allow the name '"
                    + item.getName() + "' in " + describe(destName) + ": " + namingRefusal;
        }
        if (restricting != null) {
            reason = (reason.isEmpty() ? "" : reason + "; ")
                    + "the name '" + item.getName() + "' is outside the name restriction '"
                    + restricting.getCreateNamePattern() + "' of grant " + restricting.getId();
        }
        // #84 (e2e-08 UX-4): the record names every active window on either side, so its grant
        // column does not read "no grant" while a window existed.
        Grant deleteWindow = GrantService.get().findActiveDeleteGrant(user, item);
        Grant createWindow = destName.isEmpty() ? null
                : GrantService.get().findActiveCreateGrant(user, destName, item.getName());
        List<String> windows = new ArrayList<>();
        if (deleteWindow != null) {
            windows.add("Delete on '" + item.getFullName() + "' from grant " + deleteWindow.getId());
        }
        if (createWindow != null) {
            windows.add("Create in " + describe(destName) + " from grant " + createWindow.getId());
        }
        if (!windows.isEmpty()) {
            reason = reason + "; active permission windows: " + String.join(", ", windows);
        }
        Grant linked = restricting != null ? restricting : createWindow != null ? createWindow : deleteWindow;
        record(user, item, destName, reason, linked);
        // E2E-1 UX-2: a permission window has one scope, so a move across folders usually needs
        // two windows. The message names each missing part and what to request for it.
        String deletePart = "Delete on '" + item.getFullName() + "'";
        String createPart = "Create in " + describe(destName);
        List<String> lacking = new ArrayList<>();
        List<String> toRequest = new ArrayList<>();
        if (!delete) {
            lacking.add(deletePart);
            toRequest.add(deletePart);
        }
        if (!create && restricting == null) {
            lacking.add(createPart);
            toRequest.add(createPart);
        }
        if (restricting != null) {
            toRequest.add(createPart + " that allows the name '" + item.getName() + "'");
        }
        StringBuilder message = new StringBuilder()
                .append("Moving '").append(item.getFullName()).append("' to ").append(describe(destName))
                .append(" was refused; nothing was moved. While change control is on, a move needs ")
                .append(deletePart).append(" and ").append(createPart)
                .append(", each from your own permissions or an active permission window.");
        if (!lacking.isEmpty()) {
            message.append(" You lack ").append(String.join(" and ", lacking)).append('.');
        }
        if (restricting != null) {
            message.append(" Your permission window for ").append(describe(destName))
                    .append(" (grant ").append(restricting.getId()).append(") only allows ")
                    .append(CreateNamePattern.describe(restricting.getCreateNamePattern()))
                    .append(", which does not include '").append(item.getName()).append("'.");
        }
        if (namingRefusal != null) {
            message.append(" The project naming strategy does not allow the name '").append(item.getName())
                    .append("' in ").append(describe(destName)).append(": ").append(namingRefusal);
            if (!namingRefusal.endsWith(".")) {
                message.append('.');
            }
        }
        // Missing parts no permission window can supply. Suggesting a window for one of them would
        // lead to a request refused at submission (or to a window that still does not allow the
        // move), so the message says an administrator must make the move and the refusal page
        // offers no window at all.
        List<String> noWindow = new ArrayList<>();
        if (!delete && !GrantScope.deleteAppliesTo(item)) {
            // D-71: a window's Delete applies only to a job, never to a folder, multibranch project
            // or organization folder.
            noWindow.add("Delete on '" + item.getFullName() + "', because a window's Delete applies only to a job");
        }
        if (!create && restricting == null && !createWindowPossible(destination)) {
            // S-13: no window can name the Jenkins root; D-71: a window's Create applies only to a
            // regular folder.
            noWindow.add(destName.isEmpty()
                    ? "Create in the Jenkins root, because a window names one job or folder and the Jenkins root is neither"
                    : "Create in " + describe(destName) + ", because a window's Create applies only to a folder");
        }
        if (!noWindow.isEmpty()) {
            message.append(" No permission window confers ").append(String.join(", nor ", noWindow))
                    .append(", so an administrator must make this move.");
            return new MoveRefusal(message.toString(), item.getFullName(), destName, false, false);
        }
        if (toRequest.isEmpty()) {
            message.append(" Ask an administrator");
        } else if (toRequest.size() == 1) {
            message.append(" Request a permission window for ").append(toRequest.get(0));
        } else {
            message.append(" A permission window covers one job or folder, so request one window for ")
                    .append(toRequest.get(0)).append(" and another for ").append(toRequest.get(1));
        }
        message.append(toRequest.isEmpty() ? "." : ", or ask an administrator.");
        return new MoveRefusal(message.toString(), item.getFullName(), destName, !delete, !create);
    }

    /**
     * D-59a: whether a move by the current user, once completed, puts the moved jobs back to the
     * state of a new job: run control and change control on, and the user is neither SYSTEM nor
     * holds Overall/Administer.
     */
    static boolean moveStartsOver() {
        BatchControlGlobalConfiguration config = BatchControlGlobalConfiguration.get();
        if (!config.isChangeControlEnabled() || !config.isRunControlEnabled()) {
            return false;
        }
        return !ACL.SYSTEM2.equals(Jenkins.getAuthentication2()) && !Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }

    /**
     * D-59a (SPEC 6a): {@code moved}, just moved from {@code oldFullName} by {@code mover}, and
     * every job or computed folder inside it start over like newly created ones: no longer
     * activated, a {@code HELD} record naming the move, and for a job the D-34 lock (computed
     * children are exempt, D-32/D-46). Never throws: the move has already happened.
     */
    static void startOver(Item moved, String oldFullName, String mover) {
        String newFullName = moved.getFullName();
        List<Item> affected = new ArrayList<>();
        affected.add(moved);
        if (moved instanceof ItemGroup) {
            // ACL.SYSTEM2 (as the enumerating authentication only, no context switch): every job
            // inside the moved folder must start over, including those the mover cannot read, or
            // a hidden job would keep running unattended. The mover's move was already allowed by
            // MoveGuard#check (Delete on the folder, Create on the destination) before the move.
            for (Item descendant : Items.allItems2(ACL.SYSTEM2, (ItemGroup<?>) moved, Item.class)) {
                if (descendant != moved) {
                    affected.add(descendant);
                }
            }
        }
        for (Item item : affected) {
            String to = item.getFullName();
            String from = oldFullName + to.substring(newFullName.length());
            try {
                if (!ActivationService.get().holdAfterMove(item, from, mover)) {
                    continue;
                }
                if (item instanceof Job) {
                    ItemChangeListener.applyActivationLock((Job<?, ?>) item, "Moved job",
                            "moving the job by '" + mover + "' has taken it out of service");
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not put the moved job '" + to + "' on hold");
            }
        }
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

    /**
     * D-59b: the installed project naming strategy's refusal of {@code name} in the group
     * {@code parentFullName} ({@code ""} for the Jenkins root) for the current user, or
     * {@code null} when it accepts the name. The default strategy accepts every name. A strategy
     * that fails unexpectedly refuses (the move would otherwise bypass it).
     */
    @CheckForNull
    private static String namingRefusal(String parentFullName, String name) {
        try {
            Jenkins.get().getProjectNamingStrategy().checkName(parentFullName, name);
            return null;
        } catch (Failure f) {
            String msg = f.getMessage();
            return msg == null || msg.isBlank() ? "the name is not allowed" : msg;
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "The project naming strategy failed while checking a move", e);
            return "the naming strategy could not check the name";
        }
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
        Item group;
        try {
            group = jenkins.getItemByFullName(destination.substring(1)); // as the user: Read applies
        } catch (AccessDeniedException e) {
            // Discover without Read (security-33 S-33-05, #74): step aside exactly as for an
            // unknown destination, so the folders plugin answers and nothing is recorded; a 403
            // must not escape from inside a permission check.
            return null;
        }
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

    /**
     * Whether a permission window can confer Item/Create in {@code destination}: never in the
     * Jenkins root (no window names it, S-13), and elsewhere only in a group a window's CREATE
     * applies to (a regular folder, D-71).
     */
    private static boolean createWindowPossible(ItemGroup<?> destination) {
        return destination instanceof Item && !destination.getFullName().isEmpty()
                && GrantScope.createAppliesTo((Item) destination);
    }

    private static String describe(String groupFullName) {
        return groupFullName.isEmpty() ? "the Jenkins root" : "'" + groupFullName + "'";
    }

    private static void record(String user, Item item, String destName, String reason,
                               @CheckForNull Grant linked) {
        String target = item.getFullName();
        boolean written;
        try {
            written = BlockedAttemptAudit.get().record(ChangeType.GRANT_VIOLATION,
                    NewItemName.MOVE_OPERATION + " " + target + " " + destName, target, user,
                    "Refused to move '" + target + "' to " + describe(destName) + " for '" + user + "': " + reason,
                    linked == null ? null : linked.getId());
        } catch (RuntimeException e) {
            // The refusal stands whatever happens to the record.
            LOGGER.log(Level.WARNING, "Could not record the refused move of '" + target + "'", e);
            written = true; // no record: the refusal must at least reach the log
        }
        // security-33 S-33-04: INFO only when a new record was written; a coalesced repeat is FINE,
        // so repeated attempts cannot flood the controller log.
        LOGGER.log(written ? Level.INFO : Level.FINE, () -> "Refused to move '" + target + "' to "
                + describe(destName) + " for '" + user + "': " + reason);
    }
}
