package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import jakarta.servlet.ServletException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 *     come back garbled (UTF-8 {@code 山田} read as {@code å±±ç°}) next to the blob's intact copy.</li>
 * <li>multipart without a blob, or a blob without string values for the field (for example the
 *     boolean array of checkboxes without {@code json}): the raw part, read as UTF-8 where
 *     {@link #asTyped} allows it (R4-03), else as Stapler decoded it.</li>
 * <li>urlencoded: {@code getParameterValues} and nothing else, as before.</li>
 * </ul>
 *
 * <p>R4-03: a single plain text field ({@link #text}) follows the same rule: the blob's string
 * value when the multipart body carries one, else the part read as UTF-8 where allowed.
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
        String[] raw = parameterValues(req, field);
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

    /**
     * R4-03: the value of the plain text field {@code field} as the user typed it, or {@code null}
     * when the request has no such field. On a multipart body the {@code json} blob's string value
     * for the field wins when there is one (as in {@link #values}), else Stapler's value of the
     * part as {@link #asTyped} reads it; on any other body {@code getParameter}, unchanged.
     */
    @CheckForNull
    public static String text(StaplerRequest2 req, String field) {
        String raw = req.getParameter(field);
        if (!isMultipart(req)) {
            return raw;
        }
        JSONObject json = submittedJson(req);
        if (json != null && json.opt(field) instanceof String) {
            return json.getString(field);
        }
        return asTyped(req, raw);
    }

    /**
     * R4-03: {@code getParameterValues(field)} with the value of the multipart part read as UTF-8
     * where {@link #asTyped} allows it. For a multipart body Stapler answers the query string's
     * values followed by the part's value, so the last element is the part's; any other body is
     * answered unchanged.
     */
    @CheckForNull
    public static String[] parameterValues(StaplerRequest2 req, String field) {
        String[] raw = req.getParameterValues(field);
        if (raw == null || raw.length == 0 || !isMultipart(req)) {
            return raw;
        }
        String last = raw[raw.length - 1];
        String typed = asTyped(req, last);
        if (typed == null || typed.equals(last)) {
            return raw;
        }
        String[] out = raw.clone();
        out[out.length - 1] = typed;
        return out;
    }

    /**
     * R4-03: Stapler's value of a text part of a multipart body, {@code value}, read back as the
     * UTF-8 text the client sent; {@code value} itself when that does not apply.
     *
     * <p>Stapler decodes a text part that names no charset of its own as ISO-8859-1 (the default of
     * commons-fileupload), while it decodes the {@code json} part of the same body with the
     * request's character encoding; it gives plugins no access to the part's bytes
     * ({@code getFileItem2} answers {@code null} for a text field). ISO-8859-1 maps each byte to the
     * character of the same number, so the bytes are recovered exactly from the string and decoded
     * as UTF-8. That is done only when all of these hold, which is what makes it safe:
     * <ul>
     * <li>the request is multipart (a urlencoded body is decoded by the container, never here);</li>
     * <li>the request's character encoding is UTF-8 or unset. Every Jenkins page is served as
     *     UTF-8 and a browser submits a form in its page's encoding; core's
     *     {@code CharacterEncodingFilter} sets UTF-8 on a POST that names no charset, and Stapler
     *     decodes the {@code json} blob of the same body with it, so the plain part then reads
     *     exactly as the blob's copy. A request that declares another charset is left alone;</li>
     * <li>every character is in the ISO-8859-1 range: a string with any character above U+00FF was
     *     not produced by that decoding (for example a part that names its own charset), and is
     *     left alone;</li>
     * <li>the bytes are valid UTF-8. They are decoded strictly: anything else (a client that sent
     *     Latin-1 bytes without saying so) keeps Stapler's value, so no input becomes U+FFFD.</li>
     * </ul>
     * An ASCII value reads the same either way and is returned as it is. The one value read
     * differently than intended would be text that is itself, character for character, the
     * ISO-8859-1 reading of valid UTF-8 and reached Stapler already decoded (a part naming its own
     * charset, or a query-string value of the same name when the body has no such part); browsers,
     * {@code curl -F} and {@code requests} send neither for the Request Run form. Values only: the
     * caller validates the result as before.
     */
    @CheckForNull
    static String asTyped(StaplerRequest2 req, @CheckForNull String value) {
        if (value == null || !isMultipart(req) || !isUtf8OrUnset(req.getCharacterEncoding())) {
            return value;
        }
        byte[] bytes = new byte[value.length()];
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0xFF) {
                return value;
            }
            ascii &= c < 0x80;
            bytes[i] = (byte) c;
        }
        if (ascii) {
            return value;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return value;
        }
    }

    private static boolean isUtf8OrUnset(@CheckForNull String encoding) {
        if (encoding == null) {
            return true;
        }
        try {
            return StandardCharsets.UTF_8.equals(Charset.forName(encoding));
        } catch (IllegalArgumentException e) {
            return false;
        }
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
