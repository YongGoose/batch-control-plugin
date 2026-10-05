package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72: disposal of the temporary files that typed parameter values keep until a build takes
 * them over, for values that will never reach the queue: a run request that ended without a run
 * (REJECTED, CANCELLED, EXPIRED, INVALIDATED before it was queued), a submission that was refused
 * before a request was stored, a person's own build submission that the queue gate refused, or
 * rerun values that could not be completed. Once a request's build is queued, the queue and the
 * build own its files and nothing here touches them.
 *
 * <p>D-74 (2): why a cancelled queue item. Each parameter type that keeps such a file deletes it
 * itself when its queue item is cancelled, through a {@link QueueListener} nested in the value
 * class: core's {@code FileParameterValue.CancelledQueueListener}
 * ({@code $JENKINS_HOME/fileParameterValueFiles/}) and the file-parameters plugin's
 * {@code StashedFileParameterValue.CancelledQueueListener}
 * ({@code $JENKINS_HOME/stashedFileParameterValueFiles/}). That listener is the only supported
 * cleanup either type offers: neither exposes its temporary file or a delete method (core's
 * {@code FileParameterValue#getFile2()} returns the upload, not the copy it keeps, and the
 * file-parameters plugin, an optional plugin Batch Control does not link, has no public accessor).
 * So this class hands exactly those value-specific listeners a cancelled {@link Queue.LeftItem}
 * carrying the values, which is the convention they already implement; no other queue listener is
 * called, nothing enters the queue, and no private field is read. A listener that fails is
 * logged; a listener logs its own failed deletions.
 */
@Restricted(NoExternalUse.class)
public final class ParameterFiles {

    private static final Logger LOGGER = Logger.getLogger(ParameterFiles.class.getName());

    private ParameterFiles() {
    }

    /**
     * Disposes of the temporary files held by {@code values}, which must never be (or have been)
     * handed to the queue. {@code owner} names them in the log (a request id, a refused
     * submission). Never throws: what cannot be disposed of is logged.
     */
    public static void dispose(@CheckForNull List<? extends ParameterValue> values, String owner) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<ParameterValue> present = new ArrayList<>(values.size());
        for (ParameterValue value : values) {
            if (value != null) {
                present.add(value);
            }
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (present.isEmpty() || jenkins == null) {
            return;
        }
        Set<ParameterValue> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        Queue.LeftItem cancelled = null;
        for (QueueListener listener : ExtensionList.lookup(QueueListener.class)) {
            Class<?> valueType = valueTypeOf(listener);
            if (valueType == null) {
                continue;
            }
            List<ParameterValue> own = new ArrayList<>();
            for (ParameterValue value : present) {
                if (valueType.isInstance(value)) {
                    own.add(value);
                }
            }
            if (own.isEmpty()) {
                continue;
            }
            try {
                if (cancelled == null) {
                    cancelled = cancelledItem(present, owner);
                }
                listener.onLeft(cancelled);
                handled.addAll(own);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not dispose of the temporary files of parameter(s) "
                        + names(own) + " of " + owner + " through " + listener.getClass().getName());
            }
        }
        if (!handled.isEmpty()) {
            LOGGER.fine(() -> "Disposed of the temporary files of parameter(s) " + names(new ArrayList<>(handled))
                    + " of " + owner);
        }
    }

    /**
     * The {@link ParameterValue} class a queue listener is nested in (in its own class or a
     * superclass of it), or {@code null} for every other listener.
     */
    @CheckForNull
    private static Class<?> valueTypeOf(QueueListener listener) {
        for (Class<?> type = listener.getClass(); type != null && type != QueueListener.class;
                type = type.getSuperclass()) {
            Class<?> enclosing = type.getEnclosingClass();
            if (enclosing != null && ParameterValue.class.isAssignableFrom(enclosing)) {
                return enclosing;
            }
        }
        return null;
    }

    /**
     * Whether {@code value} keeps a temporary file until its build takes it over: its type has a
     * value-specific queue listener (the disposal convention above), as core's file value and the
     * file-parameters plugin's stashed file value do. Such a value of a completed build cannot be
     * reused as it is: its build consumed the file.
     */
    public static boolean keepsTemporaryFile(ParameterValue value) {
        if (Jenkins.getInstanceOrNull() == null) {
            return false;
        }
        for (QueueListener listener : ExtensionList.lookup(QueueListener.class)) {
            Class<?> valueType = valueTypeOf(listener);
            if (valueType != null && valueType.isInstance(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A cancelled {@link Queue.LeftItem} carrying {@code values} in a {@link ParametersAction}.
     * It is built from a {@link Queue.WaitingItem} that is never scheduled, for a stand-in task
     * named after {@code owner}, so no job needs to exist or be readable.
     */
    private static Queue.LeftItem cancelledItem(List<ParameterValue> values, String owner) {
        List<Action> actions = new ArrayList<>(1);
        actions.add(new ParametersAction(values));
        Calendar timestamp = Calendar.getInstance();
        timestamp.setTimeInMillis(BatchClock.now().toEpochMilli());
        return new Queue.LeftItem(new Queue.WaitingItem(timestamp, new DisposalTask(owner), actions));
    }

    private static String names(List<ParameterValue> values) {
        List<String> names = new ArrayList<>(values.size());
        for (ParameterValue value : values) {
            names.add("'" + value.getName() + "'");
        }
        return String.join(", ", names);
    }

    /** The task of the never-scheduled item handed to the value-specific listeners. */
    private static final class DisposalTask implements Queue.Task {

        private final String name;

        DisposalTask(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDisplayName() {
            return name;
        }

        @Override
        public String getFullDisplayName() {
            return name;
        }

        @Override
        public String getUrl() {
            return "";
        }

        @Override
        public Queue.Executable createExecutable() {
            return null;
        }
    }
}
