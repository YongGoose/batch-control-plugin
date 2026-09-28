package io.jenkins.plugins.batchcontrol;

import io.jenkins.plugins.batchcontrol.ops.Notification;
import io.jenkins.plugins.batchcontrol.ops.NotificationEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * An immutable copy of one delivered notification, plus the in-memory log the test notifiers
 * write into. A notifier may be invoked on another thread (SPEC 13: a notifier never delays the
 * request action), so the readers poll with a bounded wait instead of assuming synchronous
 * delivery. The waits are for delivery only; no row waits for an expiry in real time (time is
 * moved with {@code BatchClock}).
 */
final class NotificationCapture {

    /** How long a row waits for a notification it expects. */
    static final long DELIVERY_TIMEOUT_MS = 10_000;
    /** How long a row waits before concluding that a notification it forbids did not come. */
    static final long QUIET_PERIOD_MS = 1_500;

    private static final List<NotificationCapture> LOG = new ArrayList<>();

    final NotificationEvent event;
    final String kind;
    final String requestId;
    final String subject;
    final String requester;
    final String reason;
    final List<String> recipients;
    final String url;

    private NotificationCapture(NotificationEvent event, Notification n) {
        this.event = event;
        this.kind = n.getKind();
        this.requestId = n.getRequestId();
        this.subject = n.getSubject();
        this.requester = n.getRequester();
        this.reason = n.getReason();
        this.recipients = n.getRecipients() == null ? null : new ArrayList<>(n.getRecipients());
        this.url = n.getUrl();
    }

    static void record(NotificationEvent event, Notification notification) {
        synchronized (LOG) {
            LOG.add(new NotificationCapture(event, notification));
        }
    }

    static void clear() {
        synchronized (LOG) {
            LOG.clear();
        }
    }

    static List<NotificationCapture> matching(Predicate<NotificationCapture> filter) {
        synchronized (LOG) {
            return LOG.stream().filter(filter).collect(Collectors.toList());
        }
    }

    static List<NotificationCapture> of(NotificationEvent event, String requestId) {
        return matching(c -> c.event == event && requestId.equals(c.requestId));
    }

    /** Waits until at least one matching notification arrived and returns all matching ones. */
    static List<NotificationCapture> await(NotificationEvent event, String requestId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS;
        List<NotificationCapture> found = of(event, requestId);
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); // polling for asynchronous delivery, not waiting for an expiry
            found = of(event, requestId);
        }
        if (found.isEmpty()) {
            throw new AssertionError("no " + event + " notification for request " + requestId
                    + " arrived within " + DELIVERY_TIMEOUT_MS + " ms; delivered so far: " + describeAll());
        }
        return found;
    }

    /** Waits the quiet period and returns what arrived for the event and request by then. */
    static List<NotificationCapture> afterQuietPeriod(NotificationEvent event, String requestId)
            throws InterruptedException {
        Thread.sleep(QUIET_PERIOD_MS); // bounded wait for a delivery that must NOT happen
        return of(event, requestId);
    }

    static String describeAll() {
        synchronized (LOG) {
            return LOG.stream().map(NotificationCapture::toString).collect(Collectors.joining("; ", "[", "]"));
        }
    }

    @Override
    public String toString() {
        return event + "(" + kind + " " + requestId + " subject=" + subject + " requester=" + requester
                + " recipients=" + recipients + " url=" + url + ")";
    }
}
