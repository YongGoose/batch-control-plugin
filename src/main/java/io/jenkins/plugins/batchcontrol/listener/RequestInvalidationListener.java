package io.jenkins.plugins.batchcontrol.listener;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.queue.ApprovedRunAction;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-21: renaming or moving a job ends every PENDING/APPROVED request that targets its old
 * full name in status INVALIDATED (the approver reviewed a different identity than the one
 * that would run). #38: deleting a job, or a folder above it, does the same ({@link #onDeleted}). Runs regardless of the control switches — invalidation is a safety rule,
 * not a control feature.
 *
 * <p>{@code onLocationChanged} fires for both renames and moves (Jenkins core calls
 * {@code fireLocationChange} from {@code AbstractItem.renameTo} and from {@code Items.move}),
 * including recursively for the children of a moved folder.
 *
 * <p>A request whose file cannot be read or written when its job moves is invalidated later
 * ({@link RunRequestService#invalidateForJob}); the queued run of such a request on the moved job is
 * cancelled now all the same, so it never executes.
 *
 * <p>R2-04: the queue is read and the runs are cancelled as SYSTEM ({@link #cancelQueuedMarkers}),
 * so the queued run of an invalidated approval is dropped whoever renamed or moved the job, even a
 * user who cannot read it under its new name (core lists only the queue items the current user can
 * read). This covers renames, moves, and the children of a renamed or moved folder alike.
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
     * #38: deleting a job ends its PENDING/APPROVED run requests INVALIDATED, and deleting a folder (or
     * any other item group) does the same for the requests on the items below it, as SPEC 6a already
     * does for activation requests. Core cancels the deleted items' queued runs before it reports the
     * deletion; any run of an invalidated request still queued is cancelled here too.
     */
    @Override
    public void onDeleted(Item item) {
        if (!(item instanceof Job) && !(item instanceof ItemGroup)) {
            return;
        }
        List<String> invalidated;
        try {
            invalidated = RunRequestService.get().invalidateForDeletedItem(item.getFullName(), item instanceof ItemGroup);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not invalidate the run requests of the deleted item '"
                    + item.getFullName() + "'; their approval is refused while the job does not exist");
            return;
        }
        if (!invalidated.isEmpty()) {
            cancelQueuedMarkers(invalidated, null);
        }
    }

    /**
     * Best effort: an invalidated approval must never execute, so drop its queued item too. With
     * {@code moved} given, the queued runs of that item whose request missed its invalidation
     * ({@link RunRequestService#hasMissedInvalidation}) are dropped as well.
     *
     * <p>ACL.SYSTEM2 switch, for reading the queue and cancelling the matching items only (R2-04):
     * {@code Queue.getItems()} lists only the items the current user can read, and the user whose
     * rename or move invalidated a request may not be able to read the job under its new name (or
     * at all), so its approved run would stay queued and later run. The permission checks are
     * complete when this runs: core fires {@code onLocationChanged} only after it has checked the
     * user's permission to rename or move the item (and Batch Control's own move and change-control
     * checks ran before that), and {@code onDeleted} only after it has checked Item/Delete (#38); the
     * periodic work calls this as SYSTEM already. Nothing read here
     * is shown to the user: the only effect is that the runs of the given (already ended) requests
     * are cancelled.
     */
    public static void cancelQueuedMarkers(Collection<String> requestIds, @CheckForNull Item moved) {
        RunRequestService service = RunRequestService.get();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        Queue queue = jenkins.getQueue();
        // ACL.SYSTEM2 switch: the permission checks are complete (see javadoc).
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
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
}
