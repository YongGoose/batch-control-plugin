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
 * <p>File values are core's {@link FileParameterValue}, the file values of the optional
 * file-parameters plugin when it is installed ({@link FileParametersSupport}), and any other value
 * whose raw value is an uploaded file item or a {@link File}.
 */
@Restricted(NoExternalUse.class)
public final class ParameterDisplay {

    /** Display prefix of a file value (D-72). */
    public static final String FILE_PREFIX = "[file]";

    private ParameterDisplay() {
    }

    /**
     * The masked display map of {@code values}, by parameter name in submission order. Values
     * without a name are left out; with a repeated name the first value wins, as
     * {@code ParametersAction#getParameter} reads it (a run request refuses repeated names before
     * this is derived, D-72b (1); run records and incidents mask a build's own values).
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

    /**
     * D-72b (2): the text a non-file value stores, which the length and character limits apply to:
     * the plaintext of a {@link Secret} (a password included, although it is displayed masked),
     * otherwise {@code String.valueOf(value.getValue())}, {@code ""} for {@code null}. A file
     * value has none ({@code null}): its content, Base64 included, is bounded by the body cap only.
     * {@code null} as well when the value cannot be read as text.
     */
    @CheckForNull
    public static String storedText(ParameterValue value) {
        if (isFile(value)) {
            return null;
        }
        try {
            Object raw = value.getValue();
            if (raw instanceof Secret) {
                return ((Secret) raw).getPlainText();
            }
            return raw == null ? "" : String.valueOf(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code value} is a file value (see the class description). */
    public static boolean isFile(ParameterValue value) {
        if (value instanceof FileParameterValue || FileParametersSupport.isFileValue(value)) {
            return true;
        }
        Object raw = value.getValue();
        return raw instanceof org.apache.commons.fileupload2.core.FileItem || raw instanceof File;
    }

    /** The original file name of a file value without any directory part, or {@code null}. */
    @CheckForNull
    private static String fileName(ParameterValue value) {
        String name;
        if (value instanceof FileParameterValue) {
            name = ((FileParameterValue) value).getOriginalFileName();
        } else if (FileParametersSupport.isFileValue(value)) {
            name = FileParametersSupport.fileName(value);
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
