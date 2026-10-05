package io.jenkins.plugins.batchcontrol.policy;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
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
 */
@Restricted(NoExternalUse.class)
public final class RequestBodyLimit {

    private static final Logger LOGGER = Logger.getLogger(RequestBodyLimit.class.getName());

    /** System property holding the cap in bytes. */
    public static final String PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    /** Default cap: 100 MB. */
    public static final long DEFAULT_MAX_BYTES = 104_857_600L;

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
