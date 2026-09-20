package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Delete veto (SPEC item 8, ARCHITECTURE section 2): with change control on, deleting an item
 * requires an active DELETE grant — even when the underlying authorization strategy grants
 * Item/Delete directly. Administrators pass (admin bypass is out of scope, SPEC section 1);
 * with change control off there is no veto and Jenkins behaves as before.
 */
@Extension
@Restricted(NoExternalUse.class)
public class DeleteVetoListener extends ItemListener {

    @Override
    public void onCheckDelete(Item item) {
        if (!BatchControlGlobalConfiguration.get().isChangeControlEnabled()) {
            return;
        }
        // SYSTEM and administrators are never vetoed (SPEC section 1: admin bypass out of scope).
        if (Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return;
        }
        String user = Jenkins.getAuthentication2().getName();
        if (GrantService.get().findActiveGrant(user, item.getFullName(), GrantAction.DELETE) != null) {
            return;
        }
        throw new Failure("Change control: deleting '" + item.getFullName()
                + "' requires an approved batch-control DELETE grant. "
                + "Request one under Batch Control > Grants and try again once it is approved.");
    }
}
