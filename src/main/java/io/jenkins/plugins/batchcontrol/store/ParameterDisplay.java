package io.jenkins.plugins.batchcontrol.store;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FileParameterValue;
import hudson.model.ParameterValue;
import hudson.util.Secret;
import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72: the one place where typed {@link ParameterValue}s become the masked text that Batch
 * Control displays or writes (the request's {@code parameters} map, run records, incidents, CSV).
 *
 * <ul>
 *   <li>a sensitive value ({@link ParameterValue#isSensitive()}, a {@link Secret}) is
 *       {@value SecretMasker#MASK};</li>
 *   <li>a file value is {@value #FILE_PREFIX} followed by its original file name, never its
 *       content, its Base64 text or a server path;</li>
 *   <li>anything else is {@code String.valueOf(value.getValue())}.</li>
 * </ul>
 *
 * <p>File values are recognised without linking the optional file-parameters plugin: core's
 * {@link FileParameterValue}, every subclass of the plugin's
 * {@value #FILE_PARAMETERS_VALUE} (by class name), and any other value whose raw value is an
 * uploaded file item or a {@link File}.
 */
@Restricted(NoExternalUse.class)
public final class ParameterDisplay {

    /** Display prefix of a file value (D-72). */
    public static final String FILE_PREFIX = "[file]";

    /** Base class of the file-parameters plugin's values (stashedFile, base64File); not linked. */
    static final String FILE_PARAMETERS_VALUE = "io.jenkins.plugins.file_parameters.AbstractFileParameterValue";

    /** The file-parameters plugin's stashed file value, whose upload waits in a temporary directory. */
    public static final String STASHED_FILE_VALUE = "io.jenkins.plugins.file_parameters.StashedFileParameterValue";

    private ParameterDisplay() {
    }

    /**
     * The masked display map of {@code values}, by parameter name in submission order. Values
     * without a name are left out; with a repeated name the first value wins, as
     * {@code ParametersAction#getParameter} reads it.
     */
    public static Map<String, String> masked(@CheckForNull Collection<? extends ParameterValue> values) {
        Map<String, String> display = new LinkedHashMap<>();
        if (values == null) {
            return display;
        }
        for (ParameterValue value : values) {
            if (value != null && value.getName() != null && !display.containsKey(value.getName())) {
                display.put(value.getName(), text(value));
            }
        }
        return display;
    }

    /** The masked display text of one value (see the class description). */
    public static String text(ParameterValue value) {
        if (value.isSensitive()) {
            return SecretMasker.MASK;
        }
        if (isFile(value)) {
            String name = fileName(value);
            return name == null || name.isEmpty() ? FILE_PREFIX : FILE_PREFIX + " " + name;
        }
        Object raw = value.getValue();
        if (raw instanceof Secret) {
            return SecretMasker.MASK;
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    /** Whether {@code value} is a file value (see the class description). */
    public static boolean isFile(ParameterValue value) {
        if (value instanceof FileParameterValue || isA(value, FILE_PARAMETERS_VALUE)) {
            return true;
        }
        Object raw = value.getValue();
        return raw instanceof org.apache.commons.fileupload2.core.FileItem || raw instanceof File;
    }

    /** Whether {@code value} is the file-parameters plugin's stashed file value (by class name). */
    public static boolean isStashedFile(ParameterValue value) {
        return isA(value, STASHED_FILE_VALUE);
    }

    /** Whether {@code object}'s class is, or extends, the class named {@code className}. */
    static boolean isA(@CheckForNull Object object, String className) {
        for (Class<?> type = object == null ? null : object.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /** The original file name of a file value without any directory part, or {@code null}. */
    @CheckForNull
    private static String fileName(ParameterValue value) {
        String name;
        if (value instanceof FileParameterValue) {
            name = ((FileParameterValue) value).getOriginalFileName();
        } else if (isA(value, FILE_PARAMETERS_VALUE)) {
            name = pluginFileName(value);
        } else {
            Object raw = value.getValue();
            if (raw instanceof org.apache.commons.fileupload2.core.FileItem) {
                name = ((org.apache.commons.fileupload2.core.FileItem<?>) raw).getName();
            } else if (raw instanceof File) {
                name = ((File) raw).getName();
            } else {
                name = null;
            }
        }
        return baseName(name);
    }

    /**
     * {@code AbstractFileParameterValue#getFilename()} of the file-parameters plugin, called
     * reflectively because the plugin is optional; {@code null} when it cannot be read.
     */
    @CheckForNull
    private static String pluginFileName(ParameterValue value) {
        try {
            Object name = value.getClass().getMethod("getFilename").invoke(value);
            return name instanceof String ? (String) name : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The last segment of {@code name} after any {@code /} or {@code \}, or {@code null}. */
    @CheckForNull
    private static String baseName(@CheckForNull String name) {
        if (name == null) {
            return null;
        }
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return cut < 0 ? name : name.substring(cut + 1);
    }
}
