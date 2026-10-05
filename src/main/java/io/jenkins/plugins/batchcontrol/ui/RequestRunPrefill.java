package io.jenkins.plugins.batchcontrol.ui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.FileParameterDefinition;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.SimpleParameterDefinition;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.store.FileParametersSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
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
 * within common URL limits, and a longer {@value #PREFIX} query value is ignored; the user
 * re-enters them. The whole encoded query is capped at {@value #MAX_QUERY_LENGTH} characters:
 * in definition order, a value that would take it past the cap is left out (S-33-03).
 *
 * <p>A crafted link can only pre-fill a form the viewer may already open: the viewer still reads
 * and submits it, and every rendered value goes through the parameter definition's own view,
 * which escapes it.
 *
 * <p>D-72: an incident rerun whose failed run has a value that cannot be recovered continues on
 * the same form ({@link #rerunQuery}): the recoverable non-sensitive values travel as
 * {@value #PREFIX}{@code <name>} under the same caps, and {@value #FROM_RERUN}{@code =<incident id>}
 * tells the form to say which values must be provided again. File parameters are never carried
 * ({@link #isFileDefinition}); carrying them from a refused build is issue #115.
 */
@Restricted(NoExternalUse.class)
public final class RequestRunPrefill {

    /** Query parameter prefix of a pre-filled value (frozen name). */
    public static final String PREFIX = "p.";

    /**
     * D-72: query parameter naming the incident whose rerun continues on the Request Run form
     * because a value of the failed run could not be recovered.
     */
    public static final String FROM_RERUN = "fromRerun";

    /** Longest value carried in the redirect URL. */
    static final int MAX_VALUE_LENGTH = 2000;

    /**
     * Longest query string the redirect may carry, URL-encoded, including the leading {@code ?}
     * and the separators (security-33 S-33-03: the redirect's {@code Location} header stays
     * well inside common header limits however many parameters the job has).
     */
    static final int MAX_QUERY_LENGTH = 4000;

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
        int queryLength = 0;
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
            if (text.length() > MAX_VALUE_LENGTH) {
                continue;
            }
            // S-33-03: in definition order, a value is carried only while the encoded query
            // stays within MAX_QUERY_LENGTH; one that would exceed it is left out (a later,
            // shorter one may still fit). The form still opens, just less pre-filled.
            int added = 1 + encode(PREFIX + definition.getName()).length() + 1 + encode(text).length();
            if (queryLength + added > MAX_QUERY_LENGTH) {
                continue;
            }
            queryLength += added;
            carried.put(definition.getName(), text);
        }
        return carried;
    }

    /**
     * D-72: the query of the Request Run form an incident rerun continues on when a value of the
     * failed run could not be recovered ({@code RerunNeedsFormException}): the {@code prefill}
     * values the job defines as carriable parameters, under the same caps as
     * {@link #carriedValues} ({@value #MAX_VALUE_LENGTH} characters per value,
     * {@value #MAX_QUERY_LENGTH} for the encoded {@value #PREFIX} part, definition order), then
     * {@value #FROM_RERUN}{@code =<incidentId>}. A password parameter is never carried, whatever
     * {@code prefill} holds.
     */
    public static String rerunQuery(Job<?, ?> job, Map<String, String> prefill, String incidentId) {
        Map<String, String> carried = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property != null && prefill != null) {
            int queryLength = 0;
            for (ParameterDefinition definition : property.getParameterDefinitions()) {
                String text = prefill.get(definition.getName());
                if (text == null || !isCarriable(definition) || text.length() > MAX_VALUE_LENGTH) {
                    continue;
                }
                int added = 1 + encode(PREFIX + definition.getName()).length() + 1 + encode(text).length();
                if (queryLength + added > MAX_QUERY_LENGTH) {
                    continue;
                }
                queryLength += added;
                carried.put(definition.getName(), text);
            }
        }
        String query = toQuery(carried);
        return query + (query.isEmpty() ? '?' : '&') + FROM_RERUN + '=' + encode(incidentId);
    }

    /**
     * Whether {@code definition} takes a file: core's {@link FileParameterDefinition} or a
     * definition of the optional file-parameters plugin (stashedFile, base64File; through
     * {@link FileParametersSupport#isFileDefinition}). A file is never carried into the form; the
     * user selects it again.
     */
    public static boolean isFileDefinition(@CheckForNull ParameterDefinition definition) {
        return definition instanceof FileParameterDefinition
                || FileParametersSupport.isFileDefinition(definition);
    }

    /** {@code ?p.A=1&p.B=x} for the given values, or the empty string for none. */
    public static String toQuery(Map<String, String> values) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            query.append(query.length() == 0 ? '?' : '&')
                    .append(encode(PREFIX + entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()));
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
            String text = prefillValue(definition, req);
            shown.add(text == null ? definition : withDefault(definition, text));
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
            if (prefillValue(definition, req) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@value #PREFIX}{@code <name>} value of {@code req} for a carriable definition, or
     * {@code null}: absent, not carriable, or longer than {@value #MAX_VALUE_LENGTH} characters
     * (the same bound as {@link #carriedValues}, so a hand-made link cannot carry more either).
     */
    @CheckForNull
    private static String prefillValue(ParameterDefinition definition, StaplerRequest2 req) {
        if (!isCarriable(definition)) {
            return null;
        }
        String text = req.getParameter(PREFIX + definition.getName());
        return text == null || text.length() > MAX_VALUE_LENGTH ? null : text;
    }

    /**
     * D-72a: the raw {@value #FROM_RERUN} value of {@code req}'s query string alone (never its
     * body), URL-decoded, or {@code null}. The Request Run form's action carries the validated
     * incident reference there as well as in its hidden field, so that a submission refused before
     * its body may be read (over the D-72 size cap) can still show the form linked to the incident.
     * Unvalidated: the caller passes it to {@code IncidentService#linkableIncident} and ignores what
     * that check refuses, exactly like the hidden field.
     */
    @CheckForNull
    public static String rerunFromQuery(@CheckForNull HttpServletRequest req) {
        String query = req == null ? null : req.getQueryString();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && FROM_RERUN.equals(pair.substring(0, eq))) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return null; // a malformed escape is no reference
                }
            }
        }
        return null;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
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
