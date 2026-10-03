package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.SimpleParameterDefinition;
import hudson.util.Secret;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * D-60: carrying the values of a refused build submission into the job's Request Run form.
 *
 * <p>The contract (frozen for tests): the Request Run page {@code <job>/batch-control/} accepts
 * query parameters {@value #PREFIX}{@code <parameter name>}; each one the job defines becomes the
 * default shown for that parameter. Anything else is ignored. Pre-fill only changes what the form
 * shows: nothing is queued or stored until the requester submits the form, whose field names are
 * unchanged.
 *
 * <p>Only parameters whose value round-trips through a string are carried
 * ({@link SimpleParameterDefinition}: string, text, boolean, choice and the like). Sensitive
 * values (password parameters, {@link Secret}s, anything {@link ParameterValue#isSensitive()})
 * are never written into the URL nor taken from it (P-03), file parameters cannot be, and a value
 * the definition refuses (a choice outside its choices) is dropped and leaves the job's default.
 * Values longer than {@value #MAX_VALUE_LENGTH} characters are left out so the redirect stays
 * within common URL limits; the user re-enters them.
 *
 * <p>A crafted link can only pre-fill a form the viewer may already open: the viewer still reads
 * and submits it, and every rendered value goes through the parameter definition's own view,
 * which escapes it.
 */
@Restricted(NoExternalUse.class)
public final class RequestRunPrefill {

    /** Query parameter prefix of a pre-filled value (frozen name). */
    public static final String PREFIX = "p.";

    /** Longest value carried in the redirect URL. */
    static final int MAX_VALUE_LENGTH = 2000;

    private RequestRunPrefill() {
    }

    /**
     * The values of {@code submitted} that may be carried to the Request Run form of {@code job},
     * by parameter name, in the job's definition order. Empty when nothing qualifies.
     */
    public static Map<String, String> carriedValues(Job<?, ?> job,
                                                    @CheckForNull List<ParameterValue> submitted) {
        Map<String, String> carried = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null || submitted == null) {
            return carried;
        }
        Map<String, ParameterValue> byName = new LinkedHashMap<>();
        for (ParameterValue value : submitted) {
            if (value != null && value.getName() != null) {
                byName.put(value.getName(), value);
            }
        }
        for (ParameterDefinition definition : property.getParameterDefinitions()) {
            ParameterValue value = byName.get(definition.getName());
            if (value == null || !isCarriable(definition) || value.isSensitive()) {
                continue;
            }
            Object raw = value.getValue();
            if (!(raw instanceof String || raw instanceof Boolean || raw instanceof Number)) {
                continue; // Secret, file, run or anything that does not round-trip as text
            }
            String text = String.valueOf(raw);
            if (text.length() <= MAX_VALUE_LENGTH) {
                carried.put(definition.getName(), text);
            }
        }
        return carried;
    }

    /** {@code ?p.A=1&p.B=x} for the given values, or the empty string for none. */
    public static String toQuery(Map<String, String> values) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            query.append(query.length() == 0 ? '?' : '&')
                    .append(URLEncoder.encode(PREFIX + entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    /**
     * {@code definitions} with each carriable one that has a {@value #PREFIX}{@code <name>} query
     * parameter in {@code req} replaced by a copy defaulting to that value. Only GET requests are
     * read: a POST is a submission, whose own values the form shows (e2e-03 DEF-09).
     */
    public static List<ParameterDefinition> apply(List<ParameterDefinition> definitions,
                                                  @CheckForNull StaplerRequest2 req) {
        if (req == null || !"GET".equals(req.getMethod())) {
            return definitions;
        }
        List<ParameterDefinition> shown = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            String text = definition.getName() == null ? null : req.getParameter(PREFIX + definition.getName());
            shown.add(text == null || !isCarriable(definition) ? definition : withDefault(definition, text));
        }
        return shown;
    }

    /**
     * Whether {@code req} is a GET carrying a {@value #PREFIX}{@code <name>} value for at least one
     * of {@code definitions} (the page then says where the values came from; constant text only).
     */
    public static boolean isPrefilled(List<ParameterDefinition> definitions,
                                      @CheckForNull StaplerRequest2 req) {
        if (req == null || !"GET".equals(req.getMethod())) {
            return false;
        }
        for (ParameterDefinition definition : definitions) {
            if (definition.getName() != null && isCarriable(definition)
                    && req.getParameter(PREFIX + definition.getName()) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCarriable(ParameterDefinition definition) {
        return definition instanceof SimpleParameterDefinition
                && !(definition instanceof PasswordParameterDefinition);
    }

    private static ParameterDefinition withDefault(ParameterDefinition definition, String text) {
        try {
            ParameterValue value = ((SimpleParameterDefinition) definition).createValue(text);
            if (value == null || value.isSensitive() || value.getValue() instanceof Secret) {
                return definition;
            }
            ParameterDefinition copy = definition.copyWithDefaultValue(value);
            return copy == null ? definition : copy;
        } catch (RuntimeException e) {
            return definition; // refused by the definition (e.g. not one of the choices)
        }
    }
}
