package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-38b: where {@code BatchControl/Request} is checked for a job-specific use (viewing, cancelling
 * or re-designating a run or activation request, requesting an activation or hold): the request's
 * item, so Request assigned on that job or a folder above it counts; Jenkins when the item no
 * longer exists.
 */
@Restricted(NoExternalUse.class)
public final class RequestScope {

    private RequestScope() {
    }

    /**
     * The item named {@code itemFullName}, or Jenkins when it does not exist. The caller then
     * checks the permission on the result as itself.
     */
    public static AccessControlled of(@CheckForNull String itemFullName) {
        if (itemFullName != null && !itemFullName.isEmpty()) {
            Item item;
            // SYSTEM2 only to find out whether the item exists: a caller-scoped lookup returns
            // null for an existing item the caller cannot read, which would move the check to
            // Jenkins. No permission is decided here; the caller checks on the result as itself.
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                item = Jenkins.get().getItemByFullName(itemFullName);
            }
            if (item != null) {
                return item;
            }
        }
        return Jenkins.get();
    }
}
