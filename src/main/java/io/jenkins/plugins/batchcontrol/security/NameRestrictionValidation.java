package io.jenkins.plugins.batchcontrol.security;

import hudson.model.Failure;
import hudson.util.FormValidation;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * e2e-03 DEF-19: the answer to core's {@code checkJobName} / {@code checkNewName} validation when
 * the typed name is outside the name restriction of the holder's Create grant. Thrown from the
 * permission check core makes before it validates the name, it renders as the form validation
 * error next to the name field instead of an Access Denied the page cannot show. Stapler serves
 * an exception that is an {@link HttpResponse} as that response and does not log it.
 *
 * <p>The same explanation answers a refused {@code createItem} or {@code confirmRename} POST, in
 * place of core's "missing the Job/Create permission". Only ever thrown for the current user's
 * own request, after the refusal was decided (and, for a POST, recorded); nothing has changed.
 */
@Restricted(NoExternalUse.class)
final class NameRestrictionValidation extends RuntimeException implements HttpResponse {

    private static final long serialVersionUID = 1L;

    /** Whether this answers a validation request (a field message) rather than a refused change. */
    private final boolean validation;

    private NameRestrictionValidation(String message, boolean validation) {
        super(message, null, false, false);
        this.validation = validation;
    }

    /** The answer to {@code checkJobName} / {@code checkNewName}: the error next to the field. */
    static NameRestrictionValidation validation(String message) {
        return new NameRestrictionValidation(message, true);
    }

    /** The answer to a refused {@code createItem} / {@code confirmRename} POST (HTTP 400). */
    static NameRestrictionValidation refusal(String message) {
        return new NameRestrictionValidation(message, false);
    }

    @Override
    public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
            throws IOException, ServletException {
        // Both escape the message.
        if (validation) {
            FormValidation.error(getMessage()).generateResponse(req, rsp, node);
        } else {
            new Failure(getMessage()).generateResponse(req, rsp, node);
        }
    }
}
