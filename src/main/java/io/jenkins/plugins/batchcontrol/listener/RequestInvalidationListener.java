package io.jenkins.plugins.batchcontrol.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import java.util.List;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-21: renaming or moving a job ends every PENDING/APPROVED request that targets its old
 * full name in status INVALIDATED (the approver reviewed a different identity than the one
 * that would run). Runs regardless of the control switches — invalidation is a safety rule,
 * not a control feature.
 *
 * <p>{@code onLocationChanged} fires for both renames and moves (Jenkins core calls
 * {@code fireLocationChange} from {@code AbstractItem.renameTo} and from {@code Items.move}),
 * including recursively for the children of a moved folder.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RequestInvalidationListener extends ItemListener {

    private static final Logger LOGGER =
            Logger.getLogger(RequestInvalidationListener.class.getName());

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        List<String> invalidated = RunRequestService.get().invalidateForJob(oldFullName,
                "Target job renamed or moved: '" + oldFullName + "' -> '" + newFullName + "'");
        if (invalidated.isEmpty()) {
            return;
        }
        cancelQueuedMarkers(invalidated);
    }

    /** Best effort: an invalidated approval must never execute, so drop its queued item too. */
    private static void cancelQueuedMarkers(List<String> requestIds) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Queue queue = jenkins.getQueue();
        for (Queue.Item queued : queue.getItems()) {
            ApprovedRunAction marker = queued.getAction(ApprovedRunAction.class);
            if (marker != null && requestIds.contains(marker.getRequestId())) {
                boolean cancelled = queue.cancel(queued);
                LOGGER.info(() -> "Cancelled queued item of invalidated request "
                        + marker.getRequestId() + ": " + cancelled);
            }
        }
    }
}
