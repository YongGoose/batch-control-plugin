package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * The refusal of a submitted form, rendered on the form itself (SPEC section 6 usability line,
 * e2e-03 DEF-09): the message sits next to the field it is about and what the user typed is kept.
 *
 * <p>A {@code do*} endpoint that refuses its input builds one of these and calls
 * {@link #render}: the same request is forwarded to the screen's {@code index.jelly} with HTTP 400
 * (the status the plain {@code Failure} page answered before, so scripted callers see no change),
 * and the view asks {@link #current(String)} for the messages and the submitted values. Nothing
 * here changes state; it runs after the endpoint's permission checks.
 *
 * <p>Submitted values are read back from the request only while an instance is attached to it,
 * i.e. only on the POST that is being refused. A GET never gets a value from its query string
 * into a field this way, and every value is rendered through Jelly's default escaping.
 *
 * <p>{@link #form} names which form on a page failed, so a page with several forms (approve and
 * reject both have a {@code comment} field) refills only the one that was submitted.
 */
@Restricted(NoExternalUse.class)
public final class FormErrors {

    /** Request attribute the refusal travels in from the endpoint to the view. */
    static final String ATTRIBUTE = FormErrors.class.getName();

    private static final FormErrors NONE = new FormErrors("");

    private final String form;

    private final Map<String, String> fields = new LinkedHashMap<>();

    @CheckForNull
    private String message;

    @CheckForNull
    private Object attachment;

    /** @param form the name of the form that was submitted ({@code reject}, {@code create}, ...) */
    public FormErrors(String form) {
        this.form = form;
    }

    /** Adds a message shown next to {@code field}; the first message for a field wins. */
    public FormErrors field(String field, String text) {
        fields.putIfAbsent(field, text);
        return this;
    }

    /** Sets the message shown above the form (one that is not about a single field). */
    public FormErrors message(String text) {
        this.message = text;
        return this;
    }

    /**
     * Files a service refusal next to the field its text is about, or above the form when no
     * keyword matches. {@code keywords} alternates keyword and field name
     * ({@code "reason", "reason", "approver", "approvers"}); the first keyword found in the
     * message (case-insensitive) decides.
     */
    public FormErrors fromService(@CheckForNull String text, String... keywords) {
        String shown = text == null || text.isBlank() ? "The request was refused." : text;
        String lower = shown.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 1 < keywords.length; i += 2) {
            if (lower.contains(keywords[i].toLowerCase(Locale.ROOT))) {
                return field(keywords[i + 1], shown);
            }
        }
        return message(shown);
    }

    /** Endpoint-side data the view needs to rebuild the form (for example parsed parameters). */
    public FormErrors attach(@CheckForNull Object data) {
        this.attachment = data;
        return this;
    }

    /** Whether anything was refused. */
    public boolean isEmpty() {
        return fields.isEmpty() && message == null;
    }

    /** The inverse of {@link #isEmpty()}, for Jelly. */
    public boolean isPresent() {
        return !isEmpty();
    }

    public String getForm() {
        return form;
    }

    /** The message above the form, or {@code null}. */
    @CheckForNull
    public String getMessage() {
        return message;
    }

    /** The message next to {@code field}, or {@code null}. */
    @CheckForNull
    public String get(String field) {
        return fields.get(field);
    }

    @CheckForNull
    public Object getAttachment() {
        return attachment;
    }

    /** What the user submitted in {@code field}; empty when nothing is being refused. */
    public String value(String field) {
        if (isEmpty()) {
            return "";
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req == null ? "" : Util.fixNull(req.getParameter(field));
    }

    /** Whether {@code value} was among the submitted values of the repeated {@code field}. */
    public boolean checked(String field, String value) {
        if (isEmpty()) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        String[] values = req == null ? null : req.getParameterValues(field);
        return values != null && Arrays.asList(values).contains(value);
    }

    /**
     * Forwards the current request to {@code it}'s {@code index.jelly} with HTTP 400 and this
     * refusal attached.
     */
    public void render(StaplerRequest2 req, StaplerResponse2 rsp, Object it)
            throws IOException, ServletException {
        req.setAttribute(ATTRIBUTE, this);
        RequestDispatcher view = req.getView(it, "index.jelly");
        rsp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        if (view == null) {
            // No view to return to: a plain-text refusal still says why.
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getWriter().println(message != null ? message : String.join("\n", fields.values()));
            return;
        }
        view.forward(req, rsp);
    }

    /** The refusal of form {@code form} on this request, or an empty one. Never {@code null}. */
    public static FormErrors current(String form) {
        FormErrors errors = current();
        return errors.form.equals(form) ? errors : NONE;
    }

    /** The refusal attached to this request, whatever form it is for, or an empty one. */
    public static FormErrors current() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        Object attached = req == null ? null : req.getAttribute(ATTRIBUTE);
        return attached instanceof FormErrors ? (FormErrors) attached : NONE;
    }
}
