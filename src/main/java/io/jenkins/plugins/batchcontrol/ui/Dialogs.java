package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import jakarta.servlet.http.HttpServletRequest;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-66: request forms opened in core's dialog ({@code data-type="dialog-opener"} with
 * {@code data-dialog-url}, the mechanism of core's "Create User" and build-parameters dialogs).
 *
 * <p>Core's dialog loads the URL, shows the first {@code <form>} of the answer and submits it with
 * the crumb header; a redirect answer is followed by the whole page (to the new request), any
 * other answer that holds a form replaces the dialog's form. A dialog form therefore carries the
 * hidden field {@value #PARAMETER}, and a refused submission is answered with the dialog view, not
 * the full page. The field only selects which view shows the refusal: every check is the same.
 *
 * <p>D-72: a dialog form may also carry the marker in its action's query string
 * ({@code ?dialog=true}), so that a submission refused before its body is read (over the body
 * size cap) can still be answered with the dialog view: {@link #fromDialogQuery} reads the query
 * string alone and never the body.
 */
@Restricted(NoExternalUse.class)
public final class Dialogs {

    /** Hidden form field marking a submission made from a dialog. */
    public static final String PARAMETER = "dialog";

    /** View name of the dialog form of a screen. */
    public static final String DIALOG_VIEW = "dialog.jelly";

    private Dialogs() {
    }

    /** Whether {@code req} was submitted from a dialog form. */
    public static boolean fromDialog(@CheckForNull StaplerRequest2 req) {
        return req != null && "true".equals(req.getParameter(PARAMETER));
    }

    /**
     * Whether the query string of {@code req} (never its body) marks a dialog submission
     * ({@code dialog=true}). For a refusal made before the body may be read (D-72 size cap):
     * {@code getParameter} would parse a multipart body.
     */
    public static boolean fromDialogQuery(@CheckForNull HttpServletRequest req) {
        String query = req == null ? null : req.getQueryString();
        if (query == null) {
            return false;
        }
        for (String pair : query.split("&")) {
            if (pair.equals(PARAMETER + "=true")) {
                return true;
            }
        }
        return false;
    }

    /** The view that shows a refusal of {@code req}: the dialog's, else {@code pageView}. */
    public static String refusalView(@CheckForNull StaplerRequest2 req, String pageView) {
        return fromDialog(req) ? DIALOG_VIEW : pageView;
    }

    /**
     * {@code ../} once per path segment of an item URL ({@code job/a/job/b/}), the way from the
     * item's page back to the Jenkins root (which carries the context path, if any).
     */
    public static String toRoot(String itemUrl) {
        StringBuilder up = new StringBuilder();
        for (String segment : itemUrl.split("/")) {
            if (!segment.isEmpty()) {
                up.append("../");
            }
        }
        return up.toString();
    }
}
