package io.jenkins.plugins.batchcontrol.listener;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import java.util.Collection;
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
 *
 * <p>A request whose file cannot be read or written when its job moves is invalidated later
 * ({@link RunRequestService#invalidateForJob}); the queued run of such a request on the moved job is
 * cancelled now all the same, so it never executes.
 */
@Extension
@Restricted(NoExternalUse.class)
public class RequestInvalidationListener extends ItemListener {

    private static final Logger LOGGER =
            Logger.getLogger(RequestInvalidationListener.class.getName());

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        RunRequestService service = RunRequestService.get();
        List<String> invalidated = service.invalidateForJob(oldFullName,
                "Target job renamed or moved: '" + oldFullName + "' -> '" + newFullName + "'");
        if (invalidated.isEmpty() && !service.hasMissedInvalidations()) {
            return;
        }
        cancelQueuedMarkers(invalidated, item);
    }

    /**
     * Best effort: an invalidated approval must never execute, so drop its queued item too. With
     * {@code moved} given, the queued runs of that item whose request missed its invalidation
     * ({@link RunRequestService#hasMissedInvalidation}) are dropped as well.
     */
    public static void cancelQueuedMarkers(Collection<String> requestIds, @CheckForNull Item moved) {
        RunRequestService service = RunRequestService.get();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Queue queue = jenkins.getQueue();
        for (Queue.Item queued : queue.getItems()) {
            ApprovedRunAction marker = queued.getAction(ApprovedRunAction.class);
            if (marker == null) {
                continue;
            }
            String requestId = marker.getRequestId();
            boolean missed = moved != null && queued.task == moved && service.hasMissedInvalidation(requestId);
            if (requestIds.contains(requestId) || missed) {
                boolean cancelled = queue.cancel(queued);
                LOGGER.info(() -> "Cancelled queued item of invalidated request "
                        + requestId + ": " + cancelled);
            }
        }
    }
}
