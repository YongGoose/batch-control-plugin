package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FileParameterValue;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import io.jenkins.plugins.batchcontrol.store.ParameterDisplay;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.SystemProperties;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.fileupload2.core.FileItem;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-72: the cap on the body of a run request submission. Requesting a run does not require
 * {@code Item/Build} (D-38a), and a submission may carry file parameters, so the body size is
 * checked after the permission check and before the form is read; a submission over the cap is
 * answered with HTTP 413 and no request is created.
 *
 * <p>The cap is the system property {@value #PROPERTY} (bytes), default {@value #DEFAULT_MAX_BYTES}
 * (100 MB), read on every check. A missing, unparsable or non-positive value means the default.
 *
 * <p>D-72b (4), spec-review-S7 m-3/O-1: a body that declares its length is judged from that
 * declaration alone, whatever its content type, and nothing of it is read. A body without a
 * declared length (chunked) is judged by the actual size of what Jenkins parsed from it
 * ({@link #parsedSize}), not refused outright.
 *
 * <p>security-37 S-37-01: both body checks are early filters only, because a submission can make
 * the endpoint read parts the prediction does not count (a file reference given as a JSON number,
 * {@code json} sent as a file part). What decides is the size of what the run request would keep,
 * measured on the values the submission actually created ({@link #keptSize}, checked by
 * {@link #checkKept} in the service before anything is stored), whatever names, encodings or part
 * kinds the client used.
 */
@Restricted(NoExternalUse.class)
public final class RequestBodyLimit {

    private static final Logger LOGGER = Logger.getLogger(RequestBodyLimit.class.getName());

    /** System property holding the cap in bytes. */
    public static final String PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    /** Default cap: 100 MB. */
    public static final long DEFAULT_MAX_BYTES = 104_857_600L;

    /** The file-parameters plugin's Base64 value, whose raw value is its content in Base64; not linked. */
    private static final String BASE64_FILE_VALUE = "io.jenkins.plugins.file_parameters.Base64FileParameterValue";

    /** The field of the file-parameters plugin's stashed value holding its upload's path; not linked. */
    private static final String STASHED_FILE_FIELD = "tmpFile";

    /** The form field core's forms post their structured data in. */
    private static final String JSON_FIELD = "json";

    private RequestBodyLimit() {
    }

    /** The current cap in bytes. */
    public static long maxRequestBodyBytes() {
        Long configured = SystemProperties.getLong(PROPERTY, DEFAULT_MAX_BYTES);
        return configured == null || configured <= 0 ? DEFAULT_MAX_BYTES : configured;
    }

    /**
     * As {@link #exceeds(HttpServletRequest, Job)} for the job the request was dispatched to (the
     * nearest {@link Job} among the Stapler request's ancestors), or none.
     */
    public static boolean exceeds(HttpServletRequest req) {
        Job<?, ?> job = req instanceof StaplerRequest2 ? ((StaplerRequest2) req).findAncestorObject(Job.class) : null;
        return exceeds(req, job);
    }

    /**
     * Whether the body of the run request submission {@code req} for {@code job} is over the cap.
     * Call it after the permission checks and before anything reads the submitted values.
     * <ul>
     *   <li>A declared {@code Content-Length}: over the cap or not, from the header alone, for any
     *       content type; nothing of the body is read.</li>
     *   <li>No declared length and no body framing ({@code Transfer-Encoding}) and no multipart
     *       type: no body, never over the cap.</li>
     *   <li>Otherwise (D-72b (4)): the size of what Jenkins parsed from the body,
     *       {@link #parsedSize}. Jenkins has already parsed a multipart body while it dispatched
     *       the URL (D-72a); this only measures the parts. When it cannot be measured (not a
     *       Stapler request, or the parts cannot be read) the body counts as over the cap.</li>
     * </ul>
     */
    public static boolean exceeds(HttpServletRequest req, @CheckForNull Job<?, ?> job) {
        long cap = maxRequestBodyBytes();
        long length = req.getContentLengthLong();
        if (length >= 0) {
            return length > cap;
        }
        String contentType = req.getContentType();
        boolean multipart = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
        if (req.getHeader("Transfer-Encoding") == null && !multipart) {
            return false;
        }
        if (!(req instanceof StaplerRequest2)) {
            return true;
        }
        return parsedSize((StaplerRequest2) req, job) > cap;
    }

    /**
     * D-72b (4): the size in bytes of what Jenkins parsed from the body of {@code req}, as far as a
     * run request submission for {@code job} can read it: every request parameter (the query
     * string, url-encoded fields and the text fields of a multipart body, name and values, in
     * UTF-8) plus every uploaded file part the submission can reach. A file part is reached through
     * a parameter definition by name: the part named after one of {@code job}'s parameters (a
     * scripted submission without {@code json}), or the part a string in the {@code json} field
     * names (core's form, whose file parameters refer to their parts by name). A part nothing can
     * reach is never read, so it never ends up stored. {@link Long#MAX_VALUE} when the parts
     * cannot be read.
     */
    public static long parsedSize(StaplerRequest2 req, @CheckForNull Job<?, ?> job) {
        long total = 0;
        Map<String, String[]> fields = req.getParameterMap();
        for (Map.Entry<String, String[]> field : fields.entrySet()) {
            total += utf8Length(field.getKey());
            for (String value : field.getValue() == null ? new String[0] : field.getValue()) {
                total += utf8Length(value);
            }
        }
        Set<String> partNames = new LinkedHashSet<>();
        if (job != null) {
            ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
            if (property != null) {
                for (ParameterDefinition definition : property.getParameterDefinitions()) {
                    partNames.add(definition.getName());
                }
            }
        }
        String[] json = fields.get(JSON_FIELD);
        if (json != null) {
            for (String text : json) {
                collectStrings(text, partNames);
            }
        }
        try {
            for (String name : partNames) {
                FileItem<?> part = name == null ? null : req.getFileItem2(name);
                if (part != null) {
                    total += Math.max(0, part.getSize());
                }
            }
        } catch (ServletException | IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not measure the parsed parts of a run request submission", e);
            return Long.MAX_VALUE;
        }
        return total;
    }

    /**
     * security-37 S-37-01, D-72b (4): refuses typed values whose {@link #keptSize} is over the cap,
     * with a {@link RequestTooLargeException} naming the cap. The caller disposes of the values'
     * temporary files. Call it before anything of the request is stored.
     */
    public static void checkKept(List<? extends ParameterValue> values) {
        long cap = maxRequestBodyBytes();
        long size = keptSize(values);
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
     * security-37 S-37-01: the size in bytes of what a run request holding {@code values} would
     * keep, judged on the values themselves rather than on the body they came from: for each value
     * its name, plus
     * <ul>
     *   <li>for a file value ({@link ParameterDisplay#isFile}), its content: core's
     *       {@link FileParameterValue} file, the file-parameters plugin's stashed upload, or the
     *       decoded size of its Base64 value (neither plugin class is linked), or an uploaded file
     *       item or {@link File} raw value;</li>
     *   <li>for any other value, its stored text ({@link ParameterDisplay#storedText}) in UTF-8.</li>
     * </ul>
     * {@link Long#MAX_VALUE} when a file value's size cannot be determined (fail closed).
     */
    public static long keptSize(@CheckForNull List<? extends ParameterValue> values) {
        long total = 0;
        if (values == null) {
            return total;
        }
        for (ParameterValue value : values) {
            if (value == null) {
                continue;
            }
            long size = ParameterDisplay.isFile(value) ? fileSize(value) : utf8Length(ParameterDisplay.storedText(value));
            if (size == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            total = saturatedAdd(total, saturatedAdd(utf8Length(value.getName()), size));
        }
        return total;
    }

    /** The content size of the file value {@code value}, or {@link Long#MAX_VALUE} when unknown. */
    private static long fileSize(ParameterValue value) {
        try {
            if (value instanceof FileParameterValue) {
                FileItem<?> file = ((FileParameterValue) value).getFile2();
                return file == null ? 0 : Math.max(0, file.getSize());
            }
            if (ParameterDisplay.isStashedFile(value)) {
                return stashedFileSize(value);
            }
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
            if (raw instanceof String) {
                String text = (String) raw;
                return BASE64_FILE_VALUE.equals(value.getClass().getName()) ? decodedBase64Size(text) : utf8Length(text);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "Could not measure file parameter '" + value.getName() + "'");
        }
        LOGGER.warning(() -> "Could not determine the size of file parameter '" + value.getName() + "' ("
                + value.getClass().getName() + "); the run request is refused");
        return Long.MAX_VALUE;
    }

    /**
     * The size of the upload a file-parameters stashed value keeps until its build stashes it,
     * read from the value's path field (the plugin is optional and offers no accessor), or
     * {@link Long#MAX_VALUE} when the field cannot be read.
     */
    private static long stashedFileSize(ParameterValue value) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(STASHED_FILE_FIELD);
                field.setAccessible(true);
                Object path = field.get(value);
                if (path == null) {
                    return 0;
                }
                return path instanceof String ? new File((String) path).length() : Long.MAX_VALUE;
            } catch (NoSuchFieldException e) {
                // Look in the superclass.
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "Could not read the upload of file parameter '" + value.getName() + "'");
                return Long.MAX_VALUE;
            }
        }
        return Long.MAX_VALUE;
    }

    /** The number of bytes the Base64 text {@code text} decodes to (at most; whitespace counts). */
    private static long decodedBase64Size(String text) {
        int length = text.length();
        int padding = 0;
        while (padding < 2 && length - padding > 0 && text.charAt(length - padding - 1) == '=') {
            padding++;
        }
        return Math.max(0, (long) length * 3 / 4 - padding);
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

    /** Adds every string value of the JSON text {@code text} to {@code out}; ignores invalid JSON. */
    private static void collectStrings(@CheckForNull String text, Set<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            collect(JSONObject.fromObject(text), out);
        } catch (JSONException e) {
            // Not core's form data; the form reader refuses it later.
        }
    }

    private static void collect(Object node, Set<String> out) {
        if (node instanceof JSONObject) {
            for (Object value : ((JSONObject) node).values()) {
                collect(value, out);
            }
        } else if (node instanceof JSONArray) {
            for (Object value : (JSONArray) node) {
                collect(value, out);
            }
        } else if (node instanceof String) {
            out.add((String) node);
        }
    }

    private static long utf8Length(@CheckForNull String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
