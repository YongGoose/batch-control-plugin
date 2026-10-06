package io.jenkins.plugins.batchcontrol.queue;

import hudson.Extension;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72b (7), security-35 S-35-07: when the queue item of an approved run (it carries
 * {@link ApprovedRunAction}) is cancelled (by a user, by clearing the queue, or because its job
 * was deleted), the request records it, so startup recovery never submits that run again and the
 * request's files are disposed of when it ends. An item that left the queue for a build is not
 * cancelled and is ignored here; its run marks the request EXECUTED when it starts.
 *
 * <p>Records regardless of the control switches: it only keeps the request's own bookkeeping
 * truthful and changes nothing in Jenkins. Never throws.
 */
@Extension
@Restricted(NoExternalUse.class)
public class ApprovedRunQueueListener extends QueueListener {

    private static final Logger LOGGER = Logger.getLogger(ApprovedRunQueueListener.class.getName());

    @Override
    public void onLeft(Queue.LeftItem li) {
        if (!li.isCancelled()) {
            return;
        }
        ApprovedRunAction marker = li.getAction(ApprovedRunAction.class);
        if (marker == null || !(li.task instanceof hudson.model.Job)) {
            return;
        }
        try {
            RunRequestService.get().recordQueueCancelled(marker.getRequestId(),
                    ((hudson.model.Job<?, ?>) li.task).getFullName());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not record the cancelled queue item of run request "
                    + marker.getRequestId());
        }
    }
}
