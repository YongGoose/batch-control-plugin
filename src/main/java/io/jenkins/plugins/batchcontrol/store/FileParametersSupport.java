package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.ExtensionList;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.Queue;
import io.jenkins.plugins.file_parameters.AbstractFileParameterValue;
import io.jenkins.plugins.file_parameters.Base64FileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterDefinition;
import io.jenkins.plugins.file_parameters.StashedFileParameterValue;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The only place where Batch Control uses the optional file-parameters plugin
 * ({@code stashedFile}, {@code base64File}). It calls the plugin's public API directly.
 *
 * <p>The plugin may be missing, so every method first checks that it is installed and active
 * ({@link #isInstalled()}). Only then does it touch the nested {@code Linked} class, the one class
 * that refers to the plugin's types. Without the plugin {@code Linked} is never loaded, and each
 * method answers as it would for a value or definition of any other type.
 */
@Restricted(NoExternalUse.class)
public final class FileParametersSupport {

    /** Short name of the file-parameters plugin. */
    static final String SHORT_NAME = "file-parameters";

    private FileParametersSupport() {
    }

    /** Whether the file-parameters plugin is installed and active. */
    public static boolean isInstalled() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null && jenkins.getPlugin(SHORT_NAME) != null;
    }

    /** Whether {@code value} is one of the plugin's file values ({@code stashedFile}, {@code base64File}). */
    public static boolean isFileValue(@CheckForNull ParameterValue value) {
        return value != null && isInstalled() && Linked.isFileValue(value);
    }

    /**
     * The original file name of one of the plugin's file values, as
     * {@code AbstractFileParameterValue#getFilename()} returns it; {@code null} for any other value.
     */
    @CheckForNull
    public static String fileName(@CheckForNull ParameterValue value) {
        return value != null && isInstalled() ? Linked.fileName(value) : null;
    }

    /**
     * Whether {@code value} is the plugin's stashed file value. Such a value keeps its upload in a
     * temporary file under {@code $JENKINS_HOME/stashedFileParameterValueFiles/} until its build
     * takes the file over. A Base64 file value keeps its content in the value itself.
     */
    public static boolean keepsTemporaryFile(@CheckForNull ParameterValue value) {
        return value != null && isInstalled() && Linked.isStashed(value);
    }

    /**
     * Whether {@code definition} is one of the plugin's file definitions ({@code stashedFile},
     * {@code base64File}). Their common base class is not public, so both are named.
     */
    public static boolean isFileDefinition(@CheckForNull ParameterDefinition definition) {
        return definition != null && isInstalled() && Linked.isFileDefinition(definition);
    }

    /**
     * Hands the cancelled queue item {@code cancelled} to the plugin's own listener for cancelled
     * items, {@code StashedFileParameterValue.CancelledQueueListener}. That listener deletes the
     * temporary file of each stashed value the item carries. The plugin offers no other public way
     * to delete that file: the value does not expose it and has no delete method. Does nothing
     * without the plugin.
     */
    public static void disposeCancelled(Queue.LeftItem cancelled) {
        if (isInstalled()) {
            Linked.disposeCancelled(cancelled);
        }
    }

    /** Refers to the plugin's types; loaded only after {@link #isInstalled()} answered {@code true}. */
    private static final class Linked {

        private Linked() {
        }

        static boolean isFileValue(ParameterValue value) {
            return value instanceof AbstractFileParameterValue;
        }

        @CheckForNull
        static String fileName(ParameterValue value) {
            return value instanceof AbstractFileParameterValue file ? file.getFilename() : null;
        }

        static boolean isStashed(ParameterValue value) {
            return value instanceof StashedFileParameterValue;
        }

        static boolean isFileDefinition(ParameterDefinition definition) {
            return definition instanceof StashedFileParameterDefinition
                    || definition instanceof Base64FileParameterDefinition;
        }

        static void disposeCancelled(Queue.LeftItem cancelled) {
            for (StashedFileParameterValue.CancelledQueueListener listener
                    : ExtensionList.lookup(StashedFileParameterValue.CancelledQueueListener.class)) {
                listener.onLeft(cancelled);
            }
        }
    }
}
