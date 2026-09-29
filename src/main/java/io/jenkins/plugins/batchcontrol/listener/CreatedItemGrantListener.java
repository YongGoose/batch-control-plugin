package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.ChangeRecord;
import io.jenkins.plugins.batchcontrol.model.ChangeType;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantLayer;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.security.ItemIdentity;
import io.jenkins.plugins.batchcontrol.store.Store;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.springframework.security.core.Authentication;

/**
 * D-35c: records in the grant an item that its holder could only create because of the grant's
 * Create. While the grant is active the holder then also holds Item/Read and Item/Configure on
 * that item ({@code security.GrantAwareACL}), so matrix-auth's creator listener, which adds a
 * permanent Read/Configure entry for a creator lacking them, adds nothing. When the window ends,
 * those permissions end with it.
 *
 * <p>The ordinal puts this listener ahead of matrix-auth's creator listeners (default ordinal),
 * which run in the same {@code onCreated} event. A copy is covered too: core's default
 * {@code onCopied} calls {@code onCreated}. Renames, moves and deletes keep the record in step.
 *
 * <p>Only acts while change control is on and a Batch Control strategy is installed; otherwise no
 * grant confers anything and there is nothing to record. It needs neither matrix-auth nor
 * role-strategy.
 */
@Extension(ordinal = 1000)
@Restricted(NoExternalUse.class)
public class CreatedItemGrantListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(CreatedItemGrantListener.class.getName());

    @Override
    public void onCreated(Item item) {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()
                || !GrantLayer.isGrantLayered(Jenkins.get().getAuthorizationStrategy())) {
            return;
        }
        Authentication auth = Jenkins.getAuthentication2();
        if (ACL.SYSTEM2.equals(auth) || ACL.isAnonymous2(auth)) {
            return;
        }
        String user = auth.getName();
        String fullName = item.getFullName();
        if (GrantService.get().findActiveGrants(user, fullName, GrantAction.CREATE).isEmpty()) {
            return;
        }
        ItemGroup<? extends Item> parent = item.getParent();
        if (parent instanceof AccessControlled
                && GrantLayer.hasPermissionWithoutGrants((AccessControlled) parent, auth, Item.CREATE)) {
            // The holder could create here without the grant: matrix-auth's native behaviour
            // (a permanent creator entry) is not Batch Control's to change.
            return;
        }
        if (GrantService.get().findActiveCreateGrant(user, fullName, item.getName()) == null) {
            // D-40 defence in depth: the grant layer refuses a restricted Create before the item
            // exists (security.GrantAwareACL), so reaching this means the item came in through a
            // path that check did not see. It is not deleted here (that would take a SYSTEM
            // switch this plugin does not make outside its two documented places); it confers
            // nothing through the grant, and the record makes it visible to an administrator.
            LOGGER.severe(() -> "Item '" + fullName + "' was created by '" + user + "' although its name "
                    + "is outside the name restriction of every active Create grant");
            ChangeRecord record = ChangeRecord.create(ChangeType.GRANT_VIOLATION, fullName, user,
                    "The item was created under a Create grant whose name restriction does not allow the name '"
                            + item.getName() + "'; an administrator must check it.");
            Store.get().appendChangeRecord(record);
            return;
        }
        Grant grant = GrantService.get().recordCreatedItem(user, fullName,
                item instanceof AbstractItem ? ItemIdentity.of(((AbstractItem) item).getRootDir()) : null);
        if (grant != null) {
            LOGGER.fine(() -> "Item '" + fullName + "' created by '" + user + "' through grant "
                    + grant.getId());
        }
    }

    /**
     * S-09: after all items are loaded (startup, reload), drops created-item records of items that
     * no longer exist. Runs as SYSTEM at startup and as the reloading administrator otherwise, so
     * every item is visible without switching authentication.
     */
    @Override
    public void onLoaded() {
        if (BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            Jenkins jenkins = Jenkins.get();
            GrantService.get().pruneCreatedItems(name -> jenkins.getItemByFullName(name) != null);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        // With change control off every window was revoked (S-15), so no record can be active.
        if (BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            GrantService.get().relocateCreatedItem(oldFullName, newFullName);
        }
    }

    @Override
    public void onDeleted(Item item) {
        if (BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            GrantService.get().forgetCreatedItem(item.getFullName());
        }
    }
}
