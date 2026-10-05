package io.jenkins.plugins.batchcontrol.policy;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72b (4), security-37 S-37-01: a run request refused because what it would keep (its file
 * contents and the stored text of its other values, {@link RequestBodyLimit#keptSize}) is over the
 * cap {@link RequestBodyLimit#maxRequestBodyBytes()}. Nothing was stored and the submission's
 * temporary files were disposed of. An {@link IllegalArgumentException}, so callers that treat
 * refused input alike keep working; the web layer answers it with HTTP 413.
 */
@Restricted(NoExternalUse.class)
public final class RequestTooLargeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    RequestTooLargeException(String message, long limit) {
        super(message);
        this.limit = limit;
    }

    /** The cap in bytes the request was measured against. */
    public long getLimit() {
        return limit;
    }
}
