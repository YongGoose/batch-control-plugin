package io.jenkins.plugins.batchcontrol.poc.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.Permission;
import io.jenkins.plugins.batchcontrol.poc.PocGrantStore;
import org.springframework.security.core.Authentication;

/** PoC-5: answers an active grant first, then the native ACL. Shared by the wrapper and option A. */
final class GrantLayerACL extends ACL {
    private final ACL nativeAcl;
    private final String itemFullName;

    GrantLayerACL(ACL nativeAcl, String itemFullName) {
        this.nativeAcl = nativeAcl;
        this.itemFullName = itemFullName;
    }

    @Override
    public boolean hasPermission2(@NonNull Authentication a, @NonNull Permission p) {
        if ((p == Item.CREATE || p == Item.CONFIGURE || p == Item.DELETE)
                && PocGrantStore.hasActiveGrant(a.getName(), itemFullName, p)) {
            return true;
        }
        return nativeAcl.hasPermission2(a, p);
    }
}
