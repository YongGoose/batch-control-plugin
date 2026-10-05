package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
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
 * before a request was stored, or rerun values that could not be completed. Once a request's
 * build is queued, the queue and the build own its files and nothing here touches them.
 *
 * <p>Parameter types that keep such a file delete it themselves when their queue item is
 * cancelled, each through a {@link QueueListener} nested in the value class: core's
 * {@code FileParameterValue.CancelledQueueListener} ({@code $JENKINS_HOME/fileParameterValueFiles/})
 * and the file-parameters plugin's {@code StashedFileParameterValue.CancelledQueueListener}
 * ({@code $JENKINS_HOME/stashedFileParameterValueFiles/}). The values never entered the queue,
 * so this class hands exactly those value-specific listeners a cancelled {@link Queue.LeftItem}
 * carrying the values, which is the convention they already implement; no other queue listener
 * is called, nothing enters the queue, and neither the plugin nor a private field is linked. A
 * value type that keeps a known temporary file but has no such listener is logged, as is a
 * listener that fails; a listener logs its own failed deletions.
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
        Set<ParameterValue> attempted = Collections.newSetFromMap(new IdentityHashMap<>());
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
            attempted.addAll(own);
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
        for (ParameterValue value : present) {
            if (!attempted.contains(value)
                    && (value instanceof FileParameterValue || ParameterDisplay.isStashedFile(value))) {
                LOGGER.warning(() -> "Could not dispose of the temporary file of parameter '" + value.getName()
                        + "' (" + value.getClass().getName() + ") of " + owner
                        + ": no disposal is available for this parameter type");
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
     * Whether {@code value} keeps a temporary file until its build takes it over: core's
     * {@link FileParameterValue}, the file-parameters plugin's stashed file value, or any value
     * whose type has a value-specific queue listener (the disposal convention above). Such a value
     * of a completed build cannot be reused as it is: its build consumed the file.
     */
    public static boolean keepsTemporaryFile(ParameterValue value) {
        if (value instanceof FileParameterValue || ParameterDisplay.isStashedFile(value)) {
            return true;
        }
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
