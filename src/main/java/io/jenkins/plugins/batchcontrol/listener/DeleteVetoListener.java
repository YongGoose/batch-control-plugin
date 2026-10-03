package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.security.GrantService;
import io.jenkins.plugins.batchcontrol.ui.GrantRequiredFailure;
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
        if (GrantService.get().findActiveGrant(user, item.getFullName(), GrantAction.DELETE,
                item instanceof ItemGroup) != null) {
            return;
        }
        // e2e-03 DEF-26: the refusal says what is missing and links the Grants screen only for a
        // user who may open it (anyone else is told whom to ask). Still a Failure (HTTP 400).
        throw new GrantRequiredFailure(item, GrantAction.DELETE, "deleting");
    }
}
