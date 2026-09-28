package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Hands request events to every {@link BatchControlNotifier} on a background thread (D-36).
 * Callers (the policy services and the expiry work) call it after the state change is
 * persisted. Nothing here can fail or delay the calling request: building the notification,
 * submission and every notifier call are guarded, and failures are only logged.
 */
@Restricted(NoExternalUse.class)
public final class NotificationDispatcher {

    private static final Logger LOGGER = Logger.getLogger(NotificationDispatcher.class.getName());

    /** Pending notifications beyond this are dropped and logged (security-08 S-09). */
    static final int QUEUE_CAPACITY = 1000;

    /**
     * One daemon thread keeps events in order and never holds up a request thread. The queue is
     * bounded, so a hung mail server or a flood of approver changes cannot grow memory without
     * limit; on overflow the notification is dropped and logged.
     */
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new NamingThreadFactory(new DaemonThreadFactory(), "BatchControlNotifier"),
            (task, executor) -> LOGGER.warning("Notification queue full (" + QUEUE_CAPACITY
                    + " pending); dropping a notification (D-36)"));

    private NotificationDispatcher() {
    }

    /** Event for a run request. Recipients follow the D-36 rule for the event. */
    public static void run(NotificationEvent event, RunRequest request) {
        try {
            List<String> recipients = recipientsFor(event, request.getApprovers(), request.getRequester());
            dispatch(event, new Notification(Notification.KIND_RUN, request.getId(),
                    request.getJobFullName(), request.getRequester(), request.getReason(), recipients,
                    url("batch-control/requests/" + request.getId() + "/")));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of run request "
                    + request.getId(), e);
        }
    }

    /** Event for a change (grant) request. Recipients follow the D-36 rule for the event. */
    public static void grant(NotificationEvent event, GrantRequest request) {
        try {
            List<String> recipients = recipientsFor(event, request.getApprovers(), request.getRequester());
            dispatch(event, new Notification(Notification.KIND_GRANT, request.getId(),
                    request.getScope().getFullName(), request.getRequester(), request.getReason(),
                    recipients, url("batch-control/grants/" + request.getId() + "/")));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of grant request "
                    + request.getId(), e);
        }
    }

    /** {@link NotificationEvent#GRANT_EXPIRING} for an active window, sent to its holder. */
    public static void grantExpiring(Grant grant, String reason) {
        try {
            String requestId = grant.getGrantRequestId() != null ? grant.getGrantRequestId() : grant.getId();
            dispatch(NotificationEvent.GRANT_EXPIRING, new Notification(Notification.KIND_GRANT, requestId,
                    grant.getScope().getFullName(), grant.getUser(), reason,
                    grant.getUser() == null ? Collections.emptyList() : List.of(grant.getUser()),
                    url("batch-control/grants/" + requestId + "/")));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the GRANT_EXPIRING notification of grant "
                    + grant.getId(), e);
        }
    }

    private static List<String> recipientsFor(NotificationEvent event, List<String> approvers, String requester) {
        if (event == NotificationEvent.REQUEST_CREATED || event == NotificationEvent.APPROVERS_CHANGED) {
            return new ArrayList<>(approvers);
        }
        return requester == null ? Collections.emptyList() : List.of(requester);
    }

    /**
     * The link to the request page from the configured Jenkins URL only, or {@code null} when none
     * is configured (security-08 S-04): {@code Jenkins#getRootUrl()} would fall back to the current
     * request's {@code Host}/{@code X-Forwarded-Host}, which the requester controls.
     */
    @CheckForNull
    private static String url(String path) {
        if (Jenkins.getInstanceOrNull() == null) {
            return null;
        }
        String root = Util.fixEmptyAndTrim(JenkinsLocationConfiguration.get().getUrl());
        if (root == null) {
            return null;
        }
        return (root.endsWith("/") ? root : root + "/") + path;
    }

    /** Submits the event to every notifier on the background thread. */
    static void dispatch(NotificationEvent event, Notification notification) {
        List<BatchControlNotifier> notifiers;
        try {
            notifiers = new ArrayList<>(BatchControlNotifier.all());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not look up notifiers for " + event, e);
            return;
        }
        if (notifiers.isEmpty() || notification.getRecipients().isEmpty()) {
            return;
        }
        try {
            EXECUTOR.execute(() -> deliver(notifiers, event, notification));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not queue the " + event + " notification " + notification, e);
        }
    }

    private static void deliver(List<BatchControlNotifier> notifiers, NotificationEvent event,
                                Notification notification) {
        for (BatchControlNotifier notifier : notifiers) {
            try {
                notifier.notify(event, notification);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.log(Level.WARNING, "Notifier " + notifier.getClass().getName() + " failed for "
                        + event + " " + notification, e);
            }
        }
    }
}
