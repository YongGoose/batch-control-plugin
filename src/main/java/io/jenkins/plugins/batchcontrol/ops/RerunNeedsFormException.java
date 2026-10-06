package io.jenkins.plugins.batchcontrol.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * D-72 (SPEC item 11): an incident rerun could not recover every parameter value of the failed
 * run (a stashed file, which the build clears when it completes; a core file whose copy under
 * the build directory is gone; a deleted build), so no request was created. The rerun continues
 * on the job's Request Run form, prefilled with {@link #getPrefill()}: the recoverable values
 * that are neither sensitive nor files (secret values are never prefilled, as for D-60).
 */
@Restricted(NoExternalUse.class)
public class RerunNeedsFormException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final LinkedHashMap<String, String> prefill;

    public RerunNeedsFormException(String message, Map<String, String> prefill) {
        super(message);
        this.prefill = new LinkedHashMap<>(prefill);
    }

    /**
     * The recoverable non-sensitive, non-file values of the failed run as text, by parameter name
     * in the run's order; possibly empty. Unmodifiable.
     */
    public Map<String, String> getPrefill() {
        return Collections.unmodifiableMap(prefill);
    }
}
