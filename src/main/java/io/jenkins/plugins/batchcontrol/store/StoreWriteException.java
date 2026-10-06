package io.jenkins.plugins.batchcontrol.store;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72b (2), security-35 S-35-03: an entity could not be written (an I/O failure, or content the
 * XML writer refused). Nothing was stored: the previous file, if any, is unchanged and the
 * temporary file of the attempt is deleted. It is an {@link IllegalStateException}, so the web
 * layer, which already answers a service's {@code IllegalArgumentException} and
 * {@code IllegalStateException} with a refusal message on the form, shows it the same way
 * instead of an error page; {@link #getMessage()} is meant for the user.
 */
@Restricted(NoExternalUse.class)
public class StoreWriteException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StoreWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
