package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.ParameterValue;
import hudson.util.Secret;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.FileItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-72, D-74 (2): the size cap on a run request submission. Requesting a run does not require
 * {@code Item/Build} (D-38a), and a submission may carry file parameters, so its size is checked
 * in two stages, each answered with HTTP 413 and nothing created or kept:
 * <ol>
 *   <li>{@link #exceeds}: the declared {@code Content-Length}, after the permission checks and
 *       before the form is read. This is an early filter only; a body without a declared length
 *       passes it.</li>
 *   <li>{@link #checkKept}: what the request would keep, before anything is stored: the size of
 *       each value (its stored text, or its file content) and of the uploaded parts it was
 *       created from ({@link #uploadedSize}), measured on what the submission actually created
 *       whatever names, encodings or part kinds the client used.</li>
 * </ol>
 * Core has already parsed a multipart body into Stapler's temporary upload directory while it
 * dispatched the URL (D-72a); the instance-wide bound on that is Stapler's
 * {@code org.kohsuke.stapler.RequestImpl.FILEUPLOAD_MAX_SIZE}, not this cap.
 *
 * <p>The cap is the system property {@value #PROPERTY} (bytes), default {@value #DEFAULT_MAX_BYTES}
 * (100 MB), read on every check. A missing, unparsable or non-positive value means the default.
 */
@Restricted(NoExternalUse.class)
public final class RequestBodyLimit {

    private static final Logger LOGGER = Logger.getLogger(RequestBodyLimit.class.getName());

    /** System property holding the cap in bytes. */
    public static final String PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    /** Default cap: 100 MB. */
    public static final long DEFAULT_MAX_BYTES = 104_857_600L;

    private RequestBodyLimit() {
    }

    /** The current cap in bytes. */
    public static long maxRequestBodyBytes() {
        Long configured = SystemProperties.getLong(PROPERTY, DEFAULT_MAX_BYTES);
        return configured == null || configured <= 0 ? DEFAULT_MAX_BYTES : configured;
    }

    /**
     * Stage 1: whether the declared {@code Content-Length} of the run request submission
     * {@code req} is over the cap, from the header alone, for any content type; nothing of the body
     * is read. A body without a declared length is judged by stage 2 ({@link #checkKept}). Call it
     * after the permission checks and before anything reads the submitted values.
     */
    public static boolean exceeds(HttpServletRequest req) {
        return req.getContentLengthLong() > maxRequestBodyBytes();
    }

    /**
     * The size in bytes of the uploaded parts of {@code req} named by {@code partNames}, the parts
     * a parameter definition's {@code createValue} reads: for core's form, every top-level value of
     * the parameter's {@code json} entry as text ({@link #partNames(JSONObject)}, a file parameter
     * names its part in {@code file}); for a scripted submission without {@code json}, the
     * parameter's own name. A name with no uploaded part counts nothing. {@link Long#MAX_VALUE} when
     * the parts cannot be read.
     */
    public static long uploadedSize(StaplerRequest2 req, Collection<String> partNames) {
        long total = 0;
        try {
            for (String name : partNames) {
                FileItem<?> part = name == null ? null : req.getFileItem2(name);
                if (part != null) {
                    total = saturatedAdd(total, Math.max(0, part.getSize()));
                }
            }
        } catch (ServletException | IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not measure the uploaded parts of a run request submission", e);
            return Long.MAX_VALUE;
        }
        return total;
    }

    /**
     * The part names a {@code json} parameter entry can refer to: each top-level value as
     * {@link JSONObject#getString} reads it (a number or an object as its text).
     */
    public static Set<String> partNames(JSONObject entry) {
        Set<String> names = new LinkedHashSet<>();
        for (Object key : entry.keySet()) {
            Object value = entry.opt(String.valueOf(key));
            if (value != null) {
                names.add(String.valueOf(value));
            }
        }
        return names;
    }

    /**
     * Stage 2: refuses typed values whose {@link #keptSize} is over the cap, with a
     * {@link RequestTooLargeException} naming the cap. The caller disposes of the values'
     * temporary files. Call it before anything of the request is stored.
     *
     * @param uploaded the size of the uploaded parts each value was created from, by parameter
     *        name ({@link #uploadedSize}); empty for values that were not uploaded
     */
    public static void checkKept(List<? extends ParameterValue> values, Map<String, Long> uploaded) {
        long cap = maxRequestBodyBytes();
        long size = keptSize(values, uploaded);
        if (size <= cap) {
            return;
        }
        String limit = sizeText(cap);
        if (size == Long.MAX_VALUE) {
            throw new RequestTooLargeException("The run request was not submitted: the size of one of its files"
                    + " could not be determined, so it cannot be checked against the limit of " + limit
                    + " for a run request. Nothing was saved.", cap);
        }
        throw new RequestTooLargeException("The run request was not submitted: its files and parameter values"
                + " take " + sizeText(size) + ", more than the limit of " + limit + " for a run request."
                + " Nothing was saved. Submit it again with smaller files, or ask a Jenkins administrator to"
                + " raise the limit.", cap);
    }

    /**
     * The size in bytes of what a run request holding {@code values} would keep: for each value its
     * name plus the larger of its own size ({@link #ownSize}) and the size of the uploaded parts it
     * was created from ({@code uploaded}, by name). The second covers a value whose content its
     * public API does not expose (the file-parameters plugin's stashed file keeps a copy of its
     * part); the first covers values that were not uploaded in this submission (an incident rerun).
     * {@link Long#MAX_VALUE} when a size cannot be determined (fail closed).
     */
    public static long keptSize(@CheckForNull List<? extends ParameterValue> values, Map<String, Long> uploaded) {
        long total = 0;
        if (values == null) {
            return total;
        }
        for (ParameterValue value : values) {
            if (value == null) {
                continue;
            }
            Long read = uploaded.get(value.getName());
            long size = Math.max(ownSize(value), read == null ? 0 : read);
            if (size == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            total = saturatedAdd(total, saturatedAdd(utf8Length(value.getName()), size));
        }
        return total;
    }

    /**
     * The size of what {@code value} holds, read through {@link ParameterValue#getValue()}: an
     * uploaded file item (core's file parameter) or a {@link File} by its length, a {@link Secret}
     * by its plaintext, anything else by its text in UTF-8 (a Base64 file value is kept as its
     * text); {@code 0} for {@code null}. {@link Long#MAX_VALUE} when the value cannot be read.
     */
    private static long ownSize(ParameterValue value) {
        try {
            Object raw = value.getValue();
            if (raw == null) {
                return 0;
            }
            if (raw instanceof FileItem) {
                return Math.max(0, ((FileItem<?>) raw).getSize());
            }
            if (raw instanceof File) {
                return ((File) raw).length();
            }
            if (raw instanceof Secret) {
                return utf8Length(((Secret) raw).getPlainText());
            }
            return utf8Length(String.valueOf(raw));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not determine the size of parameter '" + value.getName()
                    + "'; the run request is refused");
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    /** {@code bytes} as the form shows a size: whole MB or KB, else bytes. */
    static String sizeText(long bytes) {
        long mega = 1024L * 1024L;
        if (bytes >= mega && bytes % mega == 0) {
            return bytes / mega + " MB";
        }
        if (bytes >= 1024L && bytes % 1024L == 0) {
            return bytes / 1024L + " KB";
        }
        return String.format(Locale.ROOT, "%,d bytes", bytes);
    }

    private static long utf8Length(@CheckForNull String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
