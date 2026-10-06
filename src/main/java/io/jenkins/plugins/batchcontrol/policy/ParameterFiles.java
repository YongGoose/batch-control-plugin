package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import io.jenkins.plugins.batchcontrol.store.BatchClock;
import io.jenkins.plugins.batchcontrol.store.FileParametersSupport;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
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
 * <p>Two parameter types keep such a file: core's {@link FileParameterValue}
 * ({@code $JENKINS_HOME/fileParameterValueFiles/}) and the file-parameters plugin's stashed file
 * value ({@code $JENKINS_HOME/stashedFileParameterValueFiles/}, through
 * {@link FileParametersSupport} when that plugin is installed). The plugin's Base64 file value keeps
 * its content in the value itself.
 *
 * <p>D-74 (2): why a cancelled queue item. Neither type exposes its temporary file or a method that
 * deletes it (core's {@code FileParameterValue#getFile2()} returns the upload, not the copy it
 * keeps). The only supported cleanup each offers is its public listener for cancelled queue items,
 * {@code FileParameterValue.CancelledQueueListener} and
 * {@code StashedFileParameterValue.CancelledQueueListener}. Each deletes the temporary files of its
 * own values in a cancelled {@link Queue.LeftItem}. So this class calls exactly those two
 * listeners, by their classes, with a cancelled item that carries the values. No other queue
 * listener is called, nothing enters the queue, and no private field is read. A listener that
 * fails is logged; the listeners log their own failed deletions. A value of any other type is left
 * alone.
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
        if (values == null || values.isEmpty() || Jenkins.getInstanceOrNull() == null) {
            return;
        }
        List<ParameterValue> core = new ArrayList<>();
        List<ParameterValue> stashed = new ArrayList<>();
        for (ParameterValue value : values) {
            if (value instanceof FileParameterValue) {
                core.add(value);
            } else if (FileParametersSupport.keepsTemporaryFile(value)) {
                stashed.add(value);
            }
        }
        if (!core.isEmpty()) {
            Queue.LeftItem cancelled = cancelledItem(core, owner);
            try {
                for (FileParameterValue.CancelledQueueListener listener
                        : ExtensionList.lookup(FileParameterValue.CancelledQueueListener.class)) {
                    listener.onLeft(cancelled);
                }
                LOGGER.fine(() -> "Disposed of the temporary files of parameter(s) " + names(core) + " of " + owner);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not dispose of the temporary files of parameter(s) "
                        + names(core) + " of " + owner);
            }
        }
        if (!stashed.isEmpty()) {
            Queue.LeftItem cancelled = cancelledItem(stashed, owner);
            try {
                FileParametersSupport.disposeCancelled(cancelled);
                LOGGER.fine(() -> "Disposed of the temporary files of parameter(s) " + names(stashed) + " of " + owner);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not dispose of the temporary files of parameter(s) "
                        + names(stashed) + " of " + owner);
            }
        }
    }

    /**
     * Whether {@code value} keeps a temporary file until its build takes it over: core's file value
     * or the file-parameters plugin's stashed file value. Such a value of a completed build cannot
     * be reused as it is: its build consumed the file.
     */
    public static boolean keepsTemporaryFile(ParameterValue value) {
        return value instanceof FileParameterValue || FileParametersSupport.keepsTemporaryFile(value);
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

    /** The task of the never-scheduled item handed to the two listeners. */
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
