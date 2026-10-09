package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import jakarta.servlet.ServletException;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Reads the values of a repeated form field (one value per entry, in submission order) from
 * an {@code application/x-www-form-urlencoded} or a {@code multipart/form-data} body alike.
 *
 * <p>Stapler keeps one value per name for a multipart body: {@code RequestImpl} parses the
 * parts into a map keyed by field name, so a repeated field keeps only its last part. Neither
 * {@code getParameterValues} nor {@code getFileItem2} can return the others, and the body
 * stream has already been consumed by then (core may have parsed it during dispatch, before any
 * plugin code). So for a multipart request the {@code json} blob is read as well. {@code f:form}
 * posts that blob with every submission, and Stapler parses it from the same body.
 *
 * <p>The rule (D-37):
 * <ul>
 * <li>multipart, and the blob holds string values for the field: those values, in their order,
 *     and nothing else. The raw part is not added: it is one of the blob's values for the rendered
 *     form, and Stapler decodes a part without a charset as ISO-8859-1, so a non-ASCII value would
 *     come back garbled (UTF-8 {@code 山田} read as {@code å±±ç°}) next to the blob's intact copy.
 *     The raw part is never re-decoded.</li>
 * <li>multipart without a blob, or a blob without string values for the field (for example the
 *     boolean array of checkboxes without {@code json}): the raw part, as before.</li>
 * <li>urlencoded: {@code getParameterValues} and nothing else, as before.</li>
 * </ul>
 *
 * <p>Nothing here parses a body that the caller has not let be parsed already. The blob is read
 * only when the request is multipart and carries a non-empty {@code json} field, and only after
 * {@code getParameter}, which parses the body in the first place. Callers read fields only after
 * their permission (and, for the run request, D-72 size) checks. A blob that is not a JSON object
 * is ignored, so the raw part is used as before. These are values only: the caller validates them.
 */
@Restricted(NoExternalUse.class)
public final class RepeatedField {

    private RepeatedField() {
    }

    /** Whether Stapler reads this request's fields from a parsed multipart body (its own test). */
    public static boolean isMultipart(StaplerRequest2 req) {
        String type = req.getContentType();
        return type != null && type.startsWith("multipart/");
    }

    /**
     * @param req the current request
     * @param formData the parsed {@code json} blob when the caller already has it, otherwise
     *        {@code null} (for a multipart request it is then read from the request)
     * @param field the field name, also the key of the value in the blob's top-level object
     * @return the submitted values in submission order, possibly empty, never {@code null}
     */
    public static List<String> values(StaplerRequest2 req, @CheckForNull JSONObject formData, String field) {
        List<String> out = new ArrayList<>();
        String[] raw = req.getParameterValues(field);
        if (isMultipart(req)) {
            JSONObject json = formData != null ? formData : submittedJson(req);
            if (json != null) {
                addJson(out, json.opt(field));
                if (hasValue(out)) {
                    // D-37: the blob's values are the whole set; the raw part is one of them,
                    // possibly decoded with the wrong charset, so it must not add a copy.
                    return out;
                }
                out.clear();
            }
        }
        if (raw != null) {
            for (String value : raw) {
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /** Whether {@code values} holds at least one non-blank value (a value the caller can use). */
    private static boolean hasValue(List<String> values) {
        for (String value : values) {
            if (!value.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a value of the {@code json} blob: a string, or each string of an array (what a group of
     * {@code f:checkbox json="..."} posts). Booleans (a checkbox without {@code json}) and
     * objects carry no value and are ignored.
     */
    static void addJson(List<String> out, @CheckForNull Object value) {
        if (value instanceof String) {
            out.add((String) value);
        } else if (value instanceof JSONArray) {
            for (Object element : (JSONArray) value) {
                if (element instanceof String) {
                    out.add((String) element);
                }
            }
        }
    }

    /**
     * The {@code json} blob of this request, or {@code null} when it carries none or the blob is
     * not a JSON object. {@code getSubmittedForm} answers a missing or empty field with an error
     * response, so it is called only when the field is there. It caches what it parsed, so a
     * caller that has already read the blob does not cause a second parse.
     */
    @CheckForNull
    private static JSONObject submittedJson(StaplerRequest2 req) {
        String blob = req.getParameter("json");
        if (blob == null || blob.isEmpty()) {
            return null;
        }
        try {
            return req.getSubmittedForm();
        } catch (ServletException | JSONException e) {
            return null;
        }
    }
}
