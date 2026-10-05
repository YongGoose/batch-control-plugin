package io.jenkins.plugins.batchcontrol.model;

import hudson.model.ParameterValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The typed parameter values of one run request (D-72, D-74), persisted with XStream at
 * {@code requests/run/<id>.values.xml}, next to the request file, as core keeps parameter values
 * in {@code build.xml}: a {@code Secret} field is written in Jenkins' encrypted form. Only the
 * store reads and writes this file; the approved build is scheduled with these values unchanged.
 */
@Restricted(NoExternalUse.class)
public final class RunRequestValues {

    private final String requestId;
    private final List<ParameterValue> values;

    public RunRequestValues(String requestId, List<ParameterValue> values) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.values = new ArrayList<>(Objects.requireNonNull(values, "values"));
    }

    /** The id of the request these values belong to. */
    public String requestId() {
        return requestId;
    }

    /**
     * The values, unmodifiable; an element that could not be loaded may be {@code null}. Not a
     * bean getter, so neither Jelly nor Stapler binding can reach the values.
     */
    public List<ParameterValue> values() {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
