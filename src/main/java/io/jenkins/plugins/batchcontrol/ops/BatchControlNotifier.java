package io.jenkins.plugins.batchcontrol.ops;

import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * Extension point receiving Batch Control request events (SPEC item 13, D-36). Implementations
 * are registered with {@code @Extension}; the plugin ships an e-mail implementation for the
 * Mailer plugin, and other plugins can contribute channels such as chat.
 *
 * <p>Calls happen on a background thread after the state change is persisted. An exception
 * thrown by an implementation is logged and never affects the request action or other
 * notifiers. Implementations should not block for long.
 */
public abstract class BatchControlNotifier implements ExtensionPoint {

    /**
     * Delivers one event.
     *
     * @param event what happened
     * @param notification the request details and the intended recipients
     */
    public abstract void notify(NotificationEvent event, Notification notification);

    /** All registered notifiers. */
    public static ExtensionList<BatchControlNotifier> all() {
        return ExtensionList.lookup(BatchControlNotifier.class);
    }
}
