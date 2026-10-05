package io.jenkins.plugins.batchcontrol.policy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import jenkins.util.SystemProperties;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72: the cap on the body of a run request submission. Requesting a run does not require
 * {@code Item/Build} (D-38a), and a submission may carry file parameters, so the body size is
 * checked after the permission check and before the form is read; a submission over the cap is
 * answered with HTTP 413 and no request is created.
 *
 * <p>The cap is the system property {@value #PROPERTY} (bytes), default {@value #DEFAULT_MAX_BYTES}
 * (100 MB), read on every check. A missing, unparsable or non-positive value means the default.
 */
@Restricted(NoExternalUse.class)
public final class RequestBodyLimit {

    /** System property holding the cap in bytes. */
    public static final String PROPERTY = "io.jenkins.plugins.batchcontrol.maxRequestBodyBytes";

    /** Default cap: 100 MB. */
    public static final long DEFAULT_MAX_BYTES = 104_857_600L;

    private RequestBodyLimit() {
    }

    /** The current cap in bytes. */
    public static long maxRequestBodyBytes() {
        Long configured = SystemProperties.getLong(PROPERTY, DEFAULT_MAX_BYTES);
        return configured == null || configured <= 0 ? DEFAULT_MAX_BYTES : configured;
    }

    /**
     * Whether the body of {@code req} is over the cap, judged from its headers alone (nothing is
     * read): a declared {@code Content-Length} larger than the cap, or a body of undeclared length
     * that may still be large (chunked transfer, or a {@code multipart/form-data} body without a
     * length), because its size cannot be known before it is read. A request without a body is
     * never over the cap.
     */
    public static boolean exceeds(HttpServletRequest req) {
        long length = req.getContentLengthLong();
        if (length >= 0) {
            return length > maxRequestBodyBytes();
        }
        String contentType = req.getContentType();
        return req.getHeader("Transfer-Encoding") != null
                || contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }
}
