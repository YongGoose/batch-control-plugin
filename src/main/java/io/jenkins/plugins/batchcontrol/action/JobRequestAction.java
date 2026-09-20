package io.jenkins.plugins.batchcontrol.action;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Failure;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.util.Secret;
import io.jenkins.plugins.batchcontrol.config.BatchControlGlobalConfiguration;
import io.jenkins.plugins.batchcontrol.config.BatchControlJobProperty;
import io.jenkins.plugins.batchcontrol.model.RunRequest;
import io.jenkins.plugins.batchcontrol.policy.RunRequestService;
import io.jenkins.plugins.batchcontrol.security.BatchControlPermissions;
import io.jenkins.plugins.batchcontrol.ui.ApproverOptions;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Per-job "Request Run" page at {@code /job/<name>/batch-control/} (attached by
 * {@link JobRequestActionFactory}). Renders the request form — reason, approver choice and the
 * job's parameter definitions exactly like the core build page — and submits it to
 * {@link RunRequestService#create}.
 *
 * <p>The sidebar link is only visible when run control is on, the job requires approval and the
 * user holds {@code BatchControl/Request}; the URL itself stays routable and the view degrades to
 * an informational message.
 */
public class JobRequestAction implements Action {

    /** Mask stored instead of secret parameter values; never persist password plaintext. */
    private static final String SECRET_MASK = "********";

    private final Job<?, ?> job;

    public JobRequestAction(Job<?, ?> job) {
        this.job = job;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    // ---------------------------------------------------------------- Action

    @Override
    @CheckForNull
    public String getIconFileName() {
        if (!isActive() || !Jenkins.get().hasPermission(BatchControlPermissions.REQUEST)) {
            return null;
        }
        return "symbol-paper-plane-outline";
    }

    @Override
    public String getDisplayName() {
        return "Request Run";
    }

    @Override
    public String getUrlName() {
        return "batch-control";
    }

    // ---------------------------------------------------------------- view model

    /** True when run control is on and this job requires approved runs. */
    public boolean isActive() {
        if (!BatchControlGlobalConfiguration.get().isRunControlEnabled()) {
            return false;
        }
        BatchControlJobProperty property = job.getProperty(BatchControlJobProperty.class);
        return property != null && property.isApprovalRequired();
    }

    /** The job's parameter definitions, rendered by each definition's own {@code index.jelly}. */
    public List<ParameterDefinition> getParameterDefinitions() {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property == null ? Collections.emptyList() : property.getParameterDefinitions();
    }

    /** Approver candidates: global approver list ∩ job-level restriction (if configured). */
    public List<String> getApproverOptions() {
        return ApproverOptions.forJob(job);
    }

    // ---------------------------------------------------------------- submission

    /**
     * POST {@code submit} — creates the run request and redirects to its detail page at
     * {@code /batch-control/requests/<id>/}. Validation failures from the service render as a
     * {@link Failure} page with the message.
     */
    @RequirePOST
    public void doSubmit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException {
        job.checkPermission(Item.READ);
        Jenkins.get().checkPermission(BatchControlPermissions.REQUEST);

        JSONObject formData = req.getSubmittedForm();
        String reason = Util.fixEmptyAndTrim(formData.optString("reason", ""));
        String approver = Util.fixEmptyAndTrim(formData.optString("approver", ""));
        Map<String, String> parameters = parseParameters(req, formData);

        RunRequest request;
        try {
            request = RunRequestService.get().create(job, parameters, reason, approver);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new Failure(e.getMessage() == null ? "The request was rejected" : e.getMessage());
        }
        rsp.sendRedirect2(req.getContextPath() + "/batch-control/requests/"
                + Util.rawEncode(request.getId()) + "/");
    }

    /**
     * Parses the {@code parameter} JSON array the same way core's
     * {@code ParametersDefinitionProperty._doBuild} does (each entry goes through the parameter
     * definition's {@code createValue}), then flattens the values to strings.
     */
    private Map<String, String> parseParameters(StaplerRequest2 req, JSONObject formData) {
        Map<String, String> parameters = new LinkedHashMap<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return parameters;
        }
        Object parameter = formData.opt("parameter");
        if (parameter == null) {
            return parameters;
        }
        for (Object entry : JSONArray.fromObject(parameter)) {
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject jsonEntry = (JSONObject) entry;
            String name = jsonEntry.optString("name", null);
            if (name == null) {
                continue;
            }
            ParameterDefinition definition = property.getParameterDefinition(name);
            if (definition == null) {
                throw new Failure("No such parameter definition: " + name);
            }
            ParameterValue value = definition.createValue(req, jsonEntry);
            if (value != null) {
                parameters.put(value.getName(), flatten(value));
            }
        }
        return parameters;
    }

    /**
     * Flattens a {@link ParameterValue} to the string that is stored on the request. Sensitive
     * values (password parameters, {@link Secret}s) are masked and never stored in plaintext —
     * see the slice report for the resulting limitation on reproducing password parameters.
     */
    private static String flatten(ParameterValue value) {
        if (value.isSensitive()) {
            return SECRET_MASK;
        }
        Object raw = value.getValue();
        if (raw instanceof Secret) {
            return SECRET_MASK;
        }
        return raw == null ? "" : String.valueOf(raw);
    }
}
