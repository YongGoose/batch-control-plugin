package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import io.jenkins.plugins.batchcontrol.store.XmlChars;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

    /** False when the submission's input must not be read back (D-72: body over the size cap). */
    private boolean inputKept = true;

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

    /**
     * As {@link #fromService}, but a keyword counts only at the start of the message
     * (case-insensitive). D-72b: for messages that may quote user data further on (parameter
     * names, for one), so a word inside the quoted data cannot misfile the message.
     */
    public FormErrors fromServiceStartingWith(@CheckForNull String text, String... prefixes) {
        String shown = text == null || text.isBlank() ? "The request was refused." : text;
        String lower = shown.toLowerCase(Locale.ROOT);
        for (int i = 0; i + 1 < prefixes.length; i += 2) {
            if (lower.startsWith(prefixes[i].toLowerCase(Locale.ROOT))) {
                return field(prefixes[i + 1], shown);
            }
        }
        return message(shown);
    }

    /** Endpoint-side data the view needs to rebuild the form (for example parsed parameters). */
    public FormErrors attach(@CheckForNull Object data) {
        this.attachment = data;
        return this;
    }

    /**
     * D-72: the submission is refused without its input being read (its body is over the size
     * cap, and reading a field would parse it), so the form is shown empty: {@link #value} and
     * {@link #checked} return nothing and the view may use its defaults.
     */
    public FormErrors withoutInput() {
        this.inputKept = false;
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

    /**
     * Whether the refused form is shown with the user's input ({@link #value}, {@link #checked}):
     * something was refused and the input could be read ({@link #withoutInput()}).
     */
    public boolean isInputKept() {
        return isPresent() && inputKept;
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

    /**
     * What the user submitted in {@code field}; empty when nothing is being refused. D-72b: each
     * character XML cannot hold is given back as U+FFFD ({@link #displayable}).
     */
    public String value(String field) {
        if (!isInputKept()) {
            return "";
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req == null ? "" : displayable(Util.fixNull(req.getParameter(field)));
    }

    /**
     * D-72b: {@code text} with each character XML 1.0 cannot hold (U+0000, a lone surrogate, ...)
     * replaced by U+FFFD, as a browser shows it. A refusal for such a character gives the input
     * back this way, so the page never carries the character itself and the user sees where it
     * was.
     */
    public static String displayable(String text) {
        int bad = XmlChars.firstInvalid(text);
        if (bad < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int from = 0;
        while (bad >= 0) {
            out.append(text, from, from + bad).append('�');
            from += bad + 1;
            bad = XmlChars.firstInvalid(text.subSequence(from, text.length()));
        }
        return out.append(text, from, text.length()).toString();
    }

    /**
     * Whether {@code value} was among the submitted values of the repeated {@code field}. D-37: on
     * a multipart body Stapler keeps only the last part of the field, so the form's {@code json}
     * blob is read instead when it holds values for the field ({@link RepeatedField}), and a
     * refused form re-renders the same boxes checked that the service saw.
     */
    public boolean checked(String field, String value) {
        if (!isInputKept()) {
            return false;
        }
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req != null && RepeatedField.values(req, null, field).contains(value);
    }

    /**
     * Forwards the current request to {@code it}'s {@code index.jelly} with HTTP 400 and this
     * refusal attached.
     */
    public void render(StaplerRequest2 req, StaplerResponse2 rsp, Object it)
            throws IOException, ServletException {
        render(req, rsp, it, "index.jelly");
    }

    /**
     * As {@link #render(StaplerRequest2, StaplerResponse2, Object)}, to the view {@code view} of
     * {@code it}: D-66, a form submitted from a dialog is answered with the dialog's own view, so
     * core's dialog replaces its form with the refused one (messages shown, input kept).
     */
    public void render(StaplerRequest2 req, StaplerResponse2 rsp, Object it, String view)
            throws IOException, ServletException {
        render(req, rsp, it, view, HttpServletResponse.SC_BAD_REQUEST);
    }

    /**
     * As {@link #render(StaplerRequest2, StaplerResponse2, Object, String)} with the HTTP status
     * {@code status} (D-72: 413 for a body over the size cap).
     */
    public void render(StaplerRequest2 req, StaplerResponse2 rsp, Object it, String view, int status)
            throws IOException, ServletException {
        req.setAttribute(ATTRIBUTE, this);
        RequestDispatcher dispatcher = req.getView(it, view);
        rsp.setStatus(status);
        if (dispatcher == null) {
            // No view to return to: a plain-text refusal still says why.
            rsp.setContentType("text/plain;charset=UTF-8");
            rsp.getWriter().println(message != null ? message : String.join("\n", fields.values()));
            return;
        }
        dispatcher.forward(req, rsp);
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
