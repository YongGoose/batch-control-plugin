package io.jenkins.plugins.batchcontrol.ops;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.init.Terminator;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.batchcontrol.model.ActivationRequest;
import io.jenkins.plugins.batchcontrol.model.Grant;
import io.jenkins.plugins.batchcontrol.model.GrantAction;
import io.jenkins.plugins.batchcontrol.model.GrantRequest;
import io.jenkins.plugins.batchcontrol.model.GrantScope;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
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
    private static ExecutorService executor;

    /** Created on first use in a Jenkins session; shut down by {@link #shutdown()}. */
    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    new NamingThreadFactory(new DaemonThreadFactory(), "BatchControlNotifier"),
                    (task, pool) -> LOGGER.warning("Notification queue full or shutting down; dropping a "
                            + "notification"));
        }
        return executor;
    }

    /**
     * Stops the notifier thread when Jenkins shuts down: no new work is accepted, pending
     * notifications are discarded, and a running delivery gets a short grace period before it is
     * interrupted. The next Jenkins session in the same JVM (tests) gets a fresh executor.
     */
    @Terminator
    public static void shutdown() {
        ExecutorService pool;
        synchronized (NotificationDispatcher.class) {
            pool = executor;
            executor = null;
        }
        if (pool == null) {
            return;
        }
        List<Runnable> dropped = pool.shutdownNow();
        if (!dropped.isEmpty()) {
            LOGGER.info(() -> "Jenkins is shutting down; " + dropped.size() + " pending notifications dropped");
        }
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warning("The notifier thread did not stop within 5 seconds of shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private NotificationDispatcher() {
    }

    /** Event for a run request. Recipients follow the D-36 rule for the event. */
    public static void run(NotificationEvent event, RunRequest request) {
        try {
            List<String> recipients = recipientsFor(event, request.getApprovers(), request.getRequester());
            // D-38a: the approver is told when the requester lacks Item/Build, so approving also
            // authorises a run the requester could not start. Evaluated here, on the caller's
            // thread, not on the delivery thread.
            List<String> notices = (event == NotificationEvent.REQUEST_CREATED
                    || event == NotificationEvent.APPROVERS_CHANGED)
                    && RunRequestService.get().requesterLacksBuild(request)
                    ? List.of(RunRequestService.REQUESTER_LACKS_BUILD_NOTICE) : null;
            dispatch(event, new Notification(Notification.KIND_RUN, request.getId(),
                    request.getJobFullName(), request.getRequester(), request.getReason(), recipients,
                    url("batch-control/requests/" + request.getId() + "/"), null, null, notices));
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
                    recipients, url("batch-control/grants/" + request.getId() + "/"), null,
                    grantDetails(request.getScope(), request.getActions(), request.getDurationMinutes(),
                            request.getCreateNamePattern())));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of grant request "
                    + request.getId(), e);
        }
    }

    /**
     * Event for an activation or hold request (SPEC item 6a). Recipients follow the D-36 rule for
     * the event; the kind is {@link Notification#KIND_ACTIVATION}.
     */
    public static void activation(NotificationEvent event, ActivationRequest request) {
        try {
            List<String> recipients = recipientsFor(event, request.getApprovers(), request.getRequester());
            dispatch(event, new Notification(Notification.KIND_ACTIVATION, request.getId(),
                    request.getJobFullName(), request.getRequester(), request.getReason(), recipients,
                    url("batch-control/activations/" + request.getId() + "/"), request.getAction().name()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of activation request "
                    + request.getId(), e);
        }
    }

    /**
     * D-54: {@link NotificationEvent#CANCELLED}, {@link NotificationEvent#EXPIRED} or
     * {@link NotificationEvent#INVALIDATED} for a run request that ended without a decision.
     *
     * @param wasPending whether the request was PENDING when it ended (its approvers are told)
     * @param reason     why it ended, shown in the message; may be {@code null}
     */
    public static void runEnded(NotificationEvent event, RunRequest request, boolean wasPending,
                                @CheckForNull String reason) {
        try {
            dispatch(event, new Notification(Notification.KIND_RUN, request.getId(), request.getJobFullName(),
                    request.getRequester(), request.getReason(),
                    endRecipients(event, request.getApprovers(), request.getRequester(), wasPending),
                    url("batch-control/requests/" + request.getId() + "/"), null, endDetails(reason)));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of run request "
                    + request.getId(), e);
        }
    }

    /** As {@link #runEnded} for a change (grant) request. */
    public static void grantEnded(NotificationEvent event, GrantRequest request, boolean wasPending,
                                  @CheckForNull String reason) {
        try {
            List<String> details = grantDetails(request.getScope(), request.getActions(),
                    request.getDurationMinutes(), request.getCreateNamePattern());
            details.addAll(0, endDetails(reason));
            dispatch(event, new Notification(Notification.KIND_GRANT, request.getId(),
                    request.getScope().getFullName(), request.getRequester(), request.getReason(),
                    endRecipients(event, request.getApprovers(), request.getRequester(), wasPending),
                    url("batch-control/grants/" + request.getId() + "/"), null, details));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of grant request "
                    + request.getId(), e);
        }
    }

    /** As {@link #runEnded} for an activation or hold request. */
    public static void activationEnded(NotificationEvent event, ActivationRequest request, boolean wasPending,
                                       @CheckForNull String reason) {
        try {
            dispatch(event, new Notification(Notification.KIND_ACTIVATION, request.getId(),
                    request.getJobFullName(), request.getRequester(), request.getReason(),
                    endRecipients(event, request.getApprovers(), request.getRequester(), wasPending),
                    url("batch-control/activations/" + request.getId() + "/"), request.getAction().name(),
                    endDetails(reason)));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the " + event + " notification of activation request "
                    + request.getId(), e);
        }
    }

    /**
     * D-54 recipients: the designated approvers when the request was pending; the requester for
     * EXPIRED and INVALIDATED, and for CANCELLED only when someone else cancelled it. The user
     * acting now (the canceller) is never mailed about their own action.
     */
    static List<String> endRecipients(NotificationEvent event, List<String> approvers, String requester,
                                      boolean wasPending) {
        java.util.LinkedHashSet<String> recipients = new java.util.LinkedHashSet<>();
        if (requester != null) {
            recipients.add(requester);
        }
        if (wasPending && approvers != null) {
            recipients.addAll(approvers);
        }
        if (event == NotificationEvent.CANCELLED) {
            String actor = Jenkins.getInstanceOrNull() == null ? null : Jenkins.getAuthentication2().getName();
            recipients.removeIf(id -> io.jenkins.plugins.batchcontrol.model.Approvers.sameUser(id, actor));
        }
        return new ArrayList<>(recipients);
    }

    private static List<String> endDetails(@CheckForNull String reason) {
        List<String> details = new ArrayList<>();
        if (reason != null && !reason.trim().isEmpty()) {
            // e2e-04 UX-18: labelled apart from the request's own "Reason:" block of the mail.
            details.add("Why it ended: " + reason.trim());
        }
        return details;
    }

    /** {@link NotificationEvent#GRANT_EXPIRING} for an active window, sent to its holder. */
    public static void grantExpiring(Grant grant, String reason) {
        try {
            String requestId = grant.getGrantRequestId();
            dispatch(NotificationEvent.GRANT_EXPIRING, new Notification(Notification.KIND_GRANT, requestId,
                    grant.getScope().getFullName(), grant.getUser(), reason,
                    grant.getUser() == null ? Collections.emptyList() : List.of(grant.getUser()),
                    url("batch-control/grants/" + requestId + "/"), null,
                    grantDetails(grant.getScope(), grant.getActions(), 0, grant.getCreateNamePattern())));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not build the GRANT_EXPIRING notification of grant "
                    + grant.getId(), e);
        }
    }

    /**
     * e2e-03 DEF-24: what the approver decides on, so the mail can be judged without opening the
     * request: scope type, actions, duration (when known) and the Create name restriction.
     */
    static List<String> grantDetails(GrantScope scope, List<GrantAction> actions, int durationMinutes,
                                     String createNamePattern) {
        List<String> details = new ArrayList<>();
        if (scope != null && scope.getType() != null) {
            details.add("Scope type: " + scope.getType());
        }
        if (actions != null && !actions.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (GrantAction action : actions) {
                names.add(action.name());
            }
            details.add("Actions: " + String.join(", ", names));
        }
        if (durationMinutes > 0) {
            details.add("Duration: " + durationMinutes + (durationMinutes == 1 ? " minute" : " minutes"));
        }
        if (createNamePattern != null) {
            details.add("Name restriction (Create): " + createNamePattern);
        }
        return details;
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
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null || jenkins.isTerminating()) {
            return; // never start background work once shutdown has begun
        }
        try {
            executor().execute(() -> deliver(notifiers, event, notification));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not queue the " + event + " notification " + notification, e);
        }
    }

    private static void deliver(List<BatchControlNotifier> notifiers, NotificationEvent event,
                                Notification notification) {
        for (BatchControlNotifier notifier : notifiers) {
            if (Thread.currentThread().isInterrupted()) {
                return; // shutting down
            }
            try {
                notifier.notify(event, notification);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.log(Level.WARNING, "Notifier " + notifier.getClass().getName() + " failed for "
                        + event + " " + notification, e);
            }
        }
    }
}
